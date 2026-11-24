package org.spring.shoporder.order;

import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

record OrderCursor(Instant createdAt, long id) {
    static OrderCursor parse(String token, long userId, String state) {
        if (token == null) return null;
        try {
            if (!token.matches("[A-Za-z0-9_-]{1,160}")) throw invalid();
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII);
            String[] fields = decoded.split(":", -1);
            if (fields.length != 4 || !fields[0].equals(Long.toString(userId)) || !fields[1].equals(filter(state))) {
                throw invalid();
            }
            long time = Long.parseLong(fields[2]);
            long id = Long.parseLong(fields[3]);
            if (time < 0 || time > 253_402_300_799_999L || id <= 0) throw invalid();
            return new OrderCursor(Instant.ofEpochMilli(time), id);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
    }

    String encode(long userId, String state) {
        String value = userId + ":" + filter(state) + ":" + createdAt.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static String filter(String state) { return state == null ? "-" : state; }

    private static BusinessException invalid() { return new BusinessException(ErrorCode.VALIDATION_ERROR); }
}
