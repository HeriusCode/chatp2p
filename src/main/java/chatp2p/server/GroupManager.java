package chatp2p.server;

import chatp2p.model.GroupInfo;
import chatp2p.model.GroupMessage;
import chatp2p.protocol.MessageType;
import chatp2p.protocol.ProtocolMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Thread-safe in-memory group membership and group-message distribution. */
final class GroupManager {
    private final ConcurrentHashMap<String, Set<String>> groups = new ConcurrentHashMap<>();
    private final UserManager users;
    private final Logger logger;

    GroupManager(UserManager users, Logger logger) {
        this.users = users;
        this.logger = logger;
    }

    boolean join(String username, String groupName) {
        validateGroupName(groupName);
        return groups.computeIfAbsent(groupName, ignored -> ConcurrentHashMap.newKeySet())
                .add(username);
    }

    boolean leave(String username, String groupName) {
        Set<String> members = groups.get(groupName);
        if (members == null || !members.remove(username)) {
            return false;
        }
        if (members.isEmpty()) {
            groups.remove(groupName, members);
        }
        return true;
    }

    void removeUser(String username) {
        if (username == null) {
            return;
        }
        groups.forEach((name, members) -> {
            members.remove(username);
            if (members.isEmpty()) {
                groups.remove(name, members);
            }
        });
    }

    boolean isMember(String username, String groupName) {
        Set<String> members = groups.get(groupName);
        return members != null && members.contains(username);
    }

    List<GroupInfo> snapshot(String username) {
        List<GroupInfo> result = new ArrayList<>();
        groups.forEach((name, members) ->
                result.add(new GroupInfo(name, members.size(), members.contains(username))));
        result.sort(Comparator.comparing(GroupInfo::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    void deliver(GroupMessage message) throws IOException {
        Set<String> members = groups.get(message.groupName());
        if (members == null || !members.contains(message.sender())) {
            throw new IOException("Bạn chưa tham gia nhóm " + message.groupName());
        }
        ProtocolMessage delivery = ProtocolMessage.builder(MessageType.GROUP_CHAT)
                .field("messageId", message.messageId().toString())
                .field("groupName", message.groupName())
                .field("sender", message.sender())
                .field("content", message.content())
                .field("timestamp", message.timestamp().toString())
                .build();
        for (String member : List.copyOf(members)) {
            if (member.equals(message.sender())) {
                continue;
            }
            UserManager.OnlineSession session = users.find(member);
            if (session != null) {
                try {
                    session.handler().send(delivery);
                } catch (IOException failure) {
                    logger.warning("Cannot deliver group chat to " + member + ": "
                            + failure.getMessage());
                }
            }
        }
    }

    void broadcastLists() {
        for (UserManager.OnlineSession session : users.snapshot()) {
            try {
                session.handler().send(buildList(null, session.username()));
            } catch (IOException failure) {
                logger.warning("Cannot send GROUP_LIST to " + session.username() + ": "
                        + failure.getMessage());
            }
        }
    }

    ProtocolMessage buildList(ProtocolMessage request, String username) {
        List<GroupInfo> items = snapshot(username);
        ProtocolMessage.Builder builder = request == null
                ? ProtocolMessage.builder(MessageType.GROUP_LIST)
                : ProtocolMessage.responseTo(request, MessageType.GROUP_LIST);
        builder.field("count", Integer.toString(items.size()));
        for (int index = 0; index < items.size(); index++) {
            GroupInfo group = items.get(index);
            builder.field("group." + index + ".name", group.name())
                    .field("group." + index + ".members", Integer.toString(group.memberCount()))
                    .field("group." + index + ".joined", Boolean.toString(group.joined()));
        }
        return builder.build();
    }

    static void validateGroupName(String groupName) {
        if (groupName == null || !groupName.matches("[\\p{L}\\p{N}_. -]{2,32}")) {
            throw new IllegalArgumentException(
                    "Tên nhóm phải có 2-32 ký tự và không chứa ký tự đặc biệt");
        }
    }
}
