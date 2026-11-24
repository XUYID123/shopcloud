package org.spring.shopinventory.reservation;

import org.spring.shopmessaging.trade.TradeEvents;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

@Repository
class ReservationStore {
    private static final String WAREHOUSE = TradeEvents.WAREHOUSE;
    private final JdbcClient jdbc;

    ReservationStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    Reservation lock(String orderNo) {
        jdbc.sql("""
                INSERT INTO inventory_reservation(order_no,warehouse_id,state) VALUES (?,?,'PENDING')
                ON DUPLICATE KEY UPDATE order_no=inventory_reservation.order_no
                """).params(orderNo, WAREHOUSE).update();
        return jdbc.sql("SELECT state,request_hash,failure_code FROM inventory_reservation WHERE order_no=? FOR UPDATE")
                .param(orderNo).query(this::reservation).single();
    }

    Reservation find(String orderNo) {
        return jdbc.sql("SELECT state,request_hash,failure_code FROM inventory_reservation WHERE order_no=?")
                .param(orderNo).query(this::reservation).optional().orElse(null);
    }

    private Reservation reservation(ResultSet rs, int row) throws SQLException {
        return new Reservation(rs.getString("state"), rs.getString("request_hash"), rs.getString("failure_code"));
    }

    void update(String orderNo, String state, String hash, String failureCode) {
        requireOne(jdbc.sql("UPDATE inventory_reservation SET state=?,request_hash=?,failure_code=? WHERE order_no=?")
                .param(state).param(hash).param(failureCode).param(orderNo).update());
    }

    Long lockStock(long skuId) {
        return jdbc.sql("SELECT available FROM sku_stock WHERE sku_id=? AND warehouse_id=? FOR UPDATE")
                .params(skuId, WAREHOUSE).query(Long.class).optional().orElse(null);
    }

    List<Batch> sellableBatches(long skuId, LocalDate today) {
        return jdbc.sql("""
                SELECT batch_id,batch_no,available,expires_on,quality_status FROM stock_batch
                WHERE sku_id=? AND warehouse_id=? AND quality_status='SELLABLE' AND expires_on>? AND available>0
                ORDER BY expires_on,batch_id LIMIT 100 FOR UPDATE
                """).params(skuId, WAREHOUSE, today).query(this::batch).list();
    }

    Batch lockBatch(long skuId, long batchId) {
        return jdbc.sql("""
                SELECT batch_id,batch_no,available,expires_on,quality_status FROM stock_batch
                WHERE batch_id=? AND sku_id=? AND warehouse_id=? FOR UPDATE
                """).params(batchId, skuId, WAREHOUSE).query(this::batch).single();
    }

    private Batch batch(ResultSet rs, int row) throws SQLException {
        return new Batch(rs.getLong("batch_id"), rs.getString("batch_no"), rs.getLong("available"),
                rs.getObject("expires_on", LocalDate.class), rs.getString("quality_status"));
    }

    void reserveSku(long sku, int quantity) {
        requireOne(jdbc.sql("""
                UPDATE sku_stock SET available=available-?,reserved=reserved+?,version=version+1
                WHERE sku_id=? AND warehouse_id=? AND available>=?
                """).params(quantity, quantity, sku, WAREHOUSE, quantity).update());
    }

    void reserveBatch(String orderNo, long sku, Batch batch, int quantity) {
        requireOne(jdbc.sql("""
                UPDATE stock_batch SET available=available-?,reserved=reserved+?,version=version+1
                WHERE batch_id=? AND available>=?
                """).params(quantity, quantity, batch.id(), quantity).update());
        jdbc.sql("INSERT INTO reservation_item(order_no,sku_id,batch_id,quantity) VALUES (?,?,?,?)")
                .params(orderNo, sku, batch.id(), quantity).update();
        ledger(orderNo, batch, "RESERVE", -quantity);
    }

    List<Item> items(String orderNo) {
        return jdbc.sql("""
                SELECT sku_id,batch_id,quantity FROM reservation_item WHERE order_no=? ORDER BY sku_id,batch_id
                """).param(orderNo).query((rs, row) -> new Item(rs.getLong("sku_id"), rs.getLong("batch_id"),
                rs.getInt("quantity"))).list();
    }

    void releaseBatch(String orderNo, Item item, Batch batch, boolean sellable) {
        int quantity = item.quantity();
        int available = sellable ? quantity : 0;
        int unavailable = quantity - available;
        requireOne(jdbc.sql("""
                UPDATE stock_batch SET reserved=reserved-?,available=available+?,unavailable=unavailable+?,version=version+1
                WHERE batch_id=? AND reserved>=?
                """).params(quantity, available, unavailable, batch.id(), quantity).update());
        requireOne(jdbc.sql("""
                UPDATE sku_stock SET reserved=reserved-?,available=available+?,unavailable=unavailable+?,version=version+1
                WHERE sku_id=? AND warehouse_id=? AND reserved>=?
                """).params(quantity, available, unavailable, item.skuId(), WAREHOUSE, quantity).update());
        ledger(orderNo, batch, "RELEASE", available);
    }

    String confirmedPayment(String orderNo) {
        return jdbc.sql("SELECT payment_no FROM inventory_reservation WHERE order_no=?").param(orderNo)
                .query((rs, row) -> rs.getString(1)).optional().orElse(null);
    }

    void confirm(String orderNo, String paymentNo) {
        requireOne(jdbc.sql("""
                UPDATE inventory_reservation SET state='CONFIRMED',payment_no=?
                WHERE order_no=? AND state='RESERVED'
                """).params(paymentNo, orderNo).update());
    }

    void confirmBatch(String orderNo, Item item, Batch batch) {
        int quantity = item.quantity();
        requireOne(jdbc.sql("""
                UPDATE stock_batch SET reserved=reserved-?,committed=committed+?,version=version+1
                WHERE batch_id=? AND reserved>=?
                """).params(quantity, quantity, batch.id(), quantity).update());
        requireOne(jdbc.sql("""
                UPDATE sku_stock SET reserved=reserved-?,committed=committed+?,version=version+1
                WHERE sku_id=? AND warehouse_id=? AND reserved>=?
                """).params(quantity, quantity, item.skuId(), WAREHOUSE, quantity).update());
        ledger(orderNo, batch, "CONFIRM", 0);
    }

    private void ledger(String orderNo, Batch batch, String reason, int delta) {
        // Actor 0 denotes the internal inventory worker rather than an administrator.
        jdbc.sql("""
                INSERT INTO stock_ledger(batch_id,operation_no,reason,actor_id,delta_available,available_before,available_after)
                VALUES (?,?,?,0,?,?,?)
                """).params(batch.id(), orderNo, reason, delta, batch.available(), batch.available() + delta).update();
    }

    List<ReservationViews.BatchAllocation> allocations(String orderNo) {
        return jdbc.sql("""
                SELECT i.sku_id,b.batch_id,b.batch_no,i.quantity,b.expires_on,b.quality_status
                FROM reservation_item i JOIN stock_batch b ON b.batch_id=i.batch_id
                WHERE i.order_no=? ORDER BY i.sku_id,b.expires_on,b.batch_id
                """).param(orderNo).query((rs, row) -> new ReservationViews.BatchAllocation(
                rs.getString("sku_id"), rs.getString("batch_id"), rs.getString("batch_no"), rs.getInt("quantity"),
                rs.getObject("expires_on", LocalDate.class), rs.getString("quality_status"))).list();
    }

    private static void requireOne(int count) {
        if (count != 1) throw new IllegalStateException("Inconsistent inventory balance");
    }

    record Reservation(String state, String hash, String failureCode) { }
    record Item(long skuId, long batchId, int quantity) { }
    record Batch(long id, String number, long available, LocalDate expiresOn, String qualityStatus) {
        boolean sellable(LocalDate today) { return "SELLABLE".equals(qualityStatus) && expiresOn.isAfter(today); }
    }
}
