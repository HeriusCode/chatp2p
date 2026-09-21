package chatp2p.model;

/** Network endpoint published by the central server for direct P2P connections. */
public record PeerInfo(String username, String host, int port) {
    public PeerInfo {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username cannot be blank");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host cannot be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("peer port must be between 1 and 65535");
        }
    }
}
