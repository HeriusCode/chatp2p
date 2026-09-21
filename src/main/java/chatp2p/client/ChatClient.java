package chatp2p.client;

import chatp2p.model.ChatMessage;
import chatp2p.model.FileInfo;
import chatp2p.model.PeerInfo;
import chatp2p.model.TransferProgress;
import chatp2p.protocol.MessageType;
import chatp2p.protocol.ProtocolMessage;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Client facade for the central control channel and direct P2P chat channel. */
public final class ChatClient implements AutoCloseable {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final AtomicReference<ConnectionState> state =
            new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final CopyOnWriteArrayList<ClientEventListener> listeners =
            new CopyOnWriteArrayList<>();
    private volatile ServerConnection connection;
    private volatile PeerServer peerServer;
    private volatile FileTransferManager fileTransferManager;
    private volatile List<PeerInfo> onlineUsers = List.of();
    private volatile String clientName;
    private volatile int peerPort;

    public void connect(String host, int port, String requestedClientName) throws IOException {
        connect(host, port, requestedClientName, 0);
    }

    public void connect(
            String host, int port, String requestedClientName, int requestedPeerPort)
            throws IOException {
        validateConnectionArguments(host, port, requestedClientName, requestedPeerPort);
        if (!state.compareAndSet(ConnectionState.DISCONNECTED, ConnectionState.CONNECTING)) {
            throw new IllegalStateException("Client is already connected or connecting");
        }

        FileTransferManager newFileManager = new FileTransferManager(
                requestedClientName, createFileCallbacks());
        PeerServer newPeerServer = new PeerServer(
                requestedClientName,
                this::notifyChatReceived,
                newFileManager,
                this::notifyConnectionError);
        ServerConnection newConnection = new ServerConnection();
        peerServer = newPeerServer;
        fileTransferManager = newFileManager;
        connection = newConnection;
        try {
            peerPort = newPeerServer.start(requestedPeerPort);
            newConnection.setEventListener(this::handleServerEvent);
            newConnection.connect(host, port);
            ProtocolMessage hello = ProtocolMessage.builder(MessageType.HELLO)
                    .field("clientName", requestedClientName)
                    .field("clientVersion", "1.0")
                    .field("peerPort", Integer.toString(peerPort))
                    .build();
            ProtocolMessage response = newConnection.request(hello, REQUEST_TIMEOUT);
            requireType(response, MessageType.HELLO_ACK);
            clientName = requestedClientName;
            state.set(ConnectionState.CONNECTED);
            refreshUsers();
        } catch (IOException | RuntimeException failure) {
            newConnection.close();
            newPeerServer.close();
            newFileManager.close();
            connection = null;
            peerServer = null;
            fileTransferManager = null;
            state.set(ConnectionState.DISCONNECTED);
            throw failure;
        }
    }

    public Duration ping() throws IOException {
        ensureConnected();
        Instant started = Instant.now();
        ProtocolMessage ping = ProtocolMessage.builder(MessageType.PING)
                .field("sentAt", started.toString())
                .build();
        ProtocolMessage response = connection.request(ping, REQUEST_TIMEOUT);
        requireType(response, MessageType.PONG);
        return Duration.between(started, Instant.now());
    }

    public ChatMessage sendChat(String receiver, String content) throws IOException {
        ensureConnected();
        String normalizedMessage = content == null ? "" : content.trim();
        ChatMessage message = new ChatMessage(
                UUID.randomUUID(), clientName, receiver, normalizedMessage, Instant.now());
        PeerInfo peer = resolvePeer(receiver);
        PeerConnection.sendChat(peer, message);
        return message;
    }

    public TransferHandle sendFile(String receiver, Path file) throws IOException {
        ensureConnected();
        Objects.requireNonNull(file, "file");
        PeerInfo peer = resolvePeer(receiver);
        FileTransferManager manager = fileTransferManager;
        if (manager == null) {
            throw new IOException("File transfer manager is not running");
        }
        return manager.send(peer, file);
    }

    public void refreshUsers() throws IOException {
        ensureConnected();
        ProtocolMessage response = connection.request(
                ProtocolMessage.builder(MessageType.GET_USERS).build(), REQUEST_TIMEOUT);
        requireType(response, MessageType.USER_LIST);
        applyUserList(response);
    }

    public List<PeerInfo> onlineUsers() {
        return onlineUsers;
    }

    public void addEventListener(ClientEventListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
            listener.onUsersChanged(onlineUsers);
        }
    }

    public void removeEventListener(ClientEventListener listener) {
        listeners.remove(listener);
    }

    public ConnectionState state() {
        ServerConnection current = connection;
        if (state.get() == ConnectionState.CONNECTED && (current == null || !current.isOpen())) {
            state.compareAndSet(ConnectionState.CONNECTED, ConnectionState.DISCONNECTED);
        }
        return state.get();
    }

    public String clientName() {
        return clientName;
    }

    public int peerPort() {
        return peerPort;
    }

    @Override
    public void close() {
        ConnectionState previous = state.getAndSet(ConnectionState.CLOSING);
        ServerConnection current = connection;
        if (previous == ConnectionState.CONNECTED && current != null && current.isOpen()) {
            try {
                current.send(ProtocolMessage.builder(MessageType.DISCONNECT)
                        .field("reason", "Client requested shutdown")
                        .build());
            } catch (IOException ignored) {
                // The transport may already be gone; local resources still need closing.
            }
        }
        if (current != null) {
            current.close();
        }
        PeerServer currentPeerServer = peerServer;
        if (currentPeerServer != null) {
            currentPeerServer.close();
        }
        FileTransferManager currentFileManager = fileTransferManager;
        if (currentFileManager != null) {
            currentFileManager.close();
        }
        connection = null;
        peerServer = null;
        fileTransferManager = null;
        onlineUsers = List.of();
        state.set(ConnectionState.DISCONNECTED);
    }

    private void handleServerEvent(ProtocolMessage message) {
        if (message.type() == MessageType.USER_LIST) {
            try {
                applyUserList(message);
            } catch (IOException invalidList) {
                notifyConnectionError(invalidList.getMessage());
            }
        }
    }

    private void applyUserList(ProtocolMessage message) throws IOException {
        int count;
        try {
            count = Integer.parseInt(message.requiredField("count"));
        } catch (NumberFormatException invalidCount) {
            throw new IOException("Invalid USER_LIST count", invalidCount);
        }
        if (count < 0 || count > 64) {
            throw new IOException("Invalid USER_LIST size: " + count);
        }

        List<PeerInfo> users = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String prefix = "user." + index;
            String username = message.requiredField(prefix + ".name");
            String host = message.requiredField(prefix + ".host");
            int port;
            try {
                port = Integer.parseInt(message.requiredField(prefix + ".port"));
            } catch (NumberFormatException invalidPort) {
                throw new IOException("Invalid peer port for " + username, invalidPort);
            }
            if (!username.equals(clientName)) {
                users.add(new PeerInfo(username, host, port));
            }
        }
        onlineUsers = List.copyOf(users);
        for (ClientEventListener listener : listeners) {
            listener.onUsersChanged(onlineUsers);
        }
    }

    private PeerInfo resolvePeer(String username) throws IOException {
        Objects.requireNonNull(username, "username");
        for (PeerInfo peer : onlineUsers) {
            if (peer.username().equals(username)) {
                return peer;
            }
        }
        ProtocolMessage response = connection.request(
                ProtocolMessage.builder(MessageType.CONNECT_REQUEST)
                        .field("username", username)
                        .build(),
                REQUEST_TIMEOUT);
        requireType(response, MessageType.PEER_INFO);
        try {
            return new PeerInfo(
                    response.requiredField("username"),
                    response.requiredField("host"),
                    Integer.parseInt(response.requiredField("port")));
        } catch (NumberFormatException invalidPort) {
            throw new IOException("Server returned an invalid peer port", invalidPort);
        }
    }

    private void notifyChatReceived(ChatMessage message) {
        if (message == null) {
            return;
        }
        for (ClientEventListener listener : listeners) {
            listener.onChatReceived(message);
        }
    }

    private void notifyConnectionError(String message) {
        for (ClientEventListener listener : listeners) {
            listener.onConnectionError(message);
        }
    }

    private FileTransferManager.Callbacks createFileCallbacks() {
        return new FileTransferManager.Callbacks() {
            @Override
            public boolean offer(FileInfo file) {
                for (ClientEventListener listener : listeners) {
                    if (listener.onFileOffered(file)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void progress(TransferProgress progress) {
                for (ClientEventListener listener : listeners) {
                    listener.onTransferProgress(progress);
                }
            }

            @Override
            public void completed(FileInfo file, Path savedPath) {
                for (ClientEventListener listener : listeners) {
                    listener.onTransferCompleted(file, savedPath);
                }
            }

            @Override
            public void failed(FileInfo file, String reason) {
                for (ClientEventListener listener : listeners) {
                    listener.onTransferFailed(file, reason);
                }
            }
        };
    }

    private void ensureConnected() throws IOException {
        if (state() != ConnectionState.CONNECTED) {
            throw new IOException("Client is not connected");
        }
    }

    private static void requireType(ProtocolMessage message, MessageType expected) throws IOException {
        if (message.type() != expected) {
            throw new IOException("Expected " + expected + " but received " + message.type());
        }
    }

    private static void validateConnectionArguments(
            String host, int port, String clientName, int requestedPeerPort) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(clientName, "clientName");
        if (host.isBlank()) {
            throw new IllegalArgumentException("Host cannot be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        if (requestedPeerPort < 0 || requestedPeerPort > 65_535) {
            throw new IllegalArgumentException("Peer port must be 0 or between 1 and 65535");
        }
    }
}
