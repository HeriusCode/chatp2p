package chatp2p.model;

import java.util.UUID;

/** Metadata announced before any file bytes are transferred. */
public record FileInfo(
        UUID fileId, String fileName, long size, String sender, String receiver) {
    public FileInfo {
        if (fileId == null) {
            throw new IllegalArgumentException("fileId is required");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName is required");
        }
        if (size < 0) {
            throw new IllegalArgumentException("file size cannot be negative");
        }
        if (sender == null || sender.isBlank() || receiver == null || receiver.isBlank()) {
            throw new IllegalArgumentException("sender and receiver are required");
        }
    }
}
