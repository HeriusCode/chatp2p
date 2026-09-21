package chatp2p.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** An immutable control-channel message. */
public final class ProtocolMessage {
    private final MessageType type;
    private final UUID correlationId;
    private final Map<String, String> fields;

    private ProtocolMessage(MessageType type, UUID correlationId, Map<String, String> fields) {
        this.type = Objects.requireNonNull(type, "type");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public static Builder builder(MessageType type) {
        return new Builder(type, UUID.randomUUID());
    }

    public static Builder responseTo(ProtocolMessage request, MessageType responseType) {
        Objects.requireNonNull(request, "request");
        return new Builder(responseType, request.correlationId());
    }

    public static ProtocolMessage decoded(
            MessageType type, UUID correlationId, Map<String, String> fields) {
        return new ProtocolMessage(type, correlationId, fields);
    }

    public MessageType type() {
        return type;
    }

    public UUID correlationId() {
        return correlationId;
    }

    public Map<String, String> fields() {
        return fields;
    }

    public String field(String name) {
        return fields.get(name);
    }

    public String requiredField(String name) throws ProtocolException {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            throw new ProtocolException("Missing required field: " + name);
        }
        return value;
    }

    public static final class Builder {
        private final MessageType type;
        private final UUID correlationId;
        private final Map<String, String> fields = new LinkedHashMap<>();

        private Builder(MessageType type, UUID correlationId) {
            this.type = Objects.requireNonNull(type, "type");
            this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        }

        public Builder field(String name, String value) {
            fields.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public ProtocolMessage build() {
            return new ProtocolMessage(type, correlationId, fields);
        }
    }
}

