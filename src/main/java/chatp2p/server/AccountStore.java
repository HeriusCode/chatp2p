package chatp2p.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Persistent PBKDF2 account store. Passwords are never written as plain text. */
final class AccountStore {
    private static final Path DEFAULT_STORE_PATH = Path.of("data", "users.dat");
    private static final int ITERATIONS = 120_000;
    private static final int HASH_BITS = 256;
    private static final int SALT_BYTES = 16;

    private final SecureRandom random = new SecureRandom();
    private final Path storePath;
    private final PostgresDatabase database;

    AccountStore(PostgresDatabase database) throws IOException {
        this.database = database;
        String configuredPath = System.getProperty("chatp2p.usersFile");
        storePath = configuredPath == null || configuredPath.isBlank()
                ? DEFAULT_STORE_PATH : Path.of(configuredPath);
        importLegacyAccounts();
    }

    synchronized RegistrationResult register(String username, char[] password) throws IOException {
        validateUsername(username);
        validatePassword(password);
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, ITERATIONS);
        try (Connection connection = database.open(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO accounts(username, iterations, salt, password_hash) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT(username) DO NOTHING")) {
            insert.setString(1, username);
            insert.setInt(2, ITERATIONS);
            insert.setBytes(3, salt);
            insert.setBytes(4, hash);
            return insert.executeUpdate() == 1
                    ? RegistrationResult.CREATED : RegistrationResult.USERNAME_EXISTS;
        } catch (SQLException failure) {
            throw new IOException("Cannot register account in PostgreSQL", failure);
        }
    }

    synchronized boolean authenticate(String username, char[] password) throws IOException {
        if (password == null) {
            return false;
        }
        try (Connection connection = database.open(); PreparedStatement select = connection.prepareStatement(
                "SELECT iterations, salt, password_hash FROM accounts WHERE username=?")) {
            select.setString(1, username);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return false;
                }
                byte[] actual = derive(password, row.getBytes("salt"), row.getInt("iterations"));
                return MessageDigest.isEqual(row.getBytes("password_hash"), actual);
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot authenticate account in PostgreSQL", failure);
        }
    }

    static void validateUsername(String username) {
        if (username == null || !username.matches("[\\p{L}\\p{N}_.-]{3,32}")) {
            throw new IllegalArgumentException(
                    "Username phải có 3-32 ký tự: chữ, số, dấu chấm, gạch dưới hoặc gạch ngang");
        }
    }

    static void validatePassword(char[] password) {
        if (password == null || password.length < 6 || password.length > 128) {
            throw new IllegalArgumentException("Mật khẩu phải có 6-128 ký tự");
        }
    }

    private void importLegacyAccounts() throws IOException {
        if (!Files.exists(storePath)) {
            return;
        }
        String sql = "INSERT INTO accounts(username, iterations, salt, password_hash) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT(username) DO NOTHING";
        try (Connection connection = database.open(); PreparedStatement insert = connection.prepareStatement(sql)) {
            for (String line : Files.readAllLines(storePath, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split("\\|", -1);
                if (fields.length != 4) {
                    throw new IOException("Invalid account record in " + storePath);
                }
                try {
                    insert.setString(1, fields[0]);
                    insert.setInt(2, Integer.parseInt(fields[1]));
                    insert.setBytes(3, Base64.getDecoder().decode(fields[2]));
                    insert.setBytes(4, Base64.getDecoder().decode(fields[3]));
                    insert.executeUpdate();
                } catch (IllegalArgumentException invalidRecord) {
                    throw new IOException("Invalid account record for " + fields[0], invalidRecord);
                }
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot import data/users.dat into PostgreSQL", failure);
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec specification = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is unavailable", unavailable);
        } finally {
            specification.clearPassword();
        }
    }

    enum RegistrationResult {
        CREATED,
        USERNAME_EXISTS
    }

}
