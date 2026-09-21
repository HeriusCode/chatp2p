package chatp2p.server;

import chatp2p.protocol.MessageType;
import chatp2p.protocol.ProtocolMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Thread-safe registry of online control connections and their P2P endpoints. */
final class UserManager {
    private final ConcurrentHashMap<String, OnlineSession> sessions = new ConcurrentHashMap<>();
    private final Logger logger;

    UserManager(Logger logger) {
        this.logger = logger;
    }

    boolean register(
            String username, String peerHost, int peerPort, ClientHandler handler) {
        return sessions.putIfAbsent(
                username, new OnlineSession(username, peerHost, peerPort, handler)) == null;
    }

    void unregister(String username, ClientHandler handler) {
        if (username != null) {
            sessions.computeIfPresent(username,
                    (ignored, session) -> session.handler == handler ? null : session);
        }
    }

    OnlineSession find(String username) {
        OnlineSession session = sessions.get(username);
        return session != null && session.handler.isAvailable() ? session : null;
    }

    List<OnlineSession> snapshot() {
        List<OnlineSession> result = sessions.values().stream()
                .filter(session -> session.handler.isAvailable())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        result.sort(Comparator.comparing(OnlineSession::username, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    void broadcastUserList() {
        List<OnlineSession> users = snapshot();
        ProtocolMessage.Builder builder = ProtocolMessage.builder(MessageType.USER_LIST)
                .field("count", Integer.toString(users.size()));
        for (int index = 0; index < users.size(); index++) {
            OnlineSession user = users.get(index);
            builder.field("user." + index + ".name", user.username)
                    .field("user." + index + ".host", user.peerHost)
                    .field("user." + index + ".port", Integer.toString(user.peerPort));
        }
        ProtocolMessage message = builder.build();
        for (OnlineSession session : users) {
            try {
                session.handler.send(message);
            } catch (IOException failure) {
                logger.warning("Cannot send USER_LIST to " + session.username + ": "
                        + failure.getMessage());
            }
        }
    }

    record OnlineSession(
            String username, String peerHost, int peerPort, ClientHandler handler) {
    }
}
