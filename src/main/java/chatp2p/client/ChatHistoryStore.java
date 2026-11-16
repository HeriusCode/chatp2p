package chatp2p.client;

import chatp2p.model.ChatMessage;
import chatp2p.model.FileInfo;
import chatp2p.model.GroupMessage;
import chatp2p.model.TransferDirection;
import chatp2p.model.TransferProgress;
import chatp2p.model.TransferState;
import chatp2p.protocol.MessageType;
import chatp2p.protocol.ProtocolMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Reads and writes account history through the server's PostgreSQL archive. */
public final class ChatHistoryStore {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int PAGE_SIZE = 3;

    public record StoredFile(FileInfo file, TransferDirection direction, TransferState state,
                             long transferredBytes, Instant createdAt, Path sourcePath,
                             Path savedPath, Path downloadedPath, String failureReason) {
    }

    public record Snapshot(List<ChatMessage> directMessages, List<GroupMessage> groupMessages,
                           List<StoredFile> files) {
    }

    private record LocalPaths(Path source, Path saved, Path downloaded) {
    }

    private final String owner;
    private final ServerConnection server;
    private final ConcurrentHashMap<UUID, LocalPaths> localPaths = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Instant> fileCreatedAt = new ConcurrentHashMap<>();

    ChatHistoryStore(String owner, ServerConnection server) {
        this.owner = owner;
        this.server = server;
    }

    public void save(ChatMessage message) throws IOException {
        put(ProtocolMessage.builder(MessageType.HISTORY_PUT)
                .field("kind", "DIRECT")
                .field("id", message.messageId().toString())
                .field("sender", message.sender())
                .field("receiver", message.receiver())
                .field("content", message.content())
                .field("time", Long.toString(message.timestamp().toEpochMilli())));
    }

    public void save(GroupMessage message) throws IOException {
        put(ProtocolMessage.builder(MessageType.HISTORY_PUT)
                .field("kind", "GROUP")
                .field("id", message.messageId().toString())
                .field("sender", message.sender())
                .field("conversation", message.groupName())
                .field("content", message.content())
                .field("time", Long.toString(message.timestamp().toEpochMilli())));
    }

    public void saveFileSource(FileInfo file, Path source) throws IOException {
        updatePaths(file.fileId(), source, null, null);
        putFile(file, TransferDirection.SENDING, TransferState.WAITING, 0, null, null);
    }

    public void saveFileOffer(FileInfo file) throws IOException {
        putFile(file, TransferDirection.RECEIVING, TransferState.WAITING, 0, null, null);
    }

    public void saveFileProgress(TransferProgress progress) throws IOException {
        if (progress.state() == TransferState.TRANSFERRING) {
            return;
        }
        putFile(progress.file(), progress.direction(), progress.state(),
                progress.transferredBytes(), null, null);
    }

    public void saveFileCompleted(FileInfo file, Path saved) throws IOException {
        if (saved != null) {
            updatePaths(file.fileId(), null, saved, null);
        }
        putFile(file, direction(file), TransferState.SUCCESS, file.size(), saved, null);
    }

    public void saveFileFailed(FileInfo file, String reason) throws IOException {
        putFile(file, direction(file), TransferState.FAILED, 0, null, reason);
    }

    public void saveFileAttempt(FileInfo file, Path source, String reason) throws IOException {
        updatePaths(file.fileId(), source, null, null);
        putFile(file, TransferDirection.SENDING, TransferState.FAILED, 0, null, reason);
    }

    /** Download destinations belong to this process; the server stores transfer metadata. */
    public void saveDownloadedPath(UUID fileId, Path downloaded) {
        updatePaths(fileId, null, null, downloaded);
    }

    public Snapshot load() throws IOException {
        List<ChatMessage> direct = new ArrayList<>();
        List<GroupMessage> groups = new ArrayList<>();
        List<StoredFile> files = new ArrayList<>();
        int offset = 0;
        while (true) {
            ProtocolMessage page = server.request(ProtocolMessage.builder(MessageType.HISTORY_GET)
                    .field("offset", Integer.toString(offset)).build(), REQUEST_TIMEOUT);
            if (page.type() != MessageType.HISTORY_PAGE) {
                throw new IOException("Invalid history response");
            }
            int count;
            try {
                count = Integer.parseInt(page.requiredField("count"));
            } catch (NumberFormatException invalid) {
                throw new IOException("Invalid history page size", invalid);
            }
            if (count < 0 || count > PAGE_SIZE) {
                throw new IOException("Invalid history page size: " + count);
            }
            for (int index = 0; index < count; index++) {
                decode(page, "item." + index + ".", direct, groups, files);
            }
            offset += count;
            if (count < PAGE_SIZE) {
                return new Snapshot(List.copyOf(direct), List.copyOf(groups), List.copyOf(files));
            }
        }
    }

    private void putFile(FileInfo file, TransferDirection direction, TransferState state,
                         long transferred, Path saved, String reason) throws IOException {
        Instant created = fileCreatedAt.computeIfAbsent(file.fileId(), ignored -> Instant.now());
        put(ProtocolMessage.builder(MessageType.HISTORY_PUT)
                .field("kind", "FILE")
                .field("id", file.fileId().toString())
                .field("sender", file.sender())
                .field("receiver", file.receiver())
                .field("conversation", file.groupName() == null ? "" : file.groupName())
                .field("time", Long.toString(created.toEpochMilli()))
                .field("fileName", file.fileName())
                .field("fileSize", Long.toString(file.size()))
                .field("direction", direction.name())
                .field("state", state.name())
                .field("transferred", Long.toString(transferred))
                .field("savedName", saved == null ? "" : saved.getFileName().toString())
                .field("reason", reason == null ? "" : reason));
    }

    private void put(ProtocolMessage.Builder request) throws IOException {
        ProtocolMessage response = server.request(request.build(), REQUEST_TIMEOUT);
        if (response.type() != MessageType.HISTORY_ACK) {
            throw new IOException("Invalid history acknowledgement");
        }
    }

    private void decode(ProtocolMessage page, String prefix, List<ChatMessage> direct,
                        List<GroupMessage> groups, List<StoredFile> files) throws IOException {
        try {
            String kind = page.requiredField(prefix + "kind");
            UUID id = UUID.fromString(page.requiredField(prefix + "id"));
            String sender = page.requiredField(prefix + "sender");
            Instant timestamp = Instant.ofEpochMilli(
                    Long.parseLong(page.requiredField(prefix + "time")));
            switch (kind) {
                case "DIRECT" -> direct.add(new ChatMessage(id, sender,
                        page.requiredField(prefix + "receiver"),
                        page.requiredField(prefix + "content"), timestamp));
                case "GROUP" -> groups.add(new GroupMessage(id,
                        page.requiredField(prefix + "conversation"), sender,
                        page.requiredField(prefix + "content"), timestamp));
                case "FILE" -> files.add(decodeFile(page, prefix, id, sender, timestamp));
                default -> throw new IOException("Unknown history kind: " + kind);
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid history item", invalid);
        }
    }

    private StoredFile decodeFile(ProtocolMessage page, String prefix, UUID id,
                                  String sender, Instant timestamp) throws IOException {
        String group = page.field(prefix + "conversation");
        FileInfo file = new FileInfo(id, page.requiredField(prefix + "fileName"),
                Long.parseLong(page.requiredField(prefix + "fileSize")), sender,
                page.requiredField(prefix + "receiver"),
                group == null || group.isBlank() ? null : group);
        TransferState state = TransferState.valueOf(page.requiredField(prefix + "state"));
        String reason = page.field(prefix + "reason");
        if (state == TransferState.WAITING || state == TransferState.TRANSFERRING) {
            state = TransferState.FAILED;
            reason = "Truyền file bị gián đoạn";
        }
        String savedName = page.field(prefix + "savedName");
        Path saved = null;
        if (savedName != null && !savedName.isBlank()) {
            String safeName = Path.of(savedName).getFileName().toString();
            Path candidate = Path.of(System.getProperty("java.io.tmpdir"),
                    "chatp2p", "received-files", safeName);
            if (Files.isRegularFile(candidate)) {
                saved = candidate;
            }
        }
        LocalPaths paths = localPaths.get(id);
        return new StoredFile(file,
                TransferDirection.valueOf(page.requiredField(prefix + "direction")), state,
                Long.parseLong(page.requiredField(prefix + "transferred")), timestamp,
                paths == null ? null : paths.source(),
                paths != null && paths.saved() != null ? paths.saved() : saved,
                paths == null ? null : paths.downloaded(),
                reason == null || reason.isBlank() ? null : reason);
    }

    private TransferDirection direction(FileInfo file) {
        return owner.equals(file.sender()) ? TransferDirection.SENDING : TransferDirection.RECEIVING;
    }

    private void updatePaths(UUID id, Path source, Path saved, Path downloaded) {
        localPaths.compute(id, (ignored, current) -> new LocalPaths(
                source != null ? source : current == null ? null : current.source(),
                saved != null ? saved : current == null ? null : current.saved(),
                downloaded != null ? downloaded : current == null ? null : current.downloaded()));
    }
}
