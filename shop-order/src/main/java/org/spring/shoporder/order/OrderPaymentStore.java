package org.spring.shoporder.order;

import org.spring.shopcommon.checkout.PaymentContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@Repository
class OrderPaymentStore {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String ORDER = """
            SELECT order_no,user_id,payable_amount_fen,currency,order_status,inventory_status,
                   payment_status,payment_no,refund_no,expires_at FROM order_main
            """;
    private final JdbcClient jdbc;

    OrderPaymentStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    PaymentContext context(String orderNo, long userId) {
        return jdbc.sql(ORDER + " WHERE order_no=? AND user_id=?").params(orderNo, userId)
                .query(this::row).optional().map(order -> new PaymentContext(order.number(), Long.toString(order.user()),
                        order.amount(), order.currency(), order.status(), order.expiry())).orElse(null);
    }

    Row lock(String number) {
        return jdbc.sql(ORDER + " WHERE order_no=? FOR UPDATE").param(number).query(this::row).optional().orElse(null);
    }

    Row due(boolean unpaid) {
        String condition = unpaid
                ? " WHERE order_status='UNPAID' AND expires_at<=CURRENT_TIMESTAMP(3) ORDER BY expires_at,id"
                : " WHERE order_status='PREPARING' AND created_at<=TIMESTAMPADD(SECOND,-120,CURRENT_TIMESTAMP(3)) ORDER BY created_at,id";
        return jdbc.sql(ORDER + condition + " LIMIT 1 FOR UPDATE SKIP LOCKED")
                .query(this::row).optional().orElse(null);
    }

    private Row row(ResultSet rs, int index) throws SQLException {
        return new Row(rs.getString("order_no"), rs.getLong("user_id"), rs.getLong("payable_amount_fen"),
                rs.getString("currency"), rs.getString("order_status"), rs.getString("inventory_status"),
                rs.getString("payment_status"), rs.getString("payment_no"), rs.getString("refund_no"), time(rs, "expires_at"));
    }

    void requestCancel(String id, String number, long user, String key, String reason) {
        jdbc.sql("""
                INSERT INTO order_cancellation(cancellation_no,order_no,user_id,request_key,reason,status)
                VALUES (?,?,?,?,?,'PENDING')
                """).param(id).param(number).param(user).param(key).param(reason).update();
    }

    CancellationRow cancellation(String number, long user) {
        return jdbc.sql("""
                SELECT cancellation_no,order_no,request_key,status,reason,requested_at,completed_at,failure_code
                FROM order_cancellation WHERE order_no=? AND user_id=?
                """).params(number, user).query((rs, index) -> new CancellationRow(rs.getString("request_key"),
                new OrderPaymentLifecycle.Cancellation(
                rs.getString("cancellation_no"), rs.getString("order_no"), rs.getString("status"), rs.getString("reason"),
                time(rs, "requested_at"), time(rs, "completed_at"), rs.getString("failure_code"), "/api/v1/orders/" + number)))
                .optional().orElse(null);
    }

    void startClose(String number, String reason) {
        jdbc.sql("""
                UPDATE order_main SET order_status='CLOSING',close_reason=?,version=version+1 WHERE order_no=?
                """).params(reason, number).update();
    }

    void completeClose(String number, String paymentNo) {
        jdbc.sql("""
                UPDATE order_main SET order_status='CLOSED',payment_status='CLOSED',payment_no=?,
                    inventory_status='RELEASE_PENDING',version=version+1 WHERE order_no=?
                """).param(paymentNo).param(number).update();
        completeCancel(number, "SUCCEEDED", null);
    }

    void completePaid(String number, String paymentNo, Instant time) {
        jdbc.sql("""
                UPDATE order_main SET order_status='PAID',payment_status='SUCCEEDED',payment_no=?,paid_at=?,
                    version=version+1 WHERE order_no=?
                """).params(paymentNo, Timestamp.from(time), number).update();
        completeCancel(number, "REJECTED", "ALREADY_PAID");
    }

    private void completeCancel(String number, String state, String failureCode) {
        jdbc.sql("""
                UPDATE order_cancellation SET status=?,failure_code=?,completed_at=CURRENT_TIMESTAMP(3)
                WHERE order_no=? AND status='PENDING'
                """).param(state).param(failureCode).param(number).update();
    }

    void setInventory(String number, String state) {
        jdbc.sql("UPDATE order_main SET inventory_status=?,version=version+1 WHERE order_no=?").params(state, number).update();
    }

    void startRefund(String number, String paymentNo, String refundNo, Instant paidAt, String reason, boolean inventoryFailure) {
        jdbc.sql("""
                UPDATE order_main SET payment_status='REFUNDING',payment_no=?,refund_no=?,paid_at=?,exception_code=?,
                    inventory_status=CASE WHEN ? THEN 'EXCEPTION' ELSE inventory_status END,version=version+1
                WHERE order_no=?
                """).params(paymentNo, refundNo, Timestamp.from(paidAt), reason, inventoryFailure, number).update();
    }

    Instant paidAt(String number) {
        return jdbc.sql("SELECT paid_at FROM order_main WHERE order_no=?").param(number)
                .query((rs, index) -> rs.getTimestamp(1).toInstant()).single();
    }

    void finishRefund(String number, boolean succeeded) {
        jdbc.sql("UPDATE order_main SET payment_status=?,version=version+1 WHERE order_no=?")
                .params(succeeded ? "REFUNDED" : "EXCEPTION", number).update();
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : OffsetDateTime.ofInstant(value.toInstant(), ZONE);
    }

    record Row(String number, long user, long amount, String currency, String status, String inventory,
               String paymentStatus, String paymentNo, String refundNo, OffsetDateTime expiry) { }
    record CancellationRow(String key, OrderPaymentLifecycle.Cancellation view) { }
}
