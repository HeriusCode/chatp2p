package chatp2p.protocol;

import java.io.IOException;

/** Signals a malformed, oversized or incompatible protocol frame. */
public final class ProtocolException extends IOException {
    public ProtocolException(String message) {
        super(message);
    }
}

