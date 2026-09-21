package chatp2p.protocol;

import java.util.Arrays;

/** Message types implemented by the Phase 1 control channel. */
public enum MessageType {
    HELLO(1),
    HELLO_ACK(2),
    PING(3),
    PONG(4),
    DISCONNECT(5),
    ERROR(6);

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

