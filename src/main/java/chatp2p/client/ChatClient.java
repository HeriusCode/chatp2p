package chatp2p.client;

import chatp2p.protocol.MessageType;
import chatp2p.protocol.ProtocolMessage;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Phase 1 client facade for connect, handshake, ping and graceful disconnect. */
public final class ChatClient implements AutoCloseable {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final AtomicReference<ConnectionState> state =
            new AtomicReference<>(ConnectionState.DISCONNECTED);
    private volatile ServerConnection connection;
    private volatile String clientName;

    public void connect(String host, int port, String requestedClientName) throws IOException {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(requestedClientName, "requestedClientName");
        if (host.isBlank()) {
            throw new IllegalArgumentException("Host cannot be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        if (!state.compareAndSet(ConnectionState.DISCONNECTED, ConnectionState.CONNECTING)) {
            throw new IllegalStateException("Client is already connected or connecting");
        }

        ServerConnection newConnection = new ServerConnection();
        connection = newConnection;
        try {
            newConnection.connect(host, port);
            ProtocolMessage hello = ProtocolMessage.builder(MessageType.HELLO)
                    .field("clientName", requestedClientName)
                    .field("clientVersion", "1.0")
                    .build();
            ProtocolMessage response = newConnection.request(hello, REQUEST_TIMEOUT);
            requireType(response, MessageType.HELLO_ACK);
            clientName = requestedClientName;
            state.set(ConnectionState.CONNECTED);
        } catch (IOException | RuntimeException failure) {
            newConnection.close();
            connection = null;
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
                // The transport may already be gone; close still releases local resources.
            }
        }
        if (current != null) {
            current.close();
        }
        connection = null;
        state.set(ConnectionState.DISCONNECTED);
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
}

