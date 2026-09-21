package chatp2p;

import chatp2p.client.ChatClient;
import chatp2p.client.ConnectionState;
import chatp2p.protocol.MessageType;
import chatp2p.protocol.Protocol;
import chatp2p.protocol.ProtocolMessage;
import chatp2p.server.ChatServer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Dependency-free integration smoke test; run with assertions enabled. */
public final class Phase1IntegrationTest {
    private Phase1IntegrationTest() {
    }

    public static void main(String[] args) throws Exception {
        ChatServer server = new ChatServer(0);
        ExecutorService serverThread = Executors.newSingleThreadExecutor();
        Future<?> serverResult = serverThread.submit(() -> {
            try {
                server.start();
            } catch (java.io.IOException failure) {
                throw new RuntimeException(failure);
            }
        });

        try {
            server.awaitStarted(Duration.ofSeconds(5));
            try (ChatClient first = new ChatClient(); ChatClient second = new ChatClient()) {
                first.connect("127.0.0.1", server.boundPort(), "phase1-a");
                second.connect("127.0.0.1", server.boundPort(), "phase1-b");
                check(first.state() == ConnectionState.CONNECTED, "first client not connected");
                check(second.state() == ConnectionState.CONNECTED, "second client not connected");
                check(!first.ping().isNegative(), "first ping duration is invalid");
                check(!second.ping().isNegative(), "second ping duration is invalid");

                abortClientConnection(server.boundPort());
                try (ChatClient afterAbort = new ChatClient()) {
                    afterAbort.connect("127.0.0.1", server.boundPort(), "after-abort");
                    check(afterAbort.state() == ConnectionState.CONNECTED,
                            "server stopped accepting clients after an abrupt disconnect");
                    check(!afterAbort.ping().isNegative(), "post-abort ping duration is invalid");
                }
            }
            System.out.println("PASS: handshake, concurrent clients, ping/pong, abrupt disconnect and shutdown");
        } finally {
            server.close();
            serverThread.shutdown();
            serverThread.awaitTermination(5, TimeUnit.SECONDS);
            serverResult.get(5, TimeUnit.SECONDS);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void abortClientConnection(int port) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        try {
            DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream input = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream()));
            ProtocolMessage hello = ProtocolMessage.builder(MessageType.HELLO)
                    .field("clientName", "abrupt-client")
                    .field("clientVersion", "1.0")
                    .build();
            Protocol.writeMessage(output, hello);
            ProtocolMessage response = Protocol.readMessage(input);
            check(response.type() == MessageType.HELLO_ACK, "abrupt client handshake failed");

            // SO_LINGER=0 sends a TCP reset, simulating a process/network failure.
            socket.setSoLinger(true, 0);
        } finally {
            socket.close();
        }
    }
}
