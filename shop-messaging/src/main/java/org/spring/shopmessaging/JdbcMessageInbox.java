package org.spring.shopmessaging;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.UUID;

/** Performs a database side effect once per consumer group and stable event ID. */
public final class JdbcMessageInbox {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcMessageInbox(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean consumeOnce(String group, UUID eventId, Runnable sideEffect) {
        RocketMqTransport.requireName(group, "group");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sideEffect, "sideEffect");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            try {
                jdbc.update("INSERT INTO rocketmq_inbox (consumer_group, event_id) VALUES (?, ?)",
                        group, eventId.toString());
            } catch (DuplicateKeyException duplicate) {
                return false;
            }
            sideEffect.run();
            return true;
        }));
    }
}
