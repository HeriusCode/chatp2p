package chatp2p.protocol;

import java.util.Arrays;

/** Stable wire codes used by the central control channel and P2P chat channel. */
public enum MessageType {
    HELLO(1),
    HELLO_ACK(2),
    PING(3),
    PONG(4),
    DISCONNECT(5),
    ERROR(6),
    GET_USERS(7),
    USER_LIST(8),
    CONNECT_REQUEST(9),
    PEER_INFO(10),
    CHAT(11),
    FILE_REQUEST(12),
    FILE_ACCEPT(13),
    FILE_REJECT(14),
    FILE_END(15),
    FILE_CANCEL(16);

    private final int wireCode;

    MessageType(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static MessageType fromWireCode(int wireCode) throws ProtocolException {
        return Arrays.stream(values())
                .filter(type -> type.wireCode == wireCode)
                .findFirst()
                .orElseThrow(() -> new ProtocolException("Unknown message type: " + wireCode));
    }
}
