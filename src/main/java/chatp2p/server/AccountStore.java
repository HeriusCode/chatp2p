package chatp2p.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Persistent PBKDF2 account store. Passwords are never written as plain text. */
final class AccountStore {
    private static final Path DEFAULT_STORE_PATH = Path.of("data", "users.dat");
    private static final int ITERATIONS = 120_000;
    private static final int HASH_BITS = 256;
    private static final int SALT_BYTES = 16;

    private final Map<String, Credential> credentials = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Path storePath;

    AccountStore() throws IOException {
        String configuredPath = System.getProperty("chatp2p.usersFile");
        storePath = configuredPath == null || configuredPath.isBlank()
                ? DEFAULT_STORE_PATH : Path.of(configuredPath);
        load();
    }

    synchronized RegistrationResult register(String username, char[] password) throws IOException {
        validateUsername(username);
        validatePassword(password);
        if (credentials.containsKey(username)) {
            return RegistrationResult.USERNAME_EXISTS;
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, ITERATIONS);
        credentials.put(username, new Credential(ITERATIONS, salt, hash));
        persist();
        return RegistrationResult.CREATED;
    }

    synchronized boolean authenticate(String username, char[] password) {
        Credential credential = credentials.get(username);
        if (credential == null || password == null) {
            return false;
        }
        byte[] actual = derive(password, credential.salt, credential.iterations);
        return MessageDigest.isEqual(credential.hash, actual);
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

    private void load() throws IOException {
        if (!Files.exists(storePath)) {
            return;
        }
        for (String line : Files.readAllLines(storePath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\\|", -1);
            if (fields.length != 4) {
                throw new IOException("Invalid account record in " + storePath);
            }
            try {
                credentials.put(fields[0], new Credential(
                        Integer.parseInt(fields[1]),
                        Base64.getDecoder().decode(fields[2]),
                        Base64.getDecoder().decode(fields[3])));
            } catch (IllegalArgumentException invalidRecord) {
                throw new IOException("Invalid account record for " + fields[0], invalidRecord);
            }
        }
    }

    private void persist() throws IOException {
        Path parent = storePath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = storePath.resolveSibling(storePath.getFileName() + ".tmp");
        List<String> lines = credentials.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .map(entry -> entry.getKey() + "|" + entry.getValue().iterations + "|"
                        + Base64.getEncoder().encodeToString(entry.getValue().salt) + "|"
                        + Base64.getEncoder().encodeToString(entry.getValue().hash))
                .toList();
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, storePath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, storePath, StandardCopyOption.REPLACE_EXISTING);
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

    private record Credential(int iterations, byte[] salt, byte[] hash) {
    }
}
