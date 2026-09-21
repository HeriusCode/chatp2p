package chatp2p.model;

import java.time.Instant;
import java.util.UUID;

/** One immutable direct peer-to-peer chat message. */
public record ChatMessage(
        UUID messageId, String sender, String receiver, String content, Instant timestamp) {
    public ChatMessage {
        if (messageId == null || timestamp == null) {
            throw new IllegalArgumentException("messageId and timestamp are required");
        }
        if (sender == null || sender.isBlank() || receiver == null || receiver.isBlank()) {
            throw new IllegalArgumentException("sender and receiver are required");
        }
        if (content == null || content.isBlank() || content.length() > 4_000) {
            throw new IllegalArgumentException("message must contain 1-4000 characters");
        }
    }
}
