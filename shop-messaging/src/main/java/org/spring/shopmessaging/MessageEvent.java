package org.spring.shopmessaging;

import java.util.Objects;
import java.util.UUID;

public record MessageEvent(UUID eventId, int schemaVersion, String traceId, byte[] body) {
    static final int MAX_BODY_BYTES = 1024 * 1024;

    public MessageEvent {
        Objects.requireNonNull(eventId, "eventId");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (traceId != null && !traceId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid traceId");
        }
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Message body length outside supported range");
        }
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
