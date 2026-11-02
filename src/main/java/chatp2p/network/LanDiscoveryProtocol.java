package chatp2p.network;

import java.nio.charset.StandardCharsets;

/** Small text protocol used only for UDP LAN server discovery. */
public final class LanDiscoveryProtocol {
    public static final int DEFAULT_PORT = 5050;
    private static final String REQUEST_PREFIX = "CHATP2P_DISCOVER_V2|";
    private static final String RESPONSE_PREFIX = "CHATP2P_SERVER_V2|";
    public static final int MAX_PACKET_BYTES = 512;

    private LanDiscoveryProtocol() {
    }

    public static int port() {
        String configured = System.getProperty("chatp2p.discoveryPort");
        if (configured == null || configured.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(configured);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Discovery port must be between 1 and 65535");
            }
            return port;
        } catch (NumberFormatException invalidPort) {
            throw new IllegalArgumentException("Discovery port must be a number", invalidPort);
        }
    }

    public static byte[] request(String requestId) {
        return (REQUEST_PREFIX + validateRequestId(requestId)).getBytes(StandardCharsets.UTF_8);
    }

    public static String parseRequest(byte[] data, int offset, int length) {
        String message = new String(data, offset, length, StandardCharsets.UTF_8);
        if (!message.startsWith(REQUEST_PREFIX)) {
            throw new IllegalArgumentException("Unknown discovery request");
        }
        return validateRequestId(message.substring(REQUEST_PREFIX.length()));
    }

    public static byte[] response(String requestId, int tcpPort) {
        if (tcpPort < 1 || tcpPort > 65_535) {
            throw new IllegalArgumentException("Invalid discovered TCP port");
        }
        return (RESPONSE_PREFIX + validateRequestId(requestId) + "|" + tcpPort)
                .getBytes(StandardCharsets.UTF_8);
    }

    public static int parseResponse(byte[] data, int length, String expectedRequestId) {
        String message = new String(data, 0, length, StandardCharsets.UTF_8);
        String expectedPrefix = RESPONSE_PREFIX + validateRequestId(expectedRequestId) + "|";
        if (!message.startsWith(expectedPrefix)) {
            throw new IllegalArgumentException("Unknown discovery response");
        }
        int port = Integer.parseInt(message.substring(expectedPrefix.length()));
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Invalid discovered TCP port");
        }
        return port;
    }

    private static String validateRequestId(String requestId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9-]{8,64}")) {
            throw new IllegalArgumentException("Invalid discovery request id");
        }
        return requestId;
    }
}
