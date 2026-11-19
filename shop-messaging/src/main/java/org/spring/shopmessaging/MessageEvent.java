package org.spring.shopmessaging;

import java.util.Objects;
import java.util.UUID;

public record MessageEvent(UUID eventId, int schemaVersion, String traceId, byte[] body) {
    public MessageEvent {
        Objects.requireNonNull(eventId, "eventId");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (traceId != null && !traceId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid traceId");
        }
        if (body == null || body.length == 0 || body.length > 256 * 1024) {
            throw new IllegalArgumentException("Message body length must be between 1 and 262144 bytes");
        }
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
