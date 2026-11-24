package org.spring.shopmessaging;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRetryTests {
    @Test
    void boundsLongBrokerRetriesAndSpreadsDifferentEvents() {
        var first = new UUID(0, 0);
        var second = new UUID(0, 3);
        assertThat(JdbcMessageOutbox.retryDelaySeconds(1, first)).isEqualTo(2);
        assertThat(JdbcMessageOutbox.retryDelaySeconds(3, first)).isEqualTo(8);
        assertThat(JdbcMessageOutbox.retryDelaySeconds(4, first)).isEqualTo(16);
        assertThat(JdbcMessageOutbox.retryDelaySeconds(1_000_000, second)).isEqualTo(19);
    }
}
