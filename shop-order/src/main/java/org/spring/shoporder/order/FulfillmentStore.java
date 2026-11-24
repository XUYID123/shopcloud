package org.spring.shoporder.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
class FulfillmentStore {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    FulfillmentStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    LockedOrder lock(String orderNo, Long owner) {
        var query = jdbc.sql("""
                SELECT order_status,inventory_status,payment_status,received_at
                FROM order_main WHERE order_no=:number
                """ + (owner == null ? "" : " AND user_id=:owner") + " FOR UPDATE").param("number", orderNo);
        if (owner != null) query.param("owner", owner);
        return query.query((rs, row) -> new LockedOrder(rs.getString("order_status"),
                        rs.getString("inventory_status"), rs.getString("payment_status"), OrderStore.time(rs, "received_at")))
                .optional().orElse(null);
    }

    String shipmentRequest(String orderNo, long actor, String key) {
        return jdbc.sql("SELECT request_hash FROM order_shipment_request WHERE order_no=? AND actor_id=? AND request_key=?")
                .params(orderNo, actor, key).query(String.class).optional().orElse(null);
    }

    void bindRequest(String orderNo, long actor, String key, String hash) {
        jdbc.sql("INSERT INTO order_shipment_request (order_no,actor_id,request_key,request_hash) VALUES (?,?,?,?)")
                .params(orderNo, actor, key, hash).update();
    }

    void ship(String orderNo, String carrier, String tracking, List<OrderView.BatchAllocation> allocations) {
        final String snapshot;
        try {
            snapshot = json.writeValueAsString(allocations);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot serialize shipment batch snapshot");
        }
        jdbc.sql("""
                INSERT INTO order_shipment (order_no,carrier_code,tracking_no,allocation_snapshot) VALUES (?,?,?,?)
                """).params(orderNo, carrier, tracking, snapshot).update();
        if (jdbc.sql("""
                UPDATE order_main SET order_status='SHIPPED',version=version+1
                WHERE order_no=? AND order_status='PAID' AND inventory_status='CONFIRMED' AND payment_status='SUCCEEDED'
                """).param(orderNo).update() != 1) throw new IllegalStateException("Cannot mark locked order shipped");
    }

    OffsetDateTime receive(String orderNo) {
        if (jdbc.sql("""
                UPDATE order_main SET order_status='COMPLETED',received_at=CURRENT_TIMESTAMP(3),version=version+1
                WHERE order_no=? AND order_status='SHIPPED' AND received_at IS NULL
                """).param(orderNo).update() != 1) throw new IllegalStateException("Cannot mark locked order received");
        return jdbc.sql("SELECT received_at FROM order_main WHERE order_no=?").param(orderNo)
                .query((rs, row) -> OrderStore.time(rs, "received_at")).single();
    }

    record LockedOrder(String status, String inventory, String payment, OffsetDateTime receivedAt) { }
}
