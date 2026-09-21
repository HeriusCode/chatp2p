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
    private final String remoteAddress;
    private String clientName;
    private boolean handshakeComplete;

    ClientHandler(
            long connectionId,
            Socket socket,
            Logger logger,
            ServerEventListener eventListener) {
        this.connectionId = connectionId;
        this.socket = socket;
        this.logger = logger;
        this.eventListener = eventListener;
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
                Protocol.writeMessage(output, response);
                yield true;
            }
            case DISCONNECT -> {
                logger.info(label() + " requested disconnect");
                yield false;
            }
            case HELLO_ACK, PONG, ERROR -> {
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

        clientName = requestedName;
        handshakeComplete = true;
        ProtocolMessage response = ProtocolMessage.responseTo(message, MessageType.HELLO_ACK)
                .field("message", "Connected to ChatP2P control server")
                .field("remoteAddress", socket.getRemoteSocketAddress().toString())
                .build();
        Protocol.writeMessage(output, response);
        logger.info(label() + " handshake completed");
        publish(ServerEvent.Type.HANDSHAKE_COMPLETED, "Handshake completed");
        return true;
    }

    private void sendError(
            DataOutputStream output, ProtocolMessage request, String code, String description)
            throws IOException {
        ProtocolMessage error = ProtocolMessage.responseTo(request, MessageType.ERROR)
                .field("code", code)
                .field("message", description)
                .build();
        Protocol.writeMessage(output, error);
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
