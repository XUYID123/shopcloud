package org.spring.shopmessaging;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RocketMqIntegrationTests {
    private static final String GROUP = "shop-harness-inbox";
    private static final String TAG = "infra-verification";
    private static JdbcTemplate jdbc;
    private static JdbcMessageInbox inbox;
    private static RocketMqTransport transport;

    @BeforeAll
    static void connectToIsolatedServices() {
        var source = new DriverManagerDataSource(required("HARNESS_MESSAGING_DB_URL"), "harness",
                required("HARNESS_DB_PASSWORD"));
        new ResourceDatabasePopulator(new ClassPathResource("sql/rocketmq-inbox.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE rocketmq_test_effect (event_id CHAR(36) PRIMARY KEY, apply_count INT NOT NULL)");
        inbox = new JdbcMessageInbox(source);
        transport = new RocketMqTransport(ClientConfiguration.newBuilder()
                .setEndpoints(required("HARNESS_ROCKETMQ_ENDPOINT"))
                .setRequestTimeout(Duration.ofSeconds(5))
                .enableSsl(false)
                .build(), "shop-harness-events");
    }

    @Test
    void duplicateBrokerDeliveriesMakeOneDatabaseChange() throws Exception {
        MessageEvent event = newEvent();
        var deliveries = new CountDownLatch(2);
        var received = new AtomicReference<MessageEvent>();
        try (var consumer = transport.subscribe(GROUP, TAG, incoming -> {
            if (incoming.eventId().equals(event.eventId())) {
                inbox.consumeOnce(GROUP, incoming.eventId(), () -> apply(incoming.eventId()));
                received.set(incoming);
                deliveries.countDown();
            }
        }); var producer = transport.openProducer()) {
            String first = transport.send(producer, TAG, event);
            String second = transport.send(producer, TAG, event);
            assertThat(first).isNotBlank().isNotEqualTo(second);
            assertThat(deliveries.await(50, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(received.get().body()).isEqualTo(event.body());
        assertThat(received.get().traceId()).isEqualTo(event.traceId());
        assertThat(received.get().schemaVersion()).isEqualTo(event.schemaVersion());
        assertThat(effectCount(event.eventId())).isEqualTo(1);
    }

    @Test
    void brokerRedeliversAfterConsumerTransactionRollsBack() throws Exception {
        MessageEvent event = newEvent();
        var attempts = new AtomicInteger();
        var rolledBack = new AtomicBoolean();
        var committed = new CountDownLatch(1);
        try (var consumer = transport.subscribe(GROUP, TAG, incoming -> {
            if (!incoming.eventId().equals(event.eventId())) {
                return;
            }
            int attempt = attempts.incrementAndGet();
            try {
                inbox.consumeOnce(GROUP, incoming.eventId(), () -> {
                    apply(incoming.eventId());
                    if (attempt == 1) {
                        throw new IllegalStateException("simulated transient failure");
                    }
                });
                committed.countDown();
            } catch (IllegalStateException transientFailure) {
                int receipts = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rocketmq_inbox WHERE consumer_group=? AND event_id=?",
                        Integer.class, GROUP, incoming.eventId().toString());
                rolledBack.set(effectCount(incoming.eventId()) == 0 && receipts == 0);
                throw transientFailure;
            }
        }); var producer = transport.openProducer()) {
            assertThat(transport.send(producer, TAG, event)).isNotBlank();
            assertThat(committed.await(75, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(rolledBack).isTrue();
        assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
        assertThat(effectCount(event.eventId())).isEqualTo(1);
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + key + ". Run infra/quality/gate.py full.");
        }
        return value;
    }

    private static MessageEvent newEvent() {
        return new MessageEvent(UUID.randomUUID(), 1, "mq-integration", "probe".getBytes(StandardCharsets.UTF_8));
    }

    private static void apply(UUID eventId) {
        jdbc.update("INSERT INTO rocketmq_test_effect VALUES (?, 1) ON DUPLICATE KEY UPDATE apply_count=apply_count+1",
                eventId.toString());
    }

    private static int effectCount(UUID eventId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(apply_count), 0) FROM rocketmq_test_effect WHERE event_id=?",
                Integer.class, eventId.toString());
    }
}
