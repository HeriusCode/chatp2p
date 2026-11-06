package chatp2p;

import chatp2p.client.ChatClient;
import chatp2p.client.ChatHistoryStore;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
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
        Properties databaseConfig = new Properties();
        try (var input = Files.newInputStream(Path.of("data", "db.properties"))) {
            databaseConfig.load(input);
        }
        String schema = "chatp2p_test_" + UUID.randomUUID().toString().replace("-", "");
        String baseUrl = databaseConfig.getProperty("url");
        Properties login = new Properties();
        login.setProperty("user", databaseConfig.getProperty("user"));
        login.setProperty("password", databaseConfig.getProperty("password"));
        try (Connection database = DriverManager.getConnection(baseUrl, login);
             Statement setup = database.createStatement()) {
            setup.execute("CREATE SCHEMA " + schema);
        }
        System.setProperty("chatp2p.db.url", baseUrl + "?currentSchema=" + schema);
        Path accountFile = Files.createTempFile("chatp2p-test-users-", ".dat");
        Files.deleteIfExists(accountFile);
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(TEST_PASSWORD.toCharArray(), salt, 120_000, 256))
                .getEncoded();
        Files.writeString(accountFile, "legacy-user|120000|"
                + Base64.getEncoder().encodeToString(salt) + "|"
                + Base64.getEncoder().encodeToString(hash) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        System.setProperty("chatp2p.usersFile", accountFile.toString());
        Path historyFile = Files.createTempFile("chatp2p-test-history-", ".db");
        Files.deleteIfExists(historyFile);
        System.setProperty("chatp2p.historyFile", historyFile.toString());
        Path remoteHistoryFile = Files.createTempFile("chatp2p-test-remote-", ".db");
        Files.deleteIfExists(remoteHistoryFile);
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
            try (ChatClient legacy = new ChatClient()) {
                legacy.connect("127.0.0.1", server.boundPort(), "legacy-user", TEST_PASSWORD);
                check(legacy.state() == ConnectionState.CONNECTED,
                        "legacy account was not imported into PostgreSQL");
            }
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
                expectIOException(() -> first.createGroup(
                                "phase1-invalid", java.util.List.of("no-such-account")),
                        "unknown invitee was accepted");
                first.createGroup("phase1-persisted",
                        java.util.List.of("phase1-b", "legacy-user"));
                waitUntil(() -> second.groups().stream().anyMatch(group ->
                                group.name().equals("phase1-persisted")
                                        && group.joined() && group.memberCount() == 3),
                        "invited user did not see the new group");
                first.sendGroupChat("phase1-persisted", "offline-group-history");
                check(first.loadHistory().directMessages().stream()
                        .anyMatch(message -> message.content().equals("direct-p2p-test")),
                        "sender history was not synchronized");
                check(second.loadHistory().directMessages().stream()
                        .anyMatch(message -> message.content().equals("direct-p2p-test")),
                        "receiver history was not synchronized");

                abortClientConnection(server.boundPort());
                try (ChatClient afterAbort = new ChatClient()) {
                    afterAbort.connect(
                            "127.0.0.1", server.boundPort(), "after-abort", TEST_PASSWORD);
                    check(afterAbort.state() == ConnectionState.CONNECTED,
                            "server stopped accepting clients after an abrupt disconnect");
                    check(!afterAbort.ping().isNegative(), "post-abort ping duration is invalid");
                }
            }
            ChatHistoryStore firstHistory = new ChatHistoryStore(
                    "phase1-a", "127.0.0.1", server.boundPort(), historyFile);
            ChatHistoryStore secondHistory = new ChatHistoryStore(
                    "phase1-b", "127.0.0.1", server.boundPort(), historyFile);
            check(firstHistory.load().directMessages().stream()
                    .anyMatch(message -> message.content().equals("direct-p2p-test")),
                    "sender P2P history did not survive restart");
            check(secondHistory.load().directMessages().stream()
                    .anyMatch(message -> message.content().equals("direct-p2p-test")),
                    "receiver P2P history did not survive restart");
            check(firstHistory.load().groupMessages().stream()
                    .anyMatch(message -> message.content().equals("hello-group")),
                    "group history did not survive restart");
            check(secondHistory.load().groupMessages().stream()
                    .filter(message -> message.content().equals("hello-group")).count() == 1,
                    "receiver group history was missing or duplicated");
            check(secondHistory.load().files().size() == 1,
                    "receiver file history did not survive restart");
            check(new ChatHistoryStore("another-account", "127.0.0.1",
                    server.boundPort(), historyFile).load().directMessages().isEmpty(),
                    "history was visible to another account");
            System.setProperty("chatp2p.historyFile", remoteHistoryFile.toString());
            try (ChatClient restored = new ChatClient()) {
                restored.connect("127.0.0.1", server.boundPort(), "phase1-b", TEST_PASSWORD);
                ChatHistoryStore.Snapshot recovered = restored.loadHistory();
                check(recovered.directMessages().stream()
                        .anyMatch(message -> message.content().equals("direct-p2p-test")),
                        "PostgreSQL direct history was not restored on another cache");
                check(recovered.groupMessages().stream()
                        .anyMatch(message -> message.content().equals("hello-group")),
                        "PostgreSQL group history was not restored on another cache");
                check(!recovered.files().isEmpty(),
                        "PostgreSQL file metadata was not restored on another cache");
            }
            ChatServer reloadedServer = new ChatServer(0);
            ExecutorService reloadedThread = Executors.newSingleThreadExecutor();
            Future<?> reloadedResult = reloadedThread.submit(() -> {
                try {
                    reloadedServer.start();
                } catch (java.io.IOException failure) {
                    throw new RuntimeException(failure);
                }
            });
            try {
                reloadedServer.awaitStarted(Duration.ofSeconds(5));
                try (ChatClient invited = new ChatClient()) {
                    invited.connect("127.0.0.1", reloadedServer.boundPort(),
                            "legacy-user", TEST_PASSWORD);
                    check(invited.groups().stream().anyMatch(group ->
                                    group.name().equals("phase1-persisted")
                                            && group.joined() && group.memberCount() == 3),
                            "group membership was not restored from PostgreSQL");
                    check(invited.loadHistory().groupMessages().stream().anyMatch(message ->
                                    message.groupName().equals("phase1-persisted")
                                            && message.content().equals("offline-group-history")),
                            "offline invitee did not receive saved group history");
                }
            } finally {
                reloadedServer.close();
                reloadedThread.shutdown();
                reloadedThread.awaitTermination(5, TimeUnit.SECONDS);
                reloadedResult.get(5, TimeUnit.SECONDS);
            }
            try (Connection database = DriverManager.getConnection(
                    baseUrl + "?currentSchema=" + schema, login);
                 Statement query = database.createStatement();
                 ResultSet accounts = query.executeQuery("SELECT count(*) FROM accounts")) {
                check(accounts.next() && accounts.getInt(1) >= 5,
                        "accounts were not stored in PostgreSQL");
            }
            try (Connection database = DriverManager.getConnection(
                    baseUrl + "?currentSchema=" + schema, login);
                 Statement query = database.createStatement();
                 ResultSet invalidGroup = query.executeQuery(
                         "SELECT count(*) FROM chat_groups WHERE name='phase1-invalid'")) {
                check(invalidGroup.next() && invalidGroup.getInt(1) == 0,
                        "failed invitation left a partial group in PostgreSQL");
            }
            System.out.println("PASS: auth, UDP Broadcast discovery, P2P chat/file, "
                    + "UDP Multicast group send, PostgreSQL accounts/groups/history, and shutdown");
        } finally {
            server.close();
            serverThread.shutdown();
            serverThread.awaitTermination(5, TimeUnit.SECONDS);
            serverResult.get(5, TimeUnit.SECONDS);
            Files.deleteIfExists(accountFile);
            Files.deleteIfExists(historyFile);
            Files.deleteIfExists(Path.of(historyFile + "-wal"));
            Files.deleteIfExists(Path.of(historyFile + "-shm"));
            Files.deleteIfExists(remoteHistoryFile);
            Files.deleteIfExists(Path.of(remoteHistoryFile + "-wal"));
            Files.deleteIfExists(Path.of(remoteHistoryFile + "-shm"));
            System.clearProperty("chatp2p.usersFile");
            System.clearProperty("chatp2p.historyFile");
            System.clearProperty("chatp2p.discoveryPort");
            System.clearProperty("chatp2p.db.url");
            try (Connection database = DriverManager.getConnection(baseUrl, login);
                 Statement cleanup = database.createStatement()) {
                cleanup.execute("DROP SCHEMA " + schema + " CASCADE");
            }
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
        sender.createGroup(group, java.util.List.of(receiver.clientName()));
        waitUntil(() -> receiver.groups().stream()
                .anyMatch(item -> item.name().equals(group) && item.joined()),
                "invited receiver did not join the group");
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
