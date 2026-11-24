package org.spring.shopmessaging;

import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.function.Supplier;

/** Business writes enqueue on their existing transaction. The scheduled relay sends outside DB locks. */
public final class JdbcMessageOutbox {
    private static final Logger log = LoggerFactory.getLogger(JdbcMessageOutbox.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcMessageOutbox(DataSource dataSource) {
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public void enqueue(String tag, MessageEvent event) {
        jdbc.sql("""
                INSERT INTO rocketmq_outbox (event_id,tag,schema_version,trace_id,body) VALUES (?,?,?,?,?)
                """).param(event.eventId().toString()).param(RocketMqTransport.requireName(tag, "tag"))
                .param(event.schemaVersion()).param(event.traceId()).param(event.body()).update();
    }

    public void relay(RocketMqTransport transport, Supplier<Producer> producer) {
        relay((tag, event) -> transport.send(producer.get(), tag, event));
    }

    public void relay(Sender sender) {
        for (int sent = 0; sent < 8 && !Thread.currentThread().isInterrupted(); sent++) {
            Due row = transactions.execute(status -> claim());
            if (row == null) return;
            String id = row.event().eventId().toString();
            final String receipt;
            try {
                receipt = sender.send(row.tag(), row.event());
            } catch (ClientException | RuntimeException failure) {
                String kind = failure.getClass().getSimpleName();
                int delaySeconds = retryDelaySeconds(row.attempts(), row.event().eventId());
                jdbc.sql("""
                        UPDATE rocketmq_outbox SET available_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)),
                            last_error=?, lease_token=NULL
                        WHERE event_id=? AND lease_token=? AND sent_at IS NULL
                        """).params(delaySeconds, kind, id, row.lease()).update();
                log.warn("event=OUTBOX_RETRY eventId={} tag={} traceId={} attempt={} errorType={}",
                        id, row.tag(), row.event().traceId(), row.attempts(), kind);
                continue;
            }
            jdbc.sql("""
                    UPDATE rocketmq_outbox SET sent_at=CURRENT_TIMESTAMP(3), broker_message_id=?, lease_token=NULL
                    WHERE event_id=? AND lease_token=? AND sent_at IS NULL
                    """).params(receipt, id, row.lease()).update();
        }
    }

    static int retryDelaySeconds(int attempts, UUID eventId) {
        int delay = 1 << Math.min(attempts, 4);
        // Keep retries within the order's preparation budget and spread events during extended outages.
        return attempts < 4 ? delay : delay + Math.floorMod(eventId.hashCode(), 4);
    }

    private Due claim() {
        String lease = UUID.randomUUID().toString();
        Due row = jdbc.sql("""
                SELECT event_id,tag,schema_version,trace_id,body,attempts
                FROM rocketmq_outbox WHERE sent_at IS NULL AND available_at <= CURRENT_TIMESTAMP(3)
                ORDER BY available_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).query((rs, index) -> new Due(rs.getString("tag"),
                new MessageEvent(UUID.fromString(rs.getString("event_id")), rs.getInt("schema_version"),
                        rs.getString("trace_id"), rs.getBytes("body")), rs.getInt("attempts") + 1, lease))
                .optional().orElse(null);
        if (row != null) {
            jdbc.sql("""
                    UPDATE rocketmq_outbox SET lease_token=?, available_at=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)),
                        attempts=LEAST(attempts + 1,1000000) WHERE event_id=?
                    """).params(lease, row.event().eventId().toString()).update();
        }
        return row;
    }

    private record Due(String tag, MessageEvent event, int attempts, String lease) { }

    @FunctionalInterface
    public interface Sender {
        String send(String tag, MessageEvent event) throws ClientException;
    }
}
