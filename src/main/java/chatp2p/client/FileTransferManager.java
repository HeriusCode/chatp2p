package chatp2p.client;

import chatp2p.model.FileInfo;
import chatp2p.model.PeerInfo;
import chatp2p.model.TransferDirection;
import chatp2p.model.TransferProgress;
import chatp2p.model.TransferState;
import chatp2p.protocol.MessageType;
import chatp2p.protocol.Protocol;
import chatp2p.protocol.ProtocolMessage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sends and receives files over direct TCP byte streams. */
final class FileTransferManager implements AutoCloseable {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024 * 1024;
    private static final Path RECEIVE_DIRECTORY = Path.of("data", "received_files");

    interface Callbacks {
        boolean offer(FileInfo file);

        void progress(TransferProgress progress);

        void completed(FileInfo file, Path savedPath);

        void failed(FileInfo file, String reason);
    }

    private final String localUsername;
    private final Callbacks callbacks;
    private final ExecutorService transferPool = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "file-transfer");
        thread.setDaemon(true);
        return thread;
    });

    FileTransferManager(String localUsername, Callbacks callbacks) {
        this.localUsername = localUsername;
        this.callbacks = callbacks;
    }

    TransferHandle send(PeerInfo peer, Path source) throws IOException {
        Path file = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IOException("File does not exist or is not readable: " + file);
        }
        long size = Files.size(file);
        if (size > MAX_FILE_SIZE) {
            throw new IOException("File exceeds the 10 GiB safety limit");
        }
        FileInfo info = new FileInfo(
                UUID.randomUUID(), sanitizeFileName(file.getFileName().toString()), size,
                localUsername, peer.username());
        OutgoingTransfer transfer = new OutgoingTransfer(peer, file, info);
        transferPool.execute(transfer);
        return transfer;
    }

    void receive(
            ProtocolMessage request, DataInputStream input, DataOutputStream output)
            throws IOException {
        FileInfo info = decodeFileInfo(request);
        if (!localUsername.equals(info.receiver())) {
            Protocol.writeMessage(output, ProtocolMessage.responseTo(request, MessageType.FILE_REJECT)
                    .field("reason", "Wrong receiver")
                    .build());
            return;
        }
        if (info.size() > MAX_FILE_SIZE) {
            Protocol.writeMessage(output, ProtocolMessage.responseTo(request, MessageType.FILE_REJECT)
                    .field("reason", "File is too large")
                    .build());
            return;
        }
        if (!callbacks.offer(info)) {
            Protocol.writeMessage(output, ProtocolMessage.responseTo(request, MessageType.FILE_REJECT)
                    .field("reason", "Receiver rejected the file")
                    .build());
            callbacks.failed(info, "Đã từ chối file");
            return;
        }

        Files.createDirectories(RECEIVE_DIRECTORY);
        Path finalTarget = uniqueTarget(RECEIVE_DIRECTORY, info.fileName());
        Path temporary = RECEIVE_DIRECTORY.resolve("." + info.fileId() + ".part").normalize();
        Protocol.writeMessage(output, ProtocolMessage.responseTo(request, MessageType.FILE_ACCEPT)
                .field("status", "READY")
                .build());

        boolean completed = false;
        try {
            MessageDigest digest = sha256();
            Instant started = Instant.now();
            long received = 0;
            long lastUpdate = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            try (java.io.OutputStream fileOutput = new BufferedOutputStream(
                    Files.newOutputStream(temporary))) {
                while (received < info.size()) {
                    int wanted = (int) Math.min(buffer.length, info.size() - received);
                    int count = input.read(buffer, 0, wanted);
                    if (count < 0) {
                        throw new EOFException("Peer disconnected during file transfer");
                    }
                    fileOutput.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    received += count;
                    long now = System.nanoTime();
                    if (now - lastUpdate >= 100_000_000L || received == info.size()) {
                        callbacks.progress(progress(
                                info, TransferDirection.RECEIVING, TransferState.TRANSFERRING,
                                received, started, "Receiving"));
                        lastUpdate = now;
                    }
                }
            }

            ProtocolMessage end = Protocol.readMessage(input);
            if (end.type() != MessageType.FILE_END) {
                throw new IOException("Missing FILE_END frame");
            }
            String expectedChecksum = end.requiredField("sha256");
            String actualChecksum = HexFormat.of().formatHex(digest.digest());
            if (!MessageDigest.isEqual(
                    expectedChecksum.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    actualChecksum.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new IOException("SHA-256 checksum mismatch");
            }

            finalTarget = moveToUniqueTarget(temporary, finalTarget);
            completed = true;
            Protocol.writeMessage(output, ProtocolMessage.responseTo(end, MessageType.FILE_END)
                    .field("status", "SUCCESS")
                    .field("savedAs", finalTarget.getFileName().toString())
                    .build());
            callbacks.progress(new TransferProgress(
                    info, TransferDirection.RECEIVING, TransferState.SUCCESS,
                    info.size(), 0, 0, "Completed"));
            callbacks.completed(info, finalTarget);
        } catch (IOException failure) {
            callbacks.failed(info, failure.getMessage());
            throw failure;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    @Override
    public void close() {
        transferPool.shutdownNow();
    }

    private final class OutgoingTransfer implements TransferHandle, Runnable {
        private final PeerInfo peer;
        private final Path source;
        private final FileInfo info;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile Socket socket;

        private OutgoingTransfer(PeerInfo peer, Path source, FileInfo info) {
            this.peer = peer;
            this.source = source;
            this.info = info;
        }

        @Override
        public UUID fileId() {
            return info.fileId();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            Socket current = socket;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                    // Closing the socket is the cancellation signal to the receiver.
                }
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void run() {
            callbacks.progress(new TransferProgress(
                    info, TransferDirection.SENDING, TransferState.WAITING,
                    0, 0, 0, "Waiting for receiver"));
            try (Socket transferSocket = new Socket()) {
                socket = transferSocket;
                transferSocket.connect(new InetSocketAddress(peer.host(), peer.port()), 5_000);
                transferSocket.setSoTimeout(30_000);
                transferSocket.setTcpNoDelay(true);
                try (DataOutputStream output = new DataOutputStream(
                            new BufferedOutputStream(transferSocket.getOutputStream()));
                     DataInputStream input = new DataInputStream(
                            new BufferedInputStream(transferSocket.getInputStream()));
                     InputStream fileInput = new BufferedInputStream(Files.newInputStream(source))) {
                    ProtocolMessage request = encodeFileInfo(info);
                    Protocol.writeMessage(output, request);
                    ProtocolMessage decision = Protocol.readMessage(input);
                    if (decision.type() == MessageType.FILE_REJECT) {
                        throw new IOException(valueOrDefault(
                                decision.field("reason"), "Receiver rejected the file"));
                    }
                    if (decision.type() != MessageType.FILE_ACCEPT) {
                        throw new IOException("Invalid response to FILE_REQUEST");
                    }

                    MessageDigest digest = sha256();
                    Instant started = Instant.now();
                    long sent = 0;
                    long lastUpdate = 0;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int count;
                    while ((count = fileInput.read(buffer)) >= 0) {
                        if (cancelled.get()) {
                            throw new TransferCancelledException();
                        }
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        sent += count;
                        long now = System.nanoTime();
                        if (now - lastUpdate >= 100_000_000L || sent == info.size()) {
                            callbacks.progress(progress(
                                    info, TransferDirection.SENDING, TransferState.TRANSFERRING,
                                    sent, started, "Sending"));
                            lastUpdate = now;
                        }
                    }
                    output.flush();
                    ProtocolMessage end = ProtocolMessage.builder(MessageType.FILE_END)
                            .field("fileId", info.fileId().toString())
                            .field("sha256", HexFormat.of().formatHex(digest.digest()))
                            .build();
                    Protocol.writeMessage(output, end);
                    ProtocolMessage result = Protocol.readMessage(input);
                    if (result.type() != MessageType.FILE_END
                            || !"SUCCESS".equals(result.field("status"))) {
                        throw new IOException("Receiver could not verify the file");
                    }
                    callbacks.progress(new TransferProgress(
                            info, TransferDirection.SENDING, TransferState.SUCCESS,
                            info.size(), 0, 0, "Completed"));
                    callbacks.completed(info, null);
                }
            } catch (TransferCancelledException cancelledFailure) {
                callbacks.progress(new TransferProgress(
                        info, TransferDirection.SENDING, TransferState.CANCELLED,
                        0, 0, 0, "Cancelled"));
                callbacks.failed(info, "Đã hủy truyền file");
            } catch (Exception failure) {
                TransferState state = cancelled.get()
                        ? TransferState.CANCELLED : TransferState.FAILED;
                callbacks.progress(new TransferProgress(
                        info, TransferDirection.SENDING, state,
                        0, 0, 0, failure.getMessage()));
                callbacks.failed(info, valueOrDefault(failure.getMessage(), "File transfer failed"));
            } finally {
                socket = null;
            }
        }
    }

    private static ProtocolMessage encodeFileInfo(FileInfo info) {
        return ProtocolMessage.builder(MessageType.FILE_REQUEST)
                .field("fileId", info.fileId().toString())
                .field("fileName", info.fileName())
                .field("fileSize", Long.toString(info.size()))
                .field("sender", info.sender())
                .field("receiver", info.receiver())
                .build();
    }

    private static FileInfo decodeFileInfo(ProtocolMessage request) throws IOException {
        try {
            long size = Long.parseLong(request.requiredField("fileSize"));
            if (size < 0) {
                throw new IOException("Negative file size");
            }
            return new FileInfo(
                    UUID.fromString(request.requiredField("fileId")),
                    sanitizeFileName(request.requiredField("fileName")),
                    size,
                    request.requiredField("sender"),
                    request.requiredField("receiver"));
        } catch (IllegalArgumentException invalidMetadata) {
            throw new IOException("Invalid file metadata", invalidMetadata);
        }
    }

    private static TransferProgress progress(
            FileInfo info,
            TransferDirection direction,
            TransferState state,
            long bytes,
            Instant started,
            String detail) {
        double seconds = Math.max(0.001, Duration.between(started, Instant.now()).toMillis() / 1000.0);
        double speed = bytes / seconds;
        long remaining = speed <= 0 ? -1 : (long) Math.ceil((info.size() - bytes) / speed);
        return new TransferProgress(info, direction, state, bytes, speed, remaining, detail);
    }

    private static String sanitizeFileName(String suppliedName) throws IOException {
        String baseName;
        try {
            baseName = Path.of(suppliedName).getFileName().toString();
        } catch (RuntimeException invalidPath) {
            throw new IOException("Invalid file name", invalidPath);
        }
        String sanitized = baseName.replaceAll("[\\x00-\\x1F<>:\"/\\\\|?*]", "_").trim();
        if (sanitized.isBlank() || sanitized.equals(".") || sanitized.equals("..")) {
            throw new IOException("Invalid file name");
        }
        return sanitized.length() > 200 ? sanitized.substring(0, 200) : sanitized;
    }

    private static Path uniqueTarget(Path directory, String fileName) {
        Path initial = directory.resolve(fileName).normalize();
        if (!Files.exists(initial)) {
            return initial;
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        int index = 1;
        Path candidate;
        do {
            candidate = directory.resolve(base + " (" + index++ + ")" + extension).normalize();
        } while (Files.exists(candidate));
        return candidate;
    }

    private static Path moveToUniqueTarget(Path temporary, Path preferred) throws IOException {
        Path target = preferred;
        while (true) {
            try {
                return Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileAlreadyExistsException conflict) {
                target = uniqueTarget(target.getParent(), target.getFileName().toString());
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                return Files.move(temporary, target);
            }
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is not available", impossible);
        }
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class TransferCancelledException extends IOException {
    }
}
