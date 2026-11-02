package chatp2p.model;

/** Group summary published by the central server. */
public record GroupInfo(String name, int memberCount, boolean joined) {
    public GroupInfo {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("group name cannot be blank");
        }
        if (memberCount < 0) {
            throw new IllegalArgumentException("member count cannot be negative");
        }
    }
}
