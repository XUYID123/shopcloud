package org.spring.shopinventory.stock;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/** 仅操作库存服务自己的库。所有写方法由入库事务统一调用。 */
@Repository
public class StockBatchStore {
    private final JdbcClient jdbc;

    public StockBatchStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    ReceiptBinding findRequest(long actor, String key) {
        return request(actor, key, false);
    }

    ReceiptBinding lockRequest(long actor, String key) {
        return request(actor, key, true);
    }

    private ReceiptBinding request(long actor, String key, boolean lock) {
        // 查询按幂等表联合主键定位。只有事务内竞争同键时使用当前读。
        String sql = "SELECT request_hash,batch_id FROM stock_batch_receipt WHERE actor_id=? AND request_key=?";
        return jdbc.sql(lock ? sql + " FOR UPDATE" : sql).params(actor, key)
                .query((rs, row) -> new ReceiptBinding(rs.getString("request_hash"), rs.getObject("batch_id", Long.class)))
                .optional().orElse(null);
    }

    void claimRequest(long actor, String key, String hash) {
        jdbc.sql("""
                INSERT INTO stock_batch_receipt(actor_id,request_key,request_hash) VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE actor_id=stock_batch_receipt.actor_id
                """).params(actor, key, hash).update();
    }

    void addSkuStock(long skuId, String warehouse, int quantity) {
        jdbc.sql("""
                INSERT INTO sku_stock(sku_id,warehouse_id,available) VALUES (?,?,?) AS incoming
                ON DUPLICATE KEY UPDATE available=sku_stock.available + incoming.available, version=sku_stock.version + 1
                """).params(skuId, warehouse, quantity).update();
    }

    long insertBatch(long skuId, String warehouse, String batchNo, LocalDate made, LocalDate expiry, int quantity) {
        var generated = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO stock_batch(sku_id,warehouse_id,batch_no,manufactured_on,expires_on,initial_quantity,available)
                VALUES (?,?,?,?,?,?,?)
                """).params(skuId, warehouse, batchNo, made, expiry, quantity, quantity).update(generated, "batch_id");
        var batchId = generated.getKey();
        if (batchId == null) throw new IllegalStateException("Missing generated stock batch ID");
        return batchId.longValue();
    }

    void insertLedger(long batchId, long actor, int quantity) {
        jdbc.sql("""
                INSERT INTO stock_ledger(batch_id,operation_no,reason,actor_id,delta_available,available_before,available_after)
                VALUES (?,?,'RECEIPT',?,?,0,?)
                """).params(batchId, "receipt:" + batchId, actor, quantity, quantity).update();
    }

    void completeRequest(long actor, String key, long batchId) {
        int count = jdbc.sql("""
                UPDATE stock_batch_receipt SET batch_id=?, updated_at=CURRENT_TIMESTAMP(3)
                WHERE actor_id=? AND request_key=? AND batch_id IS NULL
                """).params(batchId, actor, key).update();
        if (count != 1) throw new IllegalStateException("Missing pending stock receipt");
    }

    StockBatch loadInitialBatch(long batchId) {
        // 初次入库的批号、日期、数量组成快照，使用初始值重放且不受后续库存余额影响。
        return jdbc.sql("""
                SELECT batch_id,sku_id,warehouse_id,batch_no,manufactured_on,expires_on,initial_quantity
                FROM stock_batch WHERE batch_id=?
                """).param(batchId).query((rs, row) -> StockBatch.received(rs.getLong("batch_id"), rs.getLong("sku_id"),
                rs.getString("warehouse_id"), rs.getString("batch_no"), rs.getString("manufactured_on"),
                rs.getString("expires_on"), rs.getLong("initial_quantity"))).optional().orElse(null);
    }

    record ReceiptBinding(String requestHash, Long batchId) { }
}
