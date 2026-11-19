package org.spring.shopmessaging;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageEventTests {
    @Test
    void envelopeOwnsItsBodyAndRejectsInvalidMetadata() {
        byte[] body = {1, 2};
        UUID id = UUID.randomUUID();
        var event = new MessageEvent(id, 1, "trace-1", body);
        body[0] = 3;
        event.body()[1] = 4;
        assertThat(event.body()).containsExactly(1, 2);
        assertThatThrownBy(() -> new MessageEvent(id, 0, null, body)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageEvent(id, 1, "invalid\ntrace", body)).isInstanceOf(IllegalArgumentException.class);
    }
}
