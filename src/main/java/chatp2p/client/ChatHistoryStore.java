package chatp2p.client;

import chatp2p.model.ChatMessage;
import chatp2p.model.FileInfo;
import chatp2p.model.GroupMessage;
import chatp2p.model.TransferDirection;
import chatp2p.model.TransferProgress;
import chatp2p.model.TransferState;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/** Account-scoped local SQLite history. Message bytes still travel over P2P/server channels. */
public final class ChatHistoryStore {
    private static final String FILE_UPSERT = """
            INSERT INTO files(owner, server, file_id, file_name, file_size, sender, receiver,
                              direction, state, transferred, created_at, source_path, saved_path,
                              downloaded_path, failure_reason, group_name)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(owner, server, file_id) DO UPDATE SET
                state = CASE WHEN files.state IN ('SUCCESS', 'FAILED', 'CANCELLED')
                                  AND excluded.state = 'WAITING'
                             THEN files.state ELSE excluded.state END,
                transferred = MAX(files.transferred, excluded.transferred),
                created_at = MIN(files.created_at, excluded.created_at),
                source_path = COALESCE(excluded.source_path, files.source_path),
                saved_path = COALESCE(excluded.saved_path, files.saved_path),
                downloaded_path = COALESCE(excluded.downloaded_path, files.downloaded_path),
                failure_reason = COALESCE(excluded.failure_reason, files.failure_reason)
                ,group_name = COALESCE(excluded.group_name, files.group_name)
            """;

    public record StoredFile(FileInfo file, TransferDirection direction, TransferState state,
                             long transferredBytes, Instant createdAt, Path sourcePath,
                             Path savedPath, Path downloadedPath, String failureReason) {
    }

    public record Snapshot(List<ChatMessage> directMessages, List<GroupMessage> groupMessages,
                           List<StoredFile> files) {
    }

    private final Path database;
    private final String owner;
    private final String server;
    private final Driver driver;

    public ChatHistoryStore(String owner, String host, int port) throws IOException {
        this(owner, host, port, Path.of(System.getProperty(
                "chatp2p.historyFile", "data/chat_history/chat.db")));
    }

    public ChatHistoryStore(String owner, String host, int port, Path database) throws IOException {
        if (owner == null || owner.isBlank() || host == null || host.isBlank()
                || port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Valid owner and server address are required");
        }
        this.owner = owner;
        this.server = host.trim().toLowerCase(Locale.ROOT) + ":" + port;
        this.database = database.toAbsolutePath().normalize();
        try {
            Path parent = this.database.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            driver = loadDriver();
            try (Connection connection = open(); Statement schema = connection.createStatement()) {
                schema.execute("PRAGMA journal_mode=WAL");
                schema.execute("""
                        CREATE TABLE IF NOT EXISTS messages (
                            owner TEXT NOT NULL, server TEXT NOT NULL, message_id TEXT NOT NULL,
                            kind TEXT NOT NULL, conversation TEXT NOT NULL, sender TEXT NOT NULL,
                            receiver TEXT, content TEXT NOT NULL, sent_at INTEGER NOT NULL,
                            PRIMARY KEY(owner, server, kind, message_id))
                        """);
                schema.execute("CREATE INDEX IF NOT EXISTS messages_by_chat "
                        + "ON messages(owner, server, kind, conversation, sent_at)");
                schema.execute("""
                        CREATE TABLE IF NOT EXISTS files (
                            owner TEXT NOT NULL, server TEXT NOT NULL, file_id TEXT NOT NULL,
                            file_name TEXT NOT NULL, file_size INTEGER NOT NULL,
                            sender TEXT NOT NULL, receiver TEXT NOT NULL,
                            direction TEXT NOT NULL, state TEXT NOT NULL,
                            transferred INTEGER NOT NULL, created_at INTEGER NOT NULL,
                            source_path TEXT, saved_path TEXT, downloaded_path TEXT,
                            failure_reason TEXT, group_name TEXT,
                            PRIMARY KEY(owner, server, file_id))
                        """);
                schema.execute("CREATE INDEX IF NOT EXISTS files_by_chat "
                        + "ON files(owner, server, sender, receiver, created_at)");
                try {
                    schema.execute("ALTER TABLE files ADD COLUMN group_name TEXT");
                } catch (SQLException alreadyPresent) {
                    if (!alreadyPresent.getMessage().toLowerCase(Locale.ROOT)
                            .contains("duplicate column")) {
                        throw alreadyPresent;
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IOException("Không thể mở database lịch sử chat: " + failure.getMessage(), failure);
        }
    }

    public void save(ChatMessage message) throws IOException {
        saveMessage("DIRECT", message.messageId(),
                message.sender().equals(owner) ? message.receiver() : message.sender(),
                message.sender(), message.receiver(), message.content(), message.timestamp());
    }

    public void save(GroupMessage message) throws IOException {
        saveMessage("GROUP", message.messageId(), message.groupName(),
                message.sender(), null, message.content(), message.timestamp());
    }

    private void saveMessage(String kind, UUID id, String conversation, String sender,
                             String receiver, String content, Instant timestamp) throws IOException {
        String sql = "INSERT OR IGNORE INTO messages "
                + "(owner, server, message_id, kind, conversation, sender, receiver, content, sent_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = open(); PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, owner);
            insert.setString(2, server);
            insert.setString(3, id.toString());
            insert.setString(4, kind);
            insert.setString(5, conversation);
            insert.setString(6, sender);
            insert.setString(7, receiver);
            insert.setString(8, content);
            insert.setLong(9, timestamp.toEpochMilli());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    public void saveFileProgress(TransferProgress progress) throws IOException {
        if (progress.state() == TransferState.TRANSFERRING) {
            return; // Progress redraws often; persist the offer and terminal state.
        }
        upsertFile(progress.file(), progress.direction(), progress.state(),
                progress.transferredBytes(), Instant.now(), null, null, null, null);
    }

    public void saveFileOffer(FileInfo file) throws IOException {
        upsertFile(file, TransferDirection.RECEIVING, TransferState.WAITING, 0,
                Instant.now(), null, null, null, null);
    }

    public void saveFileSource(FileInfo file, Path source) throws IOException {
        upsertFile(file, TransferDirection.SENDING, TransferState.WAITING, 0,
                Instant.now(), source, null, null, null);
        String sql = "UPDATE files SET source_path=? WHERE owner=? AND server=? AND file_id=?";
        try (Connection connection = open(); PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, pathText(source));
            bindFileKey(update, 2, file.fileId());
            update.executeUpdate();
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    public void saveFileCompleted(FileInfo file, Path saved) throws IOException {
        TransferDirection direction = file.sender().equals(owner)
                ? TransferDirection.SENDING : TransferDirection.RECEIVING;
        upsertFile(file, direction, TransferState.SUCCESS, file.size(),
                Instant.now(), null, saved, null, null);
    }

    public void saveFileFailed(FileInfo file, String reason) throws IOException {
        TransferDirection direction = file.sender().equals(owner)
                ? TransferDirection.SENDING : TransferDirection.RECEIVING;
        TransferState state = reason != null && reason.contains("hủy")
                ? TransferState.CANCELLED : TransferState.FAILED;
        upsertFile(file, direction, state, 0, Instant.now(), null, null, null, reason);
    }

    public void saveFileAttempt(FileInfo file, Path source, String reason) throws IOException {
        upsertFile(file, TransferDirection.SENDING, TransferState.FAILED, 0,
                Instant.now(), source, null, null, reason);
    }

    /** Merge a server archive row into the local cache without discarding local file paths. */
    public void save(StoredFile stored) throws IOException {
        upsertFile(stored.file(), stored.direction(), stored.state(),
                stored.transferredBytes(), stored.createdAt(), stored.sourcePath(),
                stored.savedPath(), stored.downloadedPath(), stored.failureReason());
    }

    public void saveDownloadedPath(UUID fileId, Path downloaded) throws IOException {
        String sql = "UPDATE files SET downloaded_path=? "
                + "WHERE owner=? AND server=? AND file_id=?";
        try (Connection connection = open(); PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, pathText(downloaded));
            bindFileKey(update, 2, fileId);
            update.executeUpdate();
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    private void upsertFile(FileInfo file, TransferDirection direction, TransferState state,
                            long transferred, Instant createdAt, Path source, Path saved,
                            Path downloaded, String failureReason) throws IOException {
        try (Connection connection = open(); PreparedStatement insert = connection.prepareStatement(FILE_UPSERT)) {
            insert.setString(1, owner);
            insert.setString(2, server);
            insert.setString(3, file.fileId().toString());
            insert.setString(4, file.fileName());
            insert.setLong(5, file.size());
            insert.setString(6, file.sender());
            insert.setString(7, file.receiver());
            insert.setString(8, direction.name());
            insert.setString(9, state.name());
            insert.setLong(10, transferred);
            insert.setLong(11, createdAt.toEpochMilli());
            insert.setString(12, pathText(source));
            insert.setString(13, pathText(saved));
            insert.setString(14, pathText(downloaded));
            insert.setString(15, failureReason);
            insert.setString(16, file.groupName());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    public Snapshot load() throws IOException {
        List<ChatMessage> direct = new ArrayList<>();
        List<GroupMessage> groups = new ArrayList<>();
        List<StoredFile> files = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement messages = connection.prepareStatement(
                     "SELECT * FROM messages WHERE owner=? AND server=? ORDER BY sent_at, message_id");
             PreparedStatement transfers = connection.prepareStatement(
                     "SELECT * FROM files WHERE owner=? AND server=? ORDER BY created_at, file_id")) {
            bindAccount(messages);
            bindAccount(transfers);
            try (ResultSet rows = messages.executeQuery()) {
                while (rows.next()) {
                    UUID id = UUID.fromString(rows.getString("message_id"));
                    Instant timestamp = Instant.ofEpochMilli(rows.getLong("sent_at"));
                    if ("DIRECT".equals(rows.getString("kind"))) {
                        direct.add(new ChatMessage(id, rows.getString("sender"),
                                rows.getString("receiver"), rows.getString("content"), timestamp));
                    } else {
                        groups.add(new GroupMessage(id, rows.getString("conversation"),
                                rows.getString("sender"), rows.getString("content"), timestamp));
                    }
                }
            }
            try (ResultSet rows = transfers.executeQuery()) {
                while (rows.next()) {
                    FileInfo file = new FileInfo(UUID.fromString(rows.getString("file_id")),
                            rows.getString("file_name"), rows.getLong("file_size"),
                            rows.getString("sender"), rows.getString("receiver"),
                            rows.getString("group_name"));
                    TransferState state = TransferState.valueOf(rows.getString("state"));
                    String reason = rows.getString("failure_reason");
                    if (state == TransferState.WAITING || state == TransferState.TRANSFERRING) {
                        state = TransferState.FAILED;
                        reason = "Truyền file bị gián đoạn khi ứng dụng đóng";
                    }
                    files.add(new StoredFile(file,
                            TransferDirection.valueOf(rows.getString("direction")), state,
                            rows.getLong("transferred"),
                            Instant.ofEpochMilli(rows.getLong("created_at")),
                            textPath(rows.getString("source_path")),
                            textPath(rows.getString("saved_path")),
                            textPath(rows.getString("downloaded_path")), reason));
                }
            }
        } catch (SQLException | IllegalArgumentException failure) {
            throw databaseError(failure);
        }
        return new Snapshot(List.copyOf(direct), List.copyOf(groups), List.copyOf(files));
    }

    private Connection open() throws SQLException {
        Connection connection = driver.connect("jdbc:sqlite:" + database, new Properties());
        if (connection == null) {
            throw new SQLException("SQLite JDBC did not accept the database URL");
        }
        try {
            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA busy_timeout=5000");
            }
            return connection;
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
    }

    private static Driver loadDriver() throws IOException {
        try {
            Class<?> jdbc;
            try {
                jdbc = Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException missingFromClasspath) {
                Path jar = Path.of("target", "dependency", "sqlite-jdbc-3.54.1.0.jar")
                        .toAbsolutePath().normalize();
                if (!Files.isRegularFile(jar)) {
                    throw new IOException("Thiếu SQLite JDBC. Hãy chạy scripts/build.ps1 trước.",
                            missingFromClasspath);
                }
                URLClassLoader loader = new URLClassLoader(
                        new URL[]{jar.toUri().toURL()}, ChatHistoryStore.class.getClassLoader());
                jdbc = Class.forName("org.sqlite.JDBC", true, loader);
            }
            return (Driver) jdbc.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IOException("Không thể nạp SQLite JDBC: " + failure.getMessage(), failure);
        }
    }

    private void bindAccount(PreparedStatement statement) throws SQLException {
        statement.setString(1, owner);
        statement.setString(2, server);
    }

    private void bindFileKey(PreparedStatement statement, int start, UUID fileId) throws SQLException {
        statement.setString(start, owner);
        statement.setString(start + 1, server);
        statement.setString(start + 2, fileId.toString());
    }

    private static String pathText(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize().toString();
    }

    private static Path textPath(String path) {
        return path == null ? null : Path.of(path);
    }

    private static IOException databaseError(Exception failure) {
        return new IOException("Lưu/đọc lịch sử chat thất bại: " + failure.getMessage(), failure);
    }
}
