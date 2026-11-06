package chatp2p.server;

import chatp2p.model.GroupMessage;
import chatp2p.protocol.MessageType;
import chatp2p.protocol.Protocol;
import chatp2p.protocol.ProtocolException;
import chatp2p.protocol.ProtocolMessage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Handles one client socket independently from all other clients. */
final class ClientHandler implements Runnable {
    private final long connectionId;
    private final Socket socket;
    private final Logger logger;
    private final ServerEventListener eventListener;
    private final UserManager userManager;
    private final AccountStore accountStore;
    private final GroupManager groupManager;
    private final PostgresDatabase database;
    private final String remoteAddress;
    private String clientName;
    private boolean authenticated;
    private volatile DataOutputStream activeOutput;

    ClientHandler(
            long connectionId,
            Socket socket,
            Logger logger,
            ServerEventListener eventListener,
            UserManager userManager,
            AccountStore accountStore,
            GroupManager groupManager,
            PostgresDatabase database) {
        this.connectionId = connectionId;
        this.socket = socket;
        this.logger = logger;
        this.eventListener = eventListener;
        this.userManager = userManager;
        this.accountStore = accountStore;
        this.groupManager = groupManager;
        this.database = database;
        this.remoteAddress = socket.getRemoteSocketAddress().toString();
    }

    @Override
    public void run() {
        Socket clientSocket = socket;
        try (clientSocket;
             DataInputStream input = new DataInputStream(
                     new BufferedInputStream(clientSocket.getInputStream()));
             DataOutputStream output = new DataOutputStream(
                     new BufferedOutputStream(clientSocket.getOutputStream()))) {
            activeOutput = output;
            while (!clientSocket.isClosed()) {
                ProtocolMessage message = Protocol.readMessage(input);
                if (!process(message, output)) {
                    break;
                }
            }
        } catch (EOFException eof) {
            logger.info(label() + " disconnected");
        } catch (SocketTimeoutException timeout) {
            logger.warning(label() + " timed out");
            publish(ServerEvent.Type.ERROR, "Connection timed out");
        } catch (ProtocolException invalidFrame) {
            logger.warning(label() + " protocol error: " + invalidFrame.getMessage());
            publish(ServerEvent.Type.ERROR, "Protocol error: " + invalidFrame.getMessage());
        } catch (IOException failure) {
            logger.log(Level.WARNING, label() + " network error: " + failure.getMessage());
            publish(ServerEvent.Type.ERROR, "Network error: " + failure.getMessage());
        } catch (RuntimeException failure) {
            logger.log(Level.SEVERE, label() + " unexpected error", failure);
            publish(ServerEvent.Type.ERROR, "Unexpected error: " + failure.getMessage());
        } finally {
            groupManager.removeUser(clientName);
            userManager.unregister(clientName, this);
            activeOutput = null;
            if (authenticated) {
                userManager.broadcastUserList();
                groupManager.broadcastLists();
            }
            logger.info(label() + " handler stopped");
            publish(ServerEvent.Type.CLIENT_DISCONNECTED, "Client disconnected");
        }
    }

    private boolean process(ProtocolMessage message, DataOutputStream output) throws IOException {
        if (!authenticated) {
            return switch (message.type()) {
                case REGISTER -> handleRegister(message, output);
                case LOGIN -> handleLogin(message, output);
                default -> {
                    sendError(output, message, "AUTH_REQUIRED", "Đăng nhập trước khi sử dụng");
                    yield true;
                }
            };
        }

        return switch (message.type()) {
            case PING -> {
                ProtocolMessage response = ProtocolMessage.responseTo(message, MessageType.PONG)
                        .field("sentAt", valueOrEmpty(message.field("sentAt")))
                        .field("serverTime", Instant.now().toString())
                        .build();
                send(response);
                yield true;
            }
            case DISCONNECT -> {
                logger.info(label() + " requested disconnect");
                yield false;
            }
            case GET_USERS -> {
                sendUserList(message);
                yield true;
            }
            case CONNECT_REQUEST -> {
                sendPeerInfo(message);
                yield true;
            }
            case GET_GROUPS -> {
                send(groupManager.buildList(message, clientName));
                yield true;
            }
            case GET_GROUP_MEMBERS -> {
                try {
                    send(groupManager.onlineMembers(message, clientName));
                } catch (IOException invalid) {
                    sendError(output, message, "INVALID_GROUP", invalid.getMessage());
                }
                yield true;
            }
            case JOIN_GROUP -> {
                handleJoinGroup(message);
                yield true;
            }
            case CREATE_GROUP -> {
                handleCreateGroup(message);
                yield true;
            }
            case LEAVE_GROUP -> {
                handleLeaveGroup(message);
                yield true;
            }
            case GROUP_CHAT -> {
                handleGroupChat(message, output);
                yield true;
            }
            case HISTORY_PUT -> {
                try {
                    database.putHistory(clientName, message);
                    send(ProtocolMessage.responseTo(message, MessageType.HISTORY_ACK).build());
                } catch (IOException | IllegalArgumentException invalid) {
                    sendError(output, message, "HISTORY_ERROR", invalid.getMessage());
                }
                yield true;
            }
            case HISTORY_GET -> {
                try {
                    send(database.historyPage(clientName, message));
                } catch (IOException invalid) {
                    sendError(output, message, "HISTORY_ERROR", invalid.getMessage());
                }
                yield true;
            }
            case HELLO, HELLO_ACK, REGISTER, REGISTER_ACK, LOGIN, LOGIN_ACK,
                    PONG, ERROR, USER_LIST, PEER_INFO, CHAT, GROUP_LIST,
                    FILE_REQUEST, FILE_ACCEPT, FILE_REJECT, FILE_END, FILE_CANCEL,
                    HISTORY_ACK, HISTORY_PAGE, CREATE_GROUP_ACK, GROUP_MEMBERS,
                    CALL_INVITE, CALL_ACCEPT, CALL_REJECT -> {
                sendError(output, message, "UNEXPECTED_MESSAGE",
                        "Server cannot accept " + message.type() + " from a client");
                yield true;
            }
        };
    }

    private boolean handleRegister(ProtocolMessage message, DataOutputStream output) throws IOException {
        String username = message.requiredField("username").trim();
        char[] password = message.requiredField("password").toCharArray();
        try {
            AccountStore.RegistrationResult result = accountStore.register(username, password);
            if (result == AccountStore.RegistrationResult.USERNAME_EXISTS) {
                sendError(output, message, "USERNAME_EXISTS", "Tên đăng nhập đã tồn tại");
            } else {
                send(ProtocolMessage.responseTo(message, MessageType.REGISTER_ACK)
                        .field("message", "Đăng ký thành công")
                        .build());
                logger.info("Account registered: " + username);
            }
        } catch (IllegalArgumentException invalidInput) {
            sendError(output, message, "INVALID_ACCOUNT", invalidInput.getMessage());
        } finally {
            Arrays.fill(password, '\0');
        }
        return true;
    }

    private boolean handleLogin(ProtocolMessage message, DataOutputStream output) throws IOException {
        String requestedName = message.requiredField("username").trim();
        char[] password = message.requiredField("password").toCharArray();
        boolean valid;
        try {
            AccountStore.validateUsername(requestedName);
            valid = accountStore.authenticate(requestedName, password);
        } catch (IllegalArgumentException invalidInput) {
            sendError(output, message, "INVALID_ACCOUNT", invalidInput.getMessage());
            return true;
        } finally {
            Arrays.fill(password, '\0');
        }
        if (!valid) {
            sendError(output, message, "LOGIN_FAILED", "Sai tên đăng nhập hoặc mật khẩu");
            return true;
        }

        int peerPort;
        try {
            peerPort = Integer.parseInt(message.requiredField("peerPort"));
        } catch (NumberFormatException invalidPort) {
            sendError(output, message, "INVALID_PEER_PORT", "Peer port must be a number");
            return false;
        }
        if (peerPort < 1 || peerPort > 65_535) {
            sendError(output, message, "INVALID_PEER_PORT", "Peer port must be between 1 and 65535");
            return false;
        }

        String peerHost = socket.getInetAddress().getHostAddress();
        if (!userManager.register(requestedName, peerHost, peerPort, this)) {
            sendError(output, message, "USERNAME_ONLINE", "Tài khoản đang đăng nhập ở thiết bị khác");
            return true;
        }

        clientName = requestedName;
        authenticated = true;
        ProtocolMessage response = ProtocolMessage.responseTo(message, MessageType.LOGIN_ACK)
                .field("message", "Đăng nhập thành công")
                .field("remoteAddress", socket.getRemoteSocketAddress().toString())
                .field("createGroupSupported", "true")
                .build();
        send(response);
        logger.info(label() + " logged in");
        publish(ServerEvent.Type.HANDSHAKE_COMPLETED, "Login completed");
        userManager.broadcastUserList();
        groupManager.broadcastLists();
        return true;
    }

    private void handleJoinGroup(ProtocolMessage request) throws IOException {
        String groupName = request.requiredField("groupName").trim();
        try {
            groupManager.join(clientName, groupName);
        } catch (IllegalArgumentException | IOException invalidName) {
            sendError(output(), request, "INVALID_GROUP", invalidName.getMessage());
            return;
        }
        send(ProtocolMessage.responseTo(request, MessageType.JOIN_GROUP)
                .field("status", "JOINED")
                .field("groupName", groupName)
                .build());
        logger.info(clientName + " joined group " + groupName);
        groupManager.broadcastLists();
    }

    private void handleCreateGroup(ProtocolMessage request) throws IOException {
        try {
            String name = request.requiredField("groupName").trim();
            int count = Integer.parseInt(request.requiredField("inviteCount"));
            if (count < 0 || count > 50) {
                throw new IllegalArgumentException("Chỉ được mời tối đa 50 tài khoản");
            }
            List<String> invitees = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                String username = request.requiredField("invite." + index).trim();
                if (!username.equals(clientName)) {
                    invitees.add(username);
                }
            }
            if (!groupManager.create(clientName, name, invitees)) {
                sendError(output(), request, "GROUP_EXISTS", "Tên nhóm đã tồn tại");
                return;
            }
            send(ProtocolMessage.responseTo(request, MessageType.CREATE_GROUP_ACK)
                    .field("groupName", name).build());
            logger.info(clientName + " created group " + name + " with "
                    + invitees.size() + " invitations");
            groupManager.broadcastLists();
        } catch (IllegalArgumentException | IOException invalid) {
            sendError(output(), request, "INVALID_GROUP", invalid.getMessage());
        }
    }

    private void handleLeaveGroup(ProtocolMessage request) throws IOException {
        String groupName = request.requiredField("groupName").trim();
        boolean left;
        try {
            left = groupManager.leave(clientName, groupName);
        } catch (IOException failure) {
            sendError(output(), request, "GROUP_ERROR", failure.getMessage());
            return;
        }
        if (!left) {
            sendError(output(), request, "NOT_A_MEMBER", "Bạn chưa tham gia nhóm " + groupName);
            return;
        }
        send(ProtocolMessage.responseTo(request, MessageType.LEAVE_GROUP)
                .field("status", "LEFT")
                .field("groupName", groupName)
                .build());
        logger.info(clientName + " left group " + groupName);
        groupManager.broadcastLists();
    }

    private void handleGroupChat(ProtocolMessage request, DataOutputStream output) throws IOException {
        GroupMessage groupMessage;
        try {
            groupMessage = new GroupMessage(
                    UUID.fromString(request.requiredField("messageId")),
                    request.requiredField("groupName"),
                    request.requiredField("sender"),
                    request.requiredField("content"),
                    Instant.parse(request.requiredField("timestamp")));
        } catch (IOException | IllegalArgumentException invalidMessage) {
            sendError(output, request, "INVALID_GROUP_CHAT", "Tin nhắn nhóm không hợp lệ");
            return;
        }
        if (!clientName.equals(groupMessage.sender())) {
            sendError(output, request, "SENDER_MISMATCH", "Người gửi không khớp phiên đăng nhập");
            return;
        }
        if (!groupManager.isMember(clientName, groupMessage.groupName())) {
            sendError(output, request, "NOT_A_MEMBER", "Bạn chưa tham gia nhóm");
            return;
        }
        try {
            groupManager.deliver(groupMessage);
        } catch (IOException failure) {
            sendError(output, request, "GROUP_ERROR", failure.getMessage());
            return;
        }
        send(ProtocolMessage.responseTo(request, MessageType.GROUP_CHAT)
                .field("status", "DELIVERED")
                .build());
        logger.info(clientName + " -> group " + groupMessage.groupName() + " CHAT");
    }

    private void sendUserList(ProtocolMessage request) throws IOException {
        java.util.List<UserManager.OnlineSession> users = userManager.snapshot();
        ProtocolMessage.Builder builder = ProtocolMessage.responseTo(request, MessageType.USER_LIST)
                .field("count", Integer.toString(users.size()));
        for (int index = 0; index < users.size(); index++) {
            UserManager.OnlineSession user = users.get(index);
            builder.field("user." + index + ".name", user.username())
                    .field("user." + index + ".host", user.peerHost())
                    .field("user." + index + ".port", Integer.toString(user.peerPort()));
        }
        send(builder.build());
    }

    private void sendPeerInfo(ProtocolMessage request) throws IOException {
        String target = request.requiredField("username");
        UserManager.OnlineSession peer = userManager.find(target);
        if (peer == null) {
            sendError(output(), request, "USER_OFFLINE", "User is not online: " + target);
            return;
        }
        send(ProtocolMessage.responseTo(request, MessageType.PEER_INFO)
                .field("username", peer.username())
                .field("host", peer.peerHost())
                .field("port", Integer.toString(peer.peerPort()))
                .build());
    }

    private void sendError(
            DataOutputStream output, ProtocolMessage request, String code, String description)
            throws IOException {
        ProtocolMessage error = ProtocolMessage.responseTo(request, MessageType.ERROR)
                .field("code", code)
                .field("message", description)
                .build();
        synchronized (output) {
            Protocol.writeMessage(output, error);
        }
    }

    void send(ProtocolMessage message) throws IOException {
        DataOutputStream current = output();
        synchronized (current) {
            Protocol.writeMessage(current, message);
        }
    }

    boolean isAvailable() {
        return activeOutput != null && !socket.isClosed();
    }

    private DataOutputStream output() throws IOException {
        DataOutputStream current = activeOutput;
        if (current == null) {
            throw new IOException("Client output stream is not ready");
        }
        return current;
    }

    private String label() {
        return "Connection " + connectionId + (clientName == null ? "" : " (" + clientName + ")");
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private void publish(ServerEvent.Type type, String message) {
        try {
            eventListener.onEvent(ServerEvent.client(
                    type, connectionId, clientName, remoteAddress, message));
        } catch (RuntimeException listenerFailure) {
            logger.log(Level.WARNING, "Server event listener failed", listenerFailure);
        }
    }
}
