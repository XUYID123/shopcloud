package org.spring.shoppay.payment;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
class PaymentStore {
    private static final String CONTROL = """
            SELECT order_no,user_id,amount_fen,currency,state,close_requested,refund_status FROM payment_control
            """;
    private static final String SESSION = """
            SELECT s.payment_no,s.order_no,s.status,s.expires_at,s.provider_transaction_no,s.paid_at,
                   c.user_id,c.amount_fen,c.currency
            FROM payment_session s JOIN payment_control c ON c.order_no=s.order_no
            """;
    private final JdbcClient jdbc;

    PaymentStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    Control claim(String orderNo, long user, long amount, String currency) {
        jdbc.sql("""
                INSERT INTO payment_control(order_no,user_id,amount_fen,currency) VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE order_no=payment_control.order_no
                """).params(orderNo, user, amount, currency).update();
        return lock(orderNo);
    }

    Control lock(String orderNo) {
        return jdbc.sql(CONTROL + " WHERE order_no=? FOR UPDATE").param(orderNo)
                .query(this::mapControl).optional().orElse(null);
    }

    Control control(String orderNo) {
        return jdbc.sql(CONTROL + " WHERE order_no=?").param(orderNo).query(this::mapControl).optional().orElse(null);
    }

    private Control mapControl(ResultSet rs, int row) throws SQLException {
        return new Control(rs.getString("order_no"), rs.getLong("user_id"), rs.getLong("amount_fen"),
                rs.getString("currency"), rs.getString("state"), rs.getBoolean("close_requested"),
                rs.getString("refund_status"));
    }

    Session byNumber(String paymentNo) {
        return jdbc.sql(SESSION + " WHERE s.payment_no=?").param(paymentNo).query(this::mapSession).optional().orElse(null);
    }

    Session byOrder(String orderNo) {
        return jdbc.sql(SESSION + " WHERE s.order_no=?").param(orderNo).query(this::mapSession).optional().orElse(null);
    }

    Session byRequest(long user, String requestKey) {
        return jdbc.sql(SESSION + " JOIN payment_request r ON r.payment_no=s.payment_no WHERE r.user_id=? AND r.request_key=?")
                .params(user, requestKey).query(this::mapSession).optional().orElse(null);
    }

    private Session mapSession(ResultSet rs, int row) throws SQLException {
        var paid = rs.getTimestamp("paid_at");
        return new Session(rs.getString("payment_no"), rs.getString("order_no"), rs.getLong("user_id"),
                rs.getString("status"), rs.getLong("amount_fen"), rs.getString("currency"),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("provider_transaction_no"),
                paid == null ? null : paid.toInstant());
    }

    void create(String paymentNo, String orderNo, Instant expires) {
        jdbc.sql("INSERT INTO payment_session(payment_no,order_no,status,expires_at) VALUES (?,?,'PENDING',?)")
                .params(paymentNo, orderNo, Timestamp.from(expires)).update();
    }

    void bind(long user, String key, String paymentNo) {
        jdbc.sql("INSERT INTO payment_request(user_id,request_key,payment_no) VALUES (?,?,?)")
                .params(user, key, paymentNo).update();
    }

    void markCloseRequested(String orderNo) {
        jdbc.sql("UPDATE payment_control SET close_requested=TRUE WHERE order_no=?").param(orderNo).update();
    }

    void close(String orderNo) {
        jdbc.sql("UPDATE payment_session SET status='CLOSED' WHERE order_no=? AND status='PENDING'").param(orderNo).update();
        jdbc.sql("UPDATE payment_control SET state='CLOSED',close_requested=TRUE WHERE order_no=?").param(orderNo).update();
    }

    void paid(String orderNo, String paymentNo, String txn, Instant paidAt, boolean late) {
        jdbc.sql("UPDATE payment_session SET status='SUCCEEDED',provider_transaction_no=?,paid_at=? WHERE payment_no=?")
                .params(txn, Timestamp.from(paidAt), paymentNo).update();
        if (!late) jdbc.sql("UPDATE payment_control SET state='PAID' WHERE order_no=?").param(orderNo).update();
    }

    String callbackDigest(String eventId) {
        return jdbc.sql("SELECT payload_hash FROM callback_inbox WHERE event_id=?").param(eventId)
                .query(String.class).optional().orElse(null);
    }

    String nonceEvent(String nonce) {
        return jdbc.sql("SELECT event_id FROM callback_nonce WHERE nonce=?").param(nonce)
                .query(String.class).optional().orElse(null);
    }

    void storeCallback(String id, String digest, String paymentNo) {
        jdbc.sql("INSERT INTO callback_inbox(event_id,payload_hash,payment_no) VALUES (?,?,?)")
                .params(id, digest, paymentNo).update();
    }

    void storeNonce(String nonce, String eventId) {
        jdbc.sql("INSERT INTO callback_nonce(nonce,event_id) VALUES (?,?)").params(nonce, eventId).update();
    }

    MockEvent mockEvent(String paymentNo) {
        return jdbc.sql("SELECT event_id,payment_no,body,created_at,trace_id FROM mock_provider_event WHERE payment_no=?")
                .param(paymentNo).query(this::mapEvent).optional().orElse(null);
    }

    List<MockEvent> dueCallbacks() {
        return jdbc.sql("""
                SELECT event_id,payment_no,body,created_at,trace_id FROM mock_provider_event
                WHERE delivered_at IS NULL AND available_at<=CURRENT_TIMESTAMP(3)
                ORDER BY available_at,event_id LIMIT 8
                """).query(this::mapEvent).list();
    }

    private MockEvent mapEvent(ResultSet rs, int row) throws SQLException {
        return new MockEvent(rs.getString("event_id"), rs.getString("payment_no"), rs.getBytes("body"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("trace_id"));
    }

    void insertMockEvent(MockEvent event) {
        jdbc.sql("INSERT INTO mock_provider_event(event_id,payment_no,body,created_at,trace_id) VALUES (?,?,?,?,?)")
                .param(event.id()).param(event.paymentNo()).param(event.body())
                .param(Timestamp.from(event.createdAt())).param(event.traceId()).update();
    }

    void delivered(String id) {
        jdbc.sql("UPDATE mock_provider_event SET delivered_at=CURRENT_TIMESTAMP(3) WHERE event_id=? AND delivered_at IS NULL")
                .param(id).update();
    }

    void callbackRetry(String id) {
        jdbc.sql("""
                UPDATE mock_provider_event SET available_at=TIMESTAMPADD(SECOND,LEAST(60,2+attempts),CURRENT_TIMESTAMP(3)),
                    attempts=LEAST(attempts+1,1000000) WHERE event_id=? AND delivered_at IS NULL
                """).param(id).update();
    }

    String refundNumber(String orderNo) {
        return jdbc.sql("SELECT refund_no FROM payment_refund WHERE order_no=?").param(orderNo)
                .query(String.class).optional().orElse(null);
    }

    void refund(String orderNo, String paymentNo, String refundNo, long amount, String reason, Instant when) {
        jdbc.sql("INSERT INTO payment_refund(refund_no,order_no,payment_no,amount_fen,reason,refunded_at) VALUES (?,?,?,?,?,?)")
                .params(refundNo, orderNo, paymentNo, amount, reason, Timestamp.from(when)).update();
        jdbc.sql("UPDATE payment_control SET refund_status='SUCCEEDED' WHERE order_no=?").param(orderNo).update();
    }

    record Control(String orderNo, long userId, long amountFen, String currency, String state,
                   boolean closeRequested, String refundStatus) { }
    record Session(String paymentNo, String orderNo, long userId, String status, long amountFen, String currency,
                   Instant expiry, String txn, Instant paidAt) { }
    record MockEvent(String id, String paymentNo, byte[] body, Instant createdAt, String traceId) { }
}
