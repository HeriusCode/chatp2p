package chatp2p.server;

import chatp2p.protocol.MessageType;
import chatp2p.protocol.ProtocolMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/** Imports the server machine's former SQLite cache without removing it. */
final class LegacyHistoryImporter {
    private LegacyHistoryImporter() {
    }

    static int importLocal(PostgresDatabase database) throws IOException {
        Path file = Path.of(System.getProperty("chatp2p.historyFile",
                "data/chat_history/chat.db"));
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        Set<String> accounts = new HashSet<>();
        try (Connection postgres = database.open();
             Statement query = postgres.createStatement();
             ResultSet rows = query.executeQuery("SELECT username FROM accounts")) {
            while (rows.next()) {
                accounts.add(rows.getString(1));
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot list PostgreSQL accounts for history import", failure);
        }
        int imported = 0;
        try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = sqlite.createStatement()) {
            try (ResultSet row = statement.executeQuery("SELECT * FROM messages")) {
                while (row.next()) {
                    String owner = row.getString("owner");
                    if (!accounts.contains(owner)) {
                        continue;
                    }
                    ProtocolMessage.Builder item = ProtocolMessage.builder(MessageType.HISTORY_PUT)
                            .field("kind", row.getString("kind"))
                            .field("id", row.getString("message_id"))
                            .field("sender", row.getString("sender"))
                            .field("time", Long.toString(row.getLong("sent_at")))
                            .field("content", row.getString("content"));
                    if ("DIRECT".equals(row.getString("kind"))) {
                        item.field("receiver", row.getString("receiver"));
                    } else {
                        item.field("conversation", row.getString("conversation"));
                    }
                    database.putHistory(owner, item.build());
                    imported++;
                }
            }
            try (ResultSet row = statement.executeQuery("SELECT * FROM files")) {
                while (row.next()) {
                    String owner = row.getString("owner");
                    if (!accounts.contains(owner)) {
                        continue;
                    }
                    String saved = row.getString("saved_path");
                    ProtocolMessage item = ProtocolMessage.builder(MessageType.HISTORY_PUT)
                            .field("kind", "FILE")
                            .field("id", row.getString("file_id"))
                            .field("sender", row.getString("sender"))
                            .field("receiver", row.getString("receiver"))
                            .field("time", Long.toString(row.getLong("created_at")))
                            .field("fileName", row.getString("file_name"))
                            .field("fileSize", Long.toString(row.getLong("file_size")))
                            .field("direction", row.getString("direction"))
                            .field("state", row.getString("state"))
                            .field("transferred", Long.toString(row.getLong("transferred")))
                            .field("savedName", saved == null ? "" : Path.of(saved).getFileName().toString())
                            .field("reason", row.getString("failure_reason") == null
                                    ? "" : row.getString("failure_reason"))
                            .build();
                    database.putHistory(owner, item);
                    imported++;
                }
            }
        } catch (SQLException | IllegalArgumentException failure) {
            throw new IOException("Cannot import local SQLite history", failure);
        }
        return imported;
    }
}
