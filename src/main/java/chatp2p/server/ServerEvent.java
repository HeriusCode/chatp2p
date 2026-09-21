package chatp2p.server;

import java.time.Instant;

/** Immutable event published by the TCP server for monitoring interfaces. */
public record ServerEvent(
        Instant timestamp,
        Type type,
        long connectionId,
        String clientName,
        String remoteAddress,
        String message) {

    public enum Type {
        SERVER_STARTED,
        SERVER_STOPPED,
        CONNECTION_ACCEPTED,
        HANDSHAKE_COMPLETED,
        CLIENT_DISCONNECTED,
        ERROR
    }

    public static ServerEvent server(Type type, String message) {
        return new ServerEvent(Instant.now(), type, 0, "", "", message);
    }

    public static ServerEvent client(
            Type type, long connectionId, String clientName, String remoteAddress, String message) {
        return new ServerEvent(
                Instant.now(), type, connectionId,
                clientName == null ? "" : clientName,
                remoteAddress == null ? "" : remoteAddress,
                message);
    }
}

