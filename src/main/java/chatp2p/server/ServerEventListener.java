package chatp2p.server;

/** Receives server events; implementations must be safe to call from network threads. */
@FunctionalInterface
public interface ServerEventListener {
    void onEvent(ServerEvent event);

    static ServerEventListener noOp() {
        return event -> { };
    }
}

