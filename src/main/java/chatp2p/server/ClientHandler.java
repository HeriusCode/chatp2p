package chatp2p.server;

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
import java.util.logging.Level;
import java.util.logging.Logger;

/** Handles one client socket independently from all other clients. */
final class ClientHandler implements Runnable {
    private static final int MAX_CLIENT_NAME_LENGTH = 64;

    private final long connectionId;
    private final Socket socket;
    private final Logger logger;
    private final ServerEventListener eventListener;
    private final UserManager userManager;
    private final String remoteAddress;
    private String clientName;
    private boolean handshakeComplete;
    private volatile DataOutputStream activeOutput;

    ClientHandler(
            long connectionId,
            Socket socket,
            Logger logger,
            ServerEventListener eventListener,
            UserManager userManager) {
        this.connectionId = connectionId;
        this.socket = socket;
        this.logger = logger;
        this.eventListener = eventListener;
        this.userManager = userManager;
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
            userManager.unregister(clientName, this);
            activeOutput = null;
            if (handshakeComplete) {
                userManager.broadcastUserList();
            }
            logger.info(label() + " handler stopped");
            publish(ServerEvent.Type.CLIENT_DISCONNECTED, "Client disconnected");
        }
    }

    private boolean process(ProtocolMessage message, DataOutputStream output) throws IOException {
        if (!handshakeComplete && message.type() != MessageType.HELLO) {
            sendError(output, message, "HANDSHAKE_REQUIRED", "HELLO must be the first message");
            return false;
        }

        return switch (message.type()) {
            case HELLO -> handleHello(message, output);
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
            case HELLO_ACK, PONG, ERROR, USER_LIST, PEER_INFO, CHAT,
                    FILE_REQUEST, FILE_ACCEPT, FILE_REJECT, FILE_END, FILE_CANCEL -> {
                sendError(output, message, "UNEXPECTED_MESSAGE",
                        "Server cannot accept " + message.type() + " from a client");
                yield true;
            }
        };
    }

    private boolean handleHello(ProtocolMessage message, DataOutputStream output) throws IOException {
        if (handshakeComplete) {
            sendError(output, message, "DUPLICATE_HELLO", "Handshake is already complete");
            return true;
        }

        String requestedName = message.requiredField("clientName").trim();
        if (requestedName.length() > MAX_CLIENT_NAME_LENGTH
                || !requestedName.matches("[\\p{L}\\p{N}_.-]+")) {
            sendError(output, message, "INVALID_CLIENT_NAME",
                    "Client name must be 1-64 letters, digits, dot, underscore or hyphen");
            return false;
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
            sendError(output, message, "USERNAME_ONLINE", "This username is already online");
            return false;
        }

        clientName = requestedName;
        handshakeComplete = true;
        ProtocolMessage response = ProtocolMessage.responseTo(message, MessageType.HELLO_ACK)
                .field("message", "Connected to ChatP2P control server")
                .field("remoteAddress", socket.getRemoteSocketAddress().toString())
                .build();
        send(response);
        logger.info(label() + " handshake completed");
        publish(ServerEvent.Type.HANDSHAKE_COMPLETED, "Handshake completed");
        userManager.broadcastUserList();
        return true;
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
