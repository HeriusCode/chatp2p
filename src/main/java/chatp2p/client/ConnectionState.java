package chatp2p.client;

/** Lifecycle of the client's central-server control connection. */
public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    CLOSING
}

