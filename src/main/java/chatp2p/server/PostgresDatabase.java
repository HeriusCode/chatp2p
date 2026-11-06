package chatp2p.server;

import chatp2p.model.GroupMessage;
import chatp2p.protocol.MessageType;
import chatp2p.protocol.ProtocolMessage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Server-only PostgreSQL connection and persistent chat archive. */
final class PostgresDatabase {
    private final String url;
    private final Properties credentials;

    PostgresDatabase() throws IOException {
        Properties config = new Properties();
        Path file = Path.of(System.getProperty("chatp2p.db.config", "data/db.properties"));
        if (Files.isRegularFile(file)) {
            try (InputStream input = Files.newInputStream(file)) {
                config.load(input);
            }
        }
        url = System.getProperty("chatp2p.db.url",
                System.getenv().getOrDefault("CHATP2P_DB_URL",
                        config.getProperty("url", "jdbc:postgresql://localhost:5432/chatp2p")));
        credentials = new Properties();
        credentials.setProperty("user", System.getenv().getOrDefault("CHATP2P_DB_USER",
                config.getProperty("user", "postgres")));
        credentials.setProperty("password", System.getenv().getOrDefault("CHATP2P_DB_PASSWORD",
                config.getProperty("password", "")));
        try (Connection connection = open(); Statement schema = connection.createStatement()) {
            schema.execute("""
                    CREATE TABLE IF NOT EXISTS accounts (
                      username TEXT PRIMARY KEY, iterations INTEGER NOT NULL,
                      salt BYTEA NOT NULL, password_hash BYTEA NOT NULL)
                    """);
            schema.execute("""
                    CREATE TABLE IF NOT EXISTS chat_history (
                      owner TEXT NOT NULL REFERENCES accounts(username),
                      kind TEXT NOT NULL, event_id UUID NOT NULL,
                      sender TEXT NOT NULL, receiver TEXT, conversation TEXT,
                      content TEXT, sent_at BIGINT NOT NULL,
                      file_name TEXT, file_size BIGINT,
                      direction TEXT, transfer_state TEXT, transferred BIGINT,
                      saved_name TEXT, failure_reason TEXT,
                      PRIMARY KEY(owner, kind, event_id))
                    """);
            schema.execute("CREATE INDEX IF NOT EXISTS history_by_owner "
                    + "ON chat_history(owner, sent_at, event_id)");
            schema.execute("""
                    CREATE TABLE IF NOT EXISTS chat_groups (
                      name TEXT PRIMARY KEY,
                      creator TEXT NOT NULL REFERENCES accounts(username),
                      created_at TIMESTAMPTZ NOT NULL DEFAULT now())
                    """);
            schema.execute("""
                    CREATE TABLE IF NOT EXISTS group_members (
                      group_name TEXT NOT NULL REFERENCES chat_groups(name),
                      username TEXT NOT NULL REFERENCES accounts(username),
                      joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      PRIMARY KEY(group_name, username))
                    """);
        } catch (SQLException failure) {
            throw new IOException("Cannot connect to PostgreSQL: " + failure.getMessage(), failure);
        }
    }

    Connection open() throws SQLException {
        return DriverManager.getConnection(url, credentials);
    }

    Map<String, Set<String>> loadGroups() throws IOException {
        Map<String, Set<String>> groups = new HashMap<>();
        try (Connection connection = open(); Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT g.name, m.username FROM chat_groups g "
                     + "LEFT JOIN group_members m ON m.group_name = g.name")) {
            while (rows.next()) {
                Set<String> members = groups.computeIfAbsent(rows.getString(1),
                        ignored -> new HashSet<>());
                if (rows.getString(2) != null) {
                    members.add(rows.getString(2));
                }
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot load groups from PostgreSQL", failure);
        }
        return groups;
    }

    boolean createGroup(String creator, String groupName, List<String> invitees) throws IOException {
        Set<String> members = new LinkedHashSet<>(invitees);
        members.add(creator);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement account = connection.prepareStatement(
                         "SELECT 1 FROM accounts WHERE username=?");
                 PreparedStatement group = connection.prepareStatement(
                         "INSERT INTO chat_groups(name, creator) VALUES (?, ?) "
                                 + "ON CONFLICT(name) DO NOTHING");
                 PreparedStatement member = connection.prepareStatement(
                         "INSERT INTO group_members(group_name, username) VALUES (?, ?)")) {
                group.setString(1, groupName);
                group.setString(2, creator);
                if (group.executeUpdate() == 0) {
                    connection.rollback();
                    return false;
                }
                for (String username : members) {
                    account.setString(1, username);
                    try (ResultSet found = account.executeQuery()) {
                        if (!found.next()) {
                            connection.rollback();
                            throw new IOException("Tài khoản " + username + " không tồn tại");
                        }
                    }
                    member.setString(1, groupName);
                    member.setString(2, username);
                    member.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException | IOException failure) {
                connection.rollback();
                if (failure instanceof IOException io) {
                    throw io;
                }
                throw new IOException("Cannot create group in PostgreSQL", failure);
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot create group in PostgreSQL", failure);
        }
    }

    void joinGroup(String username, String groupName) throws IOException {
        try (Connection connection = open(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO group_members(group_name, username) VALUES (?, ?) "
                        + "ON CONFLICT(group_name, username) DO NOTHING")) {
            insert.setString(1, groupName);
            insert.setString(2, username);
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new IOException("Cannot join group in PostgreSQL", failure);
        }
    }

    boolean leaveGroup(String username, String groupName) throws IOException {
        try (Connection connection = open(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM group_members WHERE group_name=? AND username=?")) {
            delete.setString(1, groupName);
            delete.setString(2, username);
            return delete.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new IOException("Cannot leave group in PostgreSQL", failure);
        }
    }

    void saveGroupMessage(GroupMessage message, Set<String> members) throws IOException {
        String sql = "INSERT INTO chat_history(owner, kind, event_id, sender, conversation, "
                + "content, sent_at) VALUES (?, 'GROUP', ?, ?, ?, ?, ?) "
                + "ON CONFLICT(owner, kind, event_id) DO NOTHING";
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                for (String member : members) {
                    insert.setString(1, member);
                    insert.setObject(2, message.messageId());
                    insert.setString(3, message.sender());
                    insert.setString(4, message.groupName());
                    insert.setString(5, message.content());
                    insert.setLong(6, message.timestamp().toEpochMilli());
                    insert.addBatch();
                }
                insert.executeBatch();
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw new IOException("Cannot save group chat in PostgreSQL", failure);
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot save group chat in PostgreSQL", failure);
        }
    }

    void putHistory(String owner, ProtocolMessage request) throws IOException {
        String kind = request.requiredField("kind");
        if (!kind.equals("DIRECT") && !kind.equals("GROUP") && !kind.equals("FILE")) {
            throw new IOException("Invalid history kind");
        }
        String sender = request.requiredField("sender");
        String receiver = request.field("receiver");
        if (kind.equals("GROUP")) {
            GroupManager.validateGroupName(request.requiredField("conversation"));
        } else if (!owner.equals(sender) && !owner.equals(receiver)) {
            throw new IOException("History event does not belong to this account");
        }
        UUID id;
        long sentAt;
        try {
            id = UUID.fromString(request.requiredField("id"));
            sentAt = Long.parseLong(request.requiredField("time"));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid history ID or time", invalid);
        }
        String sql = """
                INSERT INTO chat_history(owner, kind, event_id, sender, receiver,
                    conversation, content, sent_at, file_name, file_size, direction,
                    transfer_state, transferred, saved_name, failure_reason)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(owner, kind, event_id) DO UPDATE SET
                    transfer_state=CASE
                        WHEN chat_history.transfer_state IN ('SUCCESS', 'FAILED', 'CANCELLED')
                             AND EXCLUDED.transfer_state IN ('WAITING', 'TRANSFERRING')
                        THEN chat_history.transfer_state
                        ELSE COALESCE(EXCLUDED.transfer_state, chat_history.transfer_state) END,
                    transferred=GREATEST(COALESCE(EXCLUDED.transferred, 0),
                        COALESCE(chat_history.transferred, 0)),
                    saved_name=COALESCE(NULLIF(EXCLUDED.saved_name, ''), chat_history.saved_name),
                    failure_reason=COALESCE(EXCLUDED.failure_reason, chat_history.failure_reason)
                """;
        try (Connection connection = open(); PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, owner);
            insert.setString(2, kind);
            insert.setObject(3, id);
            insert.setString(4, sender);
            insert.setString(5, receiver);
            insert.setString(6, request.field("conversation"));
            insert.setString(7, request.field("content"));
            insert.setLong(8, sentAt);
            insert.setString(9, request.field("fileName"));
            insert.setObject(10, numberOrNull(request.field("fileSize")));
            insert.setString(11, request.field("direction"));
            insert.setString(12, request.field("state"));
            insert.setObject(13, numberOrNull(request.field("transferred")));
            insert.setString(14, request.field("savedName"));
            insert.setString(15, request.field("reason"));
            insert.executeUpdate();
        } catch (SQLException | NumberFormatException failure) {
            throw new IOException("Cannot save chat history: " + failure.getMessage(), failure);
        }
    }

    ProtocolMessage historyPage(String owner, ProtocolMessage request) throws IOException {
        int offset;
        try {
            offset = Integer.parseInt(request.requiredField("offset"));
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid history offset", invalid);
        }
        if (offset < 0 || offset > 1_000_000) {
            throw new IOException("Invalid history offset");
        }
        ProtocolMessage.Builder page = ProtocolMessage.responseTo(request, MessageType.HISTORY_PAGE);
        int count = 0;
        try (Connection connection = open(); PreparedStatement select = connection.prepareStatement(
                "SELECT * FROM chat_history WHERE owner=? ORDER BY sent_at, kind, event_id LIMIT 3 OFFSET ?")) {
            select.setString(1, owner);
            select.setInt(2, offset);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    String prefix = "item." + count++ + ".";
                    page.field(prefix + "kind", row.getString("kind"))
                            .field(prefix + "id", row.getString("event_id"))
                            .field(prefix + "sender", row.getString("sender"))
                            .field(prefix + "receiver", empty(row.getString("receiver")))
                            .field(prefix + "conversation", empty(row.getString("conversation")))
                            .field(prefix + "content", empty(row.getString("content")))
                            .field(prefix + "time", Long.toString(row.getLong("sent_at")))
                            .field(prefix + "fileName", empty(row.getString("file_name")))
                            .field(prefix + "fileSize", number(row, "file_size"))
                            .field(prefix + "direction", empty(row.getString("direction")))
                            .field(prefix + "state", empty(row.getString("transfer_state")))
                            .field(prefix + "transferred", number(row, "transferred"))
                            .field(prefix + "savedName", empty(row.getString("saved_name")))
                            .field(prefix + "reason", empty(row.getString("failure_reason")));
                }
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot read chat history: " + failure.getMessage(), failure);
        }
        return page.field("count", Integer.toString(count)).build();
    }

    private static Long numberOrNull(String value) {
        return value == null || value.isBlank() ? null : Long.parseLong(value);
    }

    private static String number(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? "" : Long.toString(value);
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
