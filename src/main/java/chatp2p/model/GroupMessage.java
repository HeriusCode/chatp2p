package chatp2p.model;

import java.time.Instant;
import java.util.UUID;

/** One text message delivered to all online members of a group. */
public record GroupMessage(
        UUID messageId, String groupName, String sender, String content, Instant timestamp) {
    public GroupMessage {
        if (messageId == null || timestamp == null) {
            throw new IllegalArgumentException("messageId and timestamp are required");
        }
        if (groupName == null || groupName.isBlank() || sender == null || sender.isBlank()) {
            throw new IllegalArgumentException("groupName and sender are required");
        }
        if (content == null || content.isBlank() || content.length() > 4_000) {
            throw new IllegalArgumentException("message must contain 1-4000 characters");
        }
    }
}
