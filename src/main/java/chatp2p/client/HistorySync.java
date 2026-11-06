package chatp2p.client;

import chatp2p.model.ChatMessage;
import chatp2p.model.FileInfo;
import chatp2p.model.GroupMessage;
import chatp2p.model.TransferDirection;
import chatp2p.model.TransferState;
import chatp2p.protocol.MessageType;
import chatp2p.protocol.ProtocolMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Mirrors the existing SQLite cache to the server PostgreSQL archive. */
final class HistorySync {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final ChatHistoryStore cache;
    private final ServerConnection server;
    private final Map<String, String> uploaded = new HashMap<>();

    HistorySync(ChatHistoryStore cache, ServerConnection server) {
        this.cache = cache;
        this.server = server;
    }

    synchronized void uploadLocal() throws IOException {
        ChatHistoryStore.Snapshot snapshot = cache.load();
        for (ChatMessage item : snapshot.directMessages()) {
            ProtocolMessage.Builder request = ProtocolMessage.builder(MessageType.HISTORY_PUT)
                    .field("kind", "DIRECT")
                    .field("id", item.messageId().toString())
                    .field("sender", item.sender())
                    .field("receiver", item.receiver())
                    .field("content", item.content())
                    .field("time", Long.toString(item.timestamp().toEpochMilli()));
            put("D:" + item.messageId(), "1", request);
        }
        for (GroupMessage item : snapshot.groupMessages()) {
            ProtocolMessage.Builder request = ProtocolMessage.builder(MessageType.HISTORY_PUT)
                    .field("kind", "GROUP")
                    .field("id", item.messageId().toString())
                    .field("sender", item.sender())
                    .field("conversation", item.groupName())
                    .field("content", item.content())
                    .field("time", Long.toString(item.timestamp().toEpochMilli()));
            put("G:" + item.messageId(), "1", request);
        }
        for (ChatHistoryStore.StoredFile item : snapshot.files()) {
            FileInfo file = item.file();
            String savedName = item.savedPath() == null ? "" : item.savedPath().getFileName().toString();
            String fingerprint = item.state() + ":" + item.transferredBytes() + ":" + savedName
                    + ":" + item.failureReason();
            ProtocolMessage.Builder request = ProtocolMessage.builder(MessageType.HISTORY_PUT)
                    .field("kind", "FILE")
                    .field("id", file.fileId().toString())
                    .field("sender", file.sender())
                    .field("receiver", file.receiver())
                    .field("conversation", file.groupName() == null ? "" : file.groupName())
                    .field("time", Long.toString(item.createdAt().toEpochMilli()))
                    .field("fileName", file.fileName())
                    .field("fileSize", Long.toString(file.size()))
                    .field("direction", item.direction().name())
                    .field("state", item.state().name())
                    .field("transferred", Long.toString(item.transferredBytes()))
                    .field("savedName", savedName)
                    .field("reason", item.failureReason() == null ? "" : item.failureReason());
            put("F:" + file.fileId(), fingerprint, request);
        }
    }

    synchronized void downloadRemote() throws IOException {
        int offset = 0;
        while (true) {
            ProtocolMessage page = server.request(ProtocolMessage.builder(MessageType.HISTORY_GET)
                    .field("offset", Integer.toString(offset)).build(), TIMEOUT);
            if (page.type() != MessageType.HISTORY_PAGE) {
                throw new IOException("Invalid history response");
            }
            int count;
            try {
                count = Integer.parseInt(page.requiredField("count"));
            } catch (NumberFormatException invalid) {
                throw new IOException("Invalid history page", invalid);
            }
            if (count < 0 || count > 3) {
                throw new IOException("Invalid history page size");
            }
            for (int index = 0; index < count; index++) {
                importItem(page, "item." + index + ".");
            }
            offset += count;
            if (count < 3) {
                return;
            }
        }
    }

    private void put(String key, String fingerprint, ProtocolMessage.Builder request)
            throws IOException {
        if (fingerprint.equals(uploaded.get(key))) {
            return;
        }
        ProtocolMessage response = server.request(request.build(), TIMEOUT);
        if (response.type() != MessageType.HISTORY_ACK) {
            throw new IOException("Invalid history acknowledgement");
        }
        uploaded.put(key, fingerprint);
    }

    private void importItem(ProtocolMessage page, String prefix) throws IOException {
        try {
            String kind = page.requiredField(prefix + "kind");
            UUID id = UUID.fromString(page.requiredField(prefix + "id"));
            String sender = page.requiredField(prefix + "sender");
            Instant time = Instant.ofEpochMilli(Long.parseLong(page.requiredField(prefix + "time")));
            switch (kind) {
                case "DIRECT" -> cache.save(new ChatMessage(id, sender,
                        page.requiredField(prefix + "receiver"),
                        page.field(prefix + "content"), time));
                case "GROUP" -> cache.save(new GroupMessage(id,
                        page.requiredField(prefix + "conversation"), sender,
                        page.field(prefix + "content"), time));
                case "FILE" -> {
                    FileInfo file = new FileInfo(id, page.requiredField(prefix + "fileName"),
                            Long.parseLong(page.requiredField(prefix + "fileSize")), sender,
                            page.requiredField(prefix + "receiver"),
                            page.field(prefix + "conversation") == null
                                    || page.field(prefix + "conversation").isBlank()
                                    ? null : page.field(prefix + "conversation"));
                    String savedName = page.field(prefix + "savedName");
                    Path saved = null;
                    if (savedName != null && !savedName.isBlank()) {
                        String baseName = Path.of(savedName).getFileName().toString();
                        if (!baseName.equals(".") && !baseName.equals("..")) {
                            Path candidate = Path.of("data", "received_files", baseName);
                            if (Files.isRegularFile(candidate)) {
                                saved = candidate;
                            }
                        }
                    }
                    String reason = page.field(prefix + "reason");
                    cache.save(new ChatHistoryStore.StoredFile(file,
                            TransferDirection.valueOf(page.requiredField(prefix + "direction")),
                            TransferState.valueOf(page.requiredField(prefix + "state")),
                            Long.parseLong(page.requiredField(prefix + "transferred")),
                            time, null, saved, null,
                            reason == null || reason.isBlank() ? null : reason));
                }
                default -> throw new IOException("Unknown history kind: " + kind);
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid history item", invalid);
        }
    }
}
