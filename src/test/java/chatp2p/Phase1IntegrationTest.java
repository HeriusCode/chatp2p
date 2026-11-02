package chatp2p;

import chatp2p.client.ChatClient;
import chatp2p.client.ClientEventListener;
import chatp2p.client.ConnectionState;
import chatp2p.client.LanServerDiscovery;
import chatp2p.model.ChatMessage;
import chatp2p.model.FileInfo;
import chatp2p.protocol.MessageType;
import chatp2p.protocol.Protocol;
import chatp2p.protocol.ProtocolMessage;
import chatp2p.server.ChatServer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free integration smoke test; run with assertions enabled. */
public final class Phase1IntegrationTest {
    private static final String TEST_PASSWORD = "phase1-secret";

    private Phase1IntegrationTest() {
    }

    public static void main(String[] args) throws Exception {
        Path accountFile = Files.createTempFile("chatp2p-test-users-", ".dat");
        Files.deleteIfExists(accountFile);
        System.setProperty("chatp2p.usersFile", accountFile.toString());
        try (DatagramSocket availablePort = new DatagramSocket(0)) {
            System.setProperty(
                    "chatp2p.discoveryPort", Integer.toString(availablePort.getLocalPort()));
        }
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
            LanServerDiscovery.ServerEndpoint discovered =
                    LanServerDiscovery.discover(Duration.ofSeconds(3));
            check(discovered.port() == server.boundPort(),
                    "UDP Broadcast discovered the wrong server port");
            registerIfMissing(server.boundPort(), "phase1-a");
            registerIfMissing(server.boundPort(), "phase1-b");
            registerIfMissing(server.boundPort(), "abrupt-client");
            registerIfMissing(server.boundPort(), "after-abort");
            expectIOException(
                    () -> ChatClient.registerAccount(
                            "127.0.0.1", server.boundPort(), "phase1-a", TEST_PASSWORD),
                    "duplicate registration was accepted");
            try (ChatClient invalidLogin = new ChatClient()) {
                expectIOException(
                        () -> invalidLogin.connect(
                                "127.0.0.1", server.boundPort(), "phase1-a", "wrong-password"),
                        "wrong password was accepted");
            }
            try (ChatClient first = new ChatClient(); ChatClient second = new ChatClient()) {
                first.connect("127.0.0.1", server.boundPort(), "phase1-a", TEST_PASSWORD);
                second.connect("127.0.0.1", server.boundPort(), "phase1-b", TEST_PASSWORD);
                check(first.multicastAvailable() && second.multicastAvailable(),
                        "UDP Multicast channel was not available on both clients");
                check(first.state() == ConnectionState.CONNECTED, "first client not connected");
                check(second.state() == ConnectionState.CONNECTED, "second client not connected");
                check(!first.ping().isNegative(), "first ping duration is invalid");
                check(!second.ping().isNegative(), "second ping duration is invalid");

                waitUntil(() -> first.onlineUsers().stream()
                        .anyMatch(peer -> peer.username().equals("phase1-b")),
                        "first client did not discover second client");
                CountDownLatch chatReceived = new CountDownLatch(1);
                second.addEventListener(new ClientEventListener() {
                    @Override
                    public void onChatReceived(ChatMessage message) {
                        if (message.sender().equals("phase1-a")
                                && message.content().equals("direct-p2p-test")) {
                            chatReceived.countDown();
                        }
                    }
                });
                first.sendChat("phase1-b", "direct-p2p-test");
                check(chatReceived.await(5, TimeUnit.SECONDS), "P2P chat was not received");

                testFileTransfer(first, second);
                testGroupChat(first, second);

                abortClientConnection(server.boundPort());
                try (ChatClient afterAbort = new ChatClient()) {
                    afterAbort.connect(
                            "127.0.0.1", server.boundPort(), "after-abort", TEST_PASSWORD);
                    check(afterAbort.state() == ConnectionState.CONNECTED,
                            "server stopped accepting clients after an abrupt disconnect");
                    check(!afterAbort.ping().isNegative(), "post-abort ping duration is invalid");
                }
            }
            System.out.println("PASS: auth, UDP Broadcast discovery, P2P chat/file, "
                    + "UDP Multicast group send with server fallback, and shutdown");
        } finally {
            server.close();
            serverThread.shutdown();
            serverThread.awaitTermination(5, TimeUnit.SECONDS);
            serverResult.get(5, TimeUnit.SECONDS);
            Files.deleteIfExists(accountFile);
            System.clearProperty("chatp2p.usersFile");
            System.clearProperty("chatp2p.discoveryPort");
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
            ProtocolMessage hello = ProtocolMessage.builder(MessageType.LOGIN)
                    .field("username", "abrupt-client")
                    .field("password", TEST_PASSWORD)
                    .field("peerPort", "65000")
                    .build();
            Protocol.writeMessage(output, hello);
            ProtocolMessage response = Protocol.readMessage(input);
            check(response.type() == MessageType.LOGIN_ACK, "abrupt client login failed");

            // SO_LINGER=0 sends a TCP reset, simulating a process/network failure.
            socket.setSoLinger(true, 0);
        } finally {
            socket.close();
        }
    }

    private static void registerIfMissing(int port, String username) throws Exception {
        try {
            ChatClient.registerAccount("127.0.0.1", port, username, TEST_PASSWORD);
        } catch (java.io.IOException existing) {
            if (!existing.getMessage().contains("USERNAME_EXISTS")) {
                throw existing;
            }
        }
    }

    private static void testGroupChat(ChatClient sender, ChatClient receiver) throws Exception {
        String group = "phase1-room";
        sender.joinGroup(group);
        receiver.joinGroup(group);
        CountDownLatch delivered = new CountDownLatch(1);
        receiver.addEventListener(new ClientEventListener() {
            @Override
            public void onGroupChatReceived(chatp2p.model.GroupMessage message) {
                if (message.groupName().equals(group)
                        && message.sender().equals("phase1-a")
                        && message.content().equals("hello-group")) {
                    delivered.countDown();
                }
            }
        });
        sender.sendGroupChat(group, "hello-group");
        check(delivered.await(5, TimeUnit.SECONDS), "group chat was not received");
        receiver.leaveGroup(group);
        expectIOException(
                () -> receiver.sendGroupChat(group, "must-fail"),
                "non-member was allowed to send group chat");
        sender.leaveGroup(group);
    }

    private static void expectIOException(CheckedAction action, String failureMessage)
            throws Exception {
        try {
            action.run();
            throw new AssertionError(failureMessage);
        } catch (java.io.IOException expected) {
            // Expected rejection from the server.
        }
    }

    private static void testFileTransfer(ChatClient sender, ChatClient receiver) throws Exception {
        Path source = Files.createTempFile("chatp2p-source-", ".bin");
        byte[] content = new byte[512 * 1024];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 31);
        }
        Files.write(source, content);

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Path> receivedPath = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        ClientEventListener fileListener = new ClientEventListener() {
            @Override
            public boolean onFileOffered(FileInfo file) {
                return file.sender().equals("phase1-a");
            }

            @Override
            public void onTransferCompleted(FileInfo file, Path savedPath) {
                receivedPath.set(savedPath);
                finished.countDown();
            }

            @Override
            public void onTransferFailed(FileInfo file, String reason) {
                failure.set(reason);
                finished.countDown();
            }
        };
        receiver.addEventListener(fileListener);
        try {
            sender.sendFile("phase1-b", source);
            check(finished.await(10, TimeUnit.SECONDS), "P2P file transfer timed out");
            check(failure.get() == null, "P2P file transfer failed: " + failure.get());
            check(receivedPath.get() != null, "receiver did not report the saved file");
            check(Files.mismatch(source, receivedPath.get()) == -1,
                    "received file differs from source");
        } finally {
            receiver.removeEventListener(fileListener);
            Files.deleteIfExists(source);
            if (receivedPath.get() != null) {
                Files.deleteIfExists(receivedPath.get());
            }
        }
    }

    private static void waitUntil(CheckedCondition condition, String failureMessage)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.test() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        check(condition.test(), failureMessage);
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean test() throws Exception;
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
