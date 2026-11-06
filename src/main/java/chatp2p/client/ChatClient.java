package chatp2p.client;

import chatp2p.model.ChatMessage;
import chatp2p.model.FileInfo;
import chatp2p.model.GroupInfo;
import chatp2p.model.GroupMessage;
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
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Client facade for the central control channel and direct P2P chat channel. */
public final class ChatClient implements AutoCloseable {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final AtomicReference<ConnectionState> state =
            new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final CopyOnWriteArrayList<ClientEventListener> listeners =
            new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "server-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
    private final java.util.concurrent.ExecutorService historyExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "history-postgres-sync");
                thread.setDaemon(true);
                return thread;
            });
    private volatile ServerConnection connection;
    private volatile PeerServer peerServer;
    private volatile FileTransferManager fileTransferManager;
    private volatile MulticastGroupChannel multicastChannel;
    private volatile ChatHistoryStore historyStore;
    private volatile HistorySync historySync;
    private final ConcurrentHashMap<UUID, Boolean> seenGroupMessages = new ConcurrentHashMap<>();
    private volatile List<PeerInfo> onlineUsers = List.of();
    private volatile List<GroupInfo> groups = List.of();
    private volatile String clientName;
    private volatile boolean createGroupSupported;
    private volatile int peerPort;

    public void connect(String host, int port, String requestedClientName) throws IOException {
        throw new IllegalArgumentException("Password is required");
    }

    public void connect(
            String host, int port, String requestedClientName, String password)
            throws IOException {
        connect(host, port, requestedClientName, password, 0);
    }

    public void connect(
            String host, int port, String requestedClientName, String password,
            int requestedPeerPort)
            throws IOException {
        validateConnectionArguments(host, port, requestedClientName, requestedPeerPort);
        char[] passwordChars = password == null ? new char[0] : password.toCharArray();
        if (passwordChars.length < 6 || passwordChars.length > 128) {
            throw new IllegalArgumentException("Mật khẩu phải có 6-128 ký tự");
        }
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
            ProtocolMessage login = ProtocolMessage.builder(MessageType.LOGIN)
                    .field("username", requestedClientName)
                    .field("password", password)
                    .field("peerPort", Integer.toString(peerPort))
                    .build();
            ProtocolMessage response = newConnection.request(login, REQUEST_TIMEOUT);
            requireType(response, MessageType.LOGIN_ACK);
            createGroupSupported = Boolean.parseBoolean(response.field("createGroupSupported"));
            historyStore = new ChatHistoryStore(requestedClientName, host, port);
            historySync = new HistorySync(historyStore, newConnection);
            clientName = requestedClientName;
            state.set(ConnectionState.CONNECTED);
            try {
                MulticastGroupChannel channel =
                        new MulticastGroupChannel(message -> acceptGroupMessage(message, true));
                channel.start();
                multicastChannel = channel;
            } catch (IOException multicastUnavailable) {
                // Central Server delivery remains the reliable fallback channel.
                multicastChannel = null;
            }
            refreshUsers();
            refreshGroups();
            heartbeatExecutor.scheduleAtFixedRate(
                    this::sendHeartbeat, 20, 30, TimeUnit.SECONDS);
        } catch (IOException | RuntimeException failure) {
            newConnection.close();
            newPeerServer.close();
            newFileManager.close();
            connection = null;
            peerServer = null;
            fileTransferManager = null;
            historyStore = null;
            historySync = null;
            createGroupSupported = false;
            state.set(ConnectionState.DISCONNECTED);
            throw failure;
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
    }

    public static void registerAccount(
            String host, int port, String username, String password) throws IOException {
        validateConnectionArguments(host, port, username, 0);
        if (password == null || password.length() < 6 || password.length() > 128) {
            throw new IllegalArgumentException("Mật khẩu phải có 6-128 ký tự");
        }
        try (ServerConnection registration = new ServerConnection()) {
            registration.connect(host, port);
            ProtocolMessage response = registration.request(
                    ProtocolMessage.builder(MessageType.REGISTER)
                            .field("username", username)
                            .field("password", password)
                            .build(),
                    REQUEST_TIMEOUT);
            requireType(response, MessageType.REGISTER_ACK);
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
        saveHistory(store -> store.save(message));
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
        TransferHandle transfer = manager.send(peer, file);
        saveHistory(store -> store.saveFileSource(transfer.fileInfo(), file));
        return transfer;
    }

    public void refreshUsers() throws IOException {
        ensureConnected();
        ProtocolMessage response = connection.request(
                ProtocolMessage.builder(MessageType.GET_USERS).build(), REQUEST_TIMEOUT);
        requireType(response, MessageType.USER_LIST);
        applyUserList(response);
    }

    public void refreshGroups() throws IOException {
        ensureConnected();
        ProtocolMessage response = connection.request(
                ProtocolMessage.builder(MessageType.GET_GROUPS).build(), REQUEST_TIMEOUT);
        requireType(response, MessageType.GROUP_LIST);
        applyGroupList(response);
    }

    public void joinGroup(String groupName) throws IOException {
        ensureConnected();
        ProtocolMessage response = connection.request(
                ProtocolMessage.builder(MessageType.JOIN_GROUP)
                        .field("groupName", groupName.trim())
                        .build(), REQUEST_TIMEOUT);
        requireType(response, MessageType.JOIN_GROUP);
        refreshGroups();
    }

    public void createGroup(String groupName, List<String> invitedUsers) throws IOException {
        ensureConnected();
        if (!createGroupSupported) {
            throw new IOException("Server đang chạy bản cũ. Hãy dừng và chạy lại ServerMain, "
                    + "sau đó đăng nhập lại trước khi tạo nhóm.");
        }
        if (groupName == null || groupName.isBlank() || invitedUsers == null) {
            throw new IllegalArgumentException("Tên nhóm và danh sách mời không hợp lệ");
        }
        java.util.LinkedHashSet<String> invitees = new java.util.LinkedHashSet<>();
        for (String username : invitedUsers) {
            if (username != null && !username.isBlank() && !username.trim().equals(clientName)) {
                invitees.add(username.trim());
            }
        }
        if (invitees.size() > 50) {
            throw new IllegalArgumentException("Chỉ được mời tối đa 50 tài khoản");
        }
        ProtocolMessage.Builder request = ProtocolMessage.builder(MessageType.CREATE_GROUP)
                .field("groupName", groupName.trim())
                .field("inviteCount", Integer.toString(invitees.size()));
        int index = 0;
        for (String username : invitees) {
            request.field("invite." + index++, username);
        }
        ProtocolMessage response = connection.request(request.build(), REQUEST_TIMEOUT);
        requireType(response, MessageType.CREATE_GROUP_ACK);
        refreshGroups();
    }

    public void leaveGroup(String groupName) throws IOException {
        ensureConnected();
        ProtocolMessage response = connection.request(
                ProtocolMessage.builder(MessageType.LEAVE_GROUP)
                        .field("groupName", groupName)
                        .build(), REQUEST_TIMEOUT);
        requireType(response, MessageType.LEAVE_GROUP);
        refreshGroups();
    }

    public GroupMessage sendGroupChat(String groupName, String content) throws IOException {
        ensureConnected();
        GroupMessage message = new GroupMessage(
                UUID.randomUUID(), groupName, clientName,
                content == null ? "" : content.trim(), Instant.now());
        ProtocolMessage response = connection.request(
                ProtocolMessage.builder(MessageType.GROUP_CHAT)
                        .field("messageId", message.messageId().toString())
                        .field("groupName", message.groupName())
                        .field("sender", message.sender())
                        .field("content", message.content())
                        .field("timestamp", message.timestamp().toString())
                        .build(), REQUEST_TIMEOUT);
        requireType(response, MessageType.GROUP_CHAT);
        saveHistory(store -> store.save(message));
        seenGroupMessages.put(message.messageId(), Boolean.TRUE);
        MulticastGroupChannel channel = multicastChannel;
        if (channel != null) {
            try {
                channel.send(message);
            } catch (IOException ignored) {
                // The server has already delivered the message as fallback.
            }
        }
        return message;
    }

    public List<PeerInfo> onlineUsers() {
        return onlineUsers;
    }

    public List<GroupInfo> groups() {
        return groups;
    }

    public boolean createGroupSupported() {
        return createGroupSupported;
    }

    public ChatHistoryStore.Snapshot loadHistory() throws IOException {
        ChatHistoryStore store = historyStore;
        HistorySync sync = historySync;
        if (sync != null) {
            sync.uploadLocal();
            sync.downloadRemote();
        }
        return store == null
                ? new ChatHistoryStore.Snapshot(List.of(), List.of(), List.of())
                : store.load();
    }

    public void rememberFailedFile(FileInfo file, Path source, String reason) {
        saveHistory(store -> store.saveFileAttempt(file, source, reason));
    }

    public void rememberDownloadedFile(UUID fileId, Path target) {
        saveHistory(store -> store.saveDownloadedPath(fileId, target));
    }

    public void addEventListener(ClientEventListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
            listener.onUsersChanged(onlineUsers);
            listener.onGroupsChanged(groups);
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
        MulticastGroupChannel currentMulticast = multicastChannel;
        if (currentMulticast != null) {
            currentMulticast.close();
        }
        connection = null;
        peerServer = null;
        fileTransferManager = null;
        multicastChannel = null;
        historyStore = null;
        historySync = null;
        createGroupSupported = false;
        seenGroupMessages.clear();
        onlineUsers = List.of();
        groups = List.of();
        state.set(ConnectionState.DISCONNECTED);
        heartbeatExecutor.shutdownNow();
        historyExecutor.shutdown();
    }

    private void handleServerEvent(ProtocolMessage message) {
        if (message.type() == MessageType.USER_LIST) {
            try {
                applyUserList(message);
            } catch (IOException invalidList) {
                notifyConnectionError(invalidList.getMessage());
            }
        } else if (message.type() == MessageType.GROUP_LIST) {
            try {
                applyGroupList(message);
            } catch (IOException invalidList) {
                notifyConnectionError(invalidList.getMessage());
            }
        } else if (message.type() == MessageType.GROUP_CHAT) {
            try {
                acceptGroupMessage(decodeGroupMessage(message), false);
            } catch (IOException invalidMessage) {
                notifyConnectionError(invalidMessage.getMessage());
            }
        }
    }

    private void acceptGroupMessage(GroupMessage message, boolean multicast) {
        if (message == null || message.sender().equals(clientName)) {
            return;
        }
        if (multicast && groups.stream().noneMatch(
                group -> group.joined() && group.name().equals(message.groupName()))) {
            return;
        }
        if (seenGroupMessages.putIfAbsent(message.messageId(), Boolean.TRUE) != null) {
            return;
        }
        if (seenGroupMessages.size() > 4_096) {
            seenGroupMessages.clear();
            seenGroupMessages.put(message.messageId(), Boolean.TRUE);
        }
        saveHistory(store -> store.save(message));
        for (ClientEventListener listener : listeners) {
            listener.onGroupChatReceived(message);
        }
    }

    public boolean multicastAvailable() {
        return multicastChannel != null;
    }

    private void applyGroupList(ProtocolMessage message) throws IOException {
        int count;
        try {
            count = Integer.parseInt(message.requiredField("count"));
        } catch (NumberFormatException invalidCount) {
            throw new IOException("Invalid GROUP_LIST count", invalidCount);
        }
        if (count < 0 || count > 256) {
            throw new IOException("Invalid GROUP_LIST size: " + count);
        }
        List<GroupInfo> updated = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String prefix = "group." + index;
            try {
                updated.add(new GroupInfo(
                        message.requiredField(prefix + ".name"),
                        Integer.parseInt(message.requiredField(prefix + ".members")),
                        Boolean.parseBoolean(message.requiredField(prefix + ".joined"))));
            } catch (NumberFormatException invalidMembers) {
                throw new IOException("Invalid group member count", invalidMembers);
            }
        }
        groups = List.copyOf(updated);
        for (ClientEventListener listener : listeners) {
            listener.onGroupsChanged(groups);
        }
    }

    private static GroupMessage decodeGroupMessage(ProtocolMessage message) throws IOException {
        try {
            return new GroupMessage(
                    UUID.fromString(message.requiredField("messageId")),
                    message.requiredField("groupName"),
                    message.requiredField("sender"),
                    message.requiredField("content"),
                    Instant.parse(message.requiredField("timestamp")));
        } catch (IllegalArgumentException invalidMessage) {
            throw new IOException("Invalid GROUP_CHAT message", invalidMessage);
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
        saveHistory(store -> store.save(message));
        for (ClientEventListener listener : listeners) {
            listener.onChatReceived(message);
        }
    }

    private void notifyConnectionError(String message) {
        for (ClientEventListener listener : listeners) {
            listener.onConnectionError(message);
        }
    }

    private void sendHeartbeat() {
        if (state.get() != ConnectionState.CONNECTED) {
            return;
        }
        try {
            ping();
        } catch (IOException failure) {
            notifyConnectionError("Mất kết nối tới Server: " + failure.getMessage());
            close();
        }
    }

    private FileTransferManager.Callbacks createFileCallbacks() {
        return new FileTransferManager.Callbacks() {
            @Override
            public boolean offer(FileInfo file) {
                saveHistory(store -> store.saveFileOffer(file));
                for (ClientEventListener listener : listeners) {
                    if (listener.onFileOffered(file)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void progress(TransferProgress progress) {
                saveHistory(store -> store.saveFileProgress(progress));
                for (ClientEventListener listener : listeners) {
                    listener.onTransferProgress(progress);
                }
            }

            @Override
            public void completed(FileInfo file, Path savedPath) {
                saveHistory(store -> store.saveFileCompleted(file, savedPath));
                for (ClientEventListener listener : listeners) {
                    listener.onTransferCompleted(file, savedPath);
                }
            }

            @Override
            public void failed(FileInfo file, String reason) {
                saveHistory(store -> store.saveFileFailed(file, reason));
                for (ClientEventListener listener : listeners) {
                    listener.onTransferFailed(file, reason);
                }
            }
        };
    }

    private void saveHistory(HistoryOperation operation) {
        ChatHistoryStore store = historyStore;
        if (store == null) {
            return;
        }
        try {
            operation.save(store);
            HistorySync sync = historySync;
            if (sync != null) {
                historyExecutor.execute(() -> {
                    try {
                        sync.uploadLocal();
                    } catch (IOException failure) {
                        notifyConnectionError("Không thể đồng bộ lịch sử: " + failure.getMessage());
                    }
                });
            }
        } catch (IOException failure) {
            notifyConnectionError(failure.getMessage());
        }
    }

    @FunctionalInterface
    private interface HistoryOperation {
        void save(ChatHistoryStore store) throws IOException;
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
