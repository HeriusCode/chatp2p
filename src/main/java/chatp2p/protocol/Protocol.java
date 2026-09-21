package chatp2p.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Length-prefixed binary framing for the TCP control channel.
 * The explicit lengths prevent TCP packet boundaries from affecting message parsing.
 */
public final class Protocol {
    public static final int MAGIC = 0x43503250; // ASCII "CP2P"
    public static final int VERSION = 1;
    public static final int MAX_FRAME_BYTES = 256 * 1024;
    public static final int MAX_FIELDS = 256;
    public static final int MAX_KEY_BYTES = 128;
    public static final int MAX_VALUE_BYTES = 64 * 1024;

    private Protocol() {
    }

    public static void writeMessage(DataOutputStream output, ProtocolMessage message) throws IOException {
        byte[] frame = encodeBody(message);
        if (frame.length > MAX_FRAME_BYTES) {
            throw new ProtocolException("Frame exceeds " + MAX_FRAME_BYTES + " bytes");
        }
        output.writeInt(frame.length);
        output.write(frame);
        output.flush();
    }

    public static ProtocolMessage readMessage(DataInputStream input) throws IOException {
        int frameLength;
        try {
            frameLength = input.readInt();
        } catch (EOFException eof) {
            throw eof;
        }
        if (frameLength <= 0 || frameLength > MAX_FRAME_BYTES) {
            throw new ProtocolException("Invalid frame length: " + frameLength);
        }

        byte[] frame = new byte[frameLength];
        input.readFully(frame);
        try (DataInputStream body = new DataInputStream(new java.io.ByteArrayInputStream(frame))) {
            int magic = body.readInt();
            if (magic != MAGIC) {
                throw new ProtocolException("Invalid protocol magic");
            }
            int version = body.readUnsignedShort();
            if (version != VERSION) {
                throw new ProtocolException("Unsupported protocol version: " + version);
            }

            MessageType type = MessageType.fromWireCode(body.readUnsignedByte());
            UUID correlationId = new UUID(body.readLong(), body.readLong());
            int fieldCount = body.readUnsignedShort();
            if (fieldCount > MAX_FIELDS) {
                throw new ProtocolException("Too many fields: " + fieldCount);
            }

            Map<String, String> fields = new LinkedHashMap<>();
            for (int i = 0; i < fieldCount; i++) {
                String key = readUtf8(body, MAX_KEY_BYTES, "field key");
                String value = readUtf8(body, MAX_VALUE_BYTES, "field value");
                if (fields.putIfAbsent(key, value) != null) {
                    throw new ProtocolException("Duplicate field: " + key);
                }
            }
            if (body.available() != 0) {
                throw new ProtocolException("Unexpected trailing bytes in frame");
            }
            return ProtocolMessage.decoded(type, correlationId, fields);
        }
    }

    private static byte[] encodeBody(ProtocolMessage message) throws IOException {
        if (message.fields().size() > MAX_FIELDS) {
            throw new ProtocolException("Too many fields: " + message.fields().size());
        }

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (DataOutputStream body = new DataOutputStream(bytes)) {
            body.writeInt(MAGIC);
            body.writeShort(VERSION);
            body.writeByte(message.type().wireCode());
            body.writeLong(message.correlationId().getMostSignificantBits());
            body.writeLong(message.correlationId().getLeastSignificantBits());
            body.writeShort(message.fields().size());
            for (Map.Entry<String, String> entry : message.fields().entrySet()) {
                writeUtf8(body, entry.getKey(), MAX_KEY_BYTES, "field key");
                writeUtf8(body, entry.getValue(), MAX_VALUE_BYTES, "field value");
            }
        }
        return bytes.toByteArray();
    }

    private static void writeUtf8(
            DataOutputStream output, String value, int maximumBytes, String description) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maximumBytes) {
            throw new ProtocolException(description + " exceeds " + maximumBytes + " bytes");
        }
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readUtf8(
            DataInputStream input, int maximumBytes, String description) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumBytes) {
            throw new ProtocolException("Invalid " + description + " length: " + length);
        }
        byte[] encoded = new byte[length];
        input.readFully(encoded);
        return new String(encoded, StandardCharsets.UTF_8);
    }
}
