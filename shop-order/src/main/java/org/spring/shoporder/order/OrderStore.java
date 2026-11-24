package org.spring.shoporder.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.spring.shopcommon.checkout.ProductQuote;
import org.spring.shoporder.checkout.CheckoutQuote;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Repository
class OrderStore {
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final TypeReference<List<QuotedItem>> ITEMS = new TypeReference<>() { };
    private static final TypeReference<List<OrderView.BatchAllocation>> ALLOCATIONS = new TypeReference<>() { };
    private static final String ORDER_COLUMNS = """
            SELECT id,o.order_no,request_hash,order_status,inventory_status,payment_status,currency,
                   goods_amount_fen,freight_amount_fen,payable_amount_fen,total_weight_gram,address_snapshot,
                   item_snapshot,created_at,expires_at,paid_at,payment_no,failure_code,exception_code,
                   s.carrier_code,s.tracking_no,s.shipped_at,s.allocation_snapshot
            FROM order_main o LEFT JOIN order_shipment s ON s.order_no=o.order_no
            """;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    OrderStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Quote quote(long owner, String id, boolean lock) {
        return jdbc.sql("""
                SELECT quote_id,address_id,address_snapshot,item_snapshot,currency,goods_amount_fen,
                       freight_amount_fen,payable_amount_fen,total_weight_gram,price_hash,consumed_order_no,
                       expires_at <= CURRENT_TIMESTAMP(3) AS expired
                FROM checkout_quote WHERE quote_id=? AND user_id=?
                """ + (lock ? " FOR UPDATE" : "")).params(id, owner)
                .query((rs, row) -> new Quote(rs.getString("quote_id"), rs.getLong("address_id"),
                        rs.getString("address_snapshot"), rs.getString("item_snapshot"), rs.getString("currency"),
                        rs.getLong("goods_amount_fen"), rs.getLong("freight_amount_fen"), rs.getLong("payable_amount_fen"),
                        rs.getLong("total_weight_gram"), rs.getString("price_hash"), rs.getString("consumed_order_no"),
                        rs.getBoolean("expired"))).optional().orElse(null);
    }

    void lockQuota(long userId) {
        jdbc.sql("""
                INSERT INTO user_order_quota (user_id) VALUES (?) ON DUPLICATE KEY UPDATE user_id=user_order_quota.user_id
                """).param(userId).update();
    }

    boolean takeQuota(long userId) {
        return jdbc.sql("UPDATE user_order_quota SET active_count=active_count+1 WHERE user_id=? AND active_count<5")
                .param(userId).update() == 1;
    }

    void returnQuota(long userId) {
        if (jdbc.sql("UPDATE user_order_quota SET active_count=active_count-1 WHERE user_id=? AND active_count>0")
                .param(userId).update() != 1) {
            throw new IllegalStateException("Missing active order quota");
        }
    }

    void create(String orderNo, long userId, String key, String hash, Quote quote) {
        jdbc.sql("""
                INSERT INTO order_main (order_no,user_id,quote_id,idempotency_key,request_hash,order_status,
                inventory_status,payment_status,currency,goods_amount_fen,freight_amount_fen,payable_amount_fen,
                total_weight_gram,address_snapshot,item_snapshot)
                VALUES (?,?,?,?,?,'PREPARING','PENDING','NONE',?,?,?,?,?,?,?)
                """).params(orderNo, userId, quote.id(), key, hash, quote.currency(), quote.goods(),
                quote.freight(), quote.payable(), quote.weight(), quote.addressSnapshot(), quote.itemSnapshot()).update();
        if (jdbc.sql("UPDATE checkout_quote SET consumed_order_no=? WHERE quote_id=? AND consumed_order_no IS NULL")
                .params(orderNo, quote.id()).update() != 1) {
            throw new IllegalStateException("Cannot mark quote consumed");
        }
    }

    Stored replay(long userId, String key) {
        return jdbc.sql(ORDER_COLUMNS + " WHERE user_id=? AND idempotency_key=?").params(userId, key)
                .query(this::map).optional().orElse(null);
    }

    Stored detail(long userId, String orderNo) {
        return jdbc.sql(ORDER_COLUMNS + " WHERE o.order_no=? AND user_id=?").params(orderNo, userId)
                .query(this::map).optional().orElse(null);
    }

    Stored adminDetail(String orderNo) {
        return jdbc.sql(ORDER_COLUMNS + " WHERE o.order_no=?").param(orderNo).query(this::map).optional().orElse(null);
    }

    List<Stored> list(Long userId, String state, String orderNo, OrderCursor cursor, int limit) {
        StringBuilder sql = new StringBuilder(ORDER_COLUMNS).append(" WHERE 1=1");
        if (userId != null) sql.append(" AND user_id=:owner");
        if (state != null) sql.append(" AND order_status=:state");
        if (orderNo != null) sql.append(" AND o.order_no=:number");
        if (cursor != null) sql.append(" AND (created_at<:created OR (created_at=:created AND id<:id))");
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT :limit");
        var query = jdbc.sql(sql.toString()).param("limit", limit);
        if (userId != null) query.param("owner", userId);
        if (state != null) query.param("state", state);
        if (orderNo != null) query.param("number", orderNo);
        if (cursor != null) query.param("created", Timestamp.from(cursor.createdAt())).param("id", cursor.id());
        return query.query(this::map).list();
    }

    EventOrder forResult(String orderNo) {
        return jdbc.sql("SELECT user_id,order_status,item_snapshot FROM order_main WHERE order_no=?").param(orderNo)
                .query((rs, row) -> new EventOrder(rs.getLong("user_id"), rs.getString("order_status"),
                        rs.getString("item_snapshot"))).optional().orElse(null);
    }

    boolean markReserved(String orderNo) {
        return jdbc.sql("""
                UPDATE order_main SET order_status='UNPAID', inventory_status='RESERVED',
                    expires_at=TIMESTAMPADD(MINUTE,15,CURRENT_TIMESTAMP(3)), version=version+1
                WHERE order_no=? AND order_status='PREPARING'
                """).param(orderNo).update() == 1;
    }

    boolean markFailed(String orderNo, String inventoryStatus, String code) {
        return jdbc.sql("""
                UPDATE order_main SET order_status='FAILED', inventory_status=?, failure_code=?, version=version+1
                WHERE order_no=? AND order_status='PREPARING'
                """).params(inventoryStatus, code, orderNo).update() == 1;
    }

    boolean markReleasedBeforePayment(String orderNo) {
        return jdbc.sql("""
                UPDATE order_main SET order_status='FAILED', inventory_status='RELEASED',
                    failure_code='RESERVATION_RELEASED', expires_at=NULL, version=version+1
                WHERE order_no=? AND order_status IN ('PREPARING','UNPAID') AND payment_status='NONE'
                """).param(orderNo).update() == 1;
    }

    void queueCartCleanup(String orderNo) {
        jdbc.sql("INSERT INTO order_cart_cleanup (order_no) VALUES (?)").param(orderNo).update();
    }

    List<QuotedItem> items(String snapshot) {
        try {
            return json.readValue(snapshot, ITEMS);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid stored item snapshot");
        }
    }

    private Stored map(ResultSet rs, int row) throws SQLException {
        try {
            var address = json.readValue(rs.getString("address_snapshot"), OrderView.Address.class);
            var items = items(rs.getString("item_snapshot")).stream().map(item -> {
                var product = item.product();
                return new CheckoutQuote.Line(product.skuId(), product.productName(), product.specName(),
                        product.unitPriceFen(), item.quantity(), product.weightGram(), product.unitPriceFen() * item.quantity());
            }).toList();
            String carrier = rs.getString("carrier_code");
            var shipment = carrier == null ? null : new OrderView.Shipment(carrier, rs.getString("tracking_no"),
                    time(rs, "shipped_at"), json.readValue(rs.getString("allocation_snapshot"), ALLOCATIONS));
            var view = new OrderView(rs.getString("order_no"), rs.getString("order_status"), rs.getString("inventory_status"),
                    rs.getString("payment_status"), rs.getString("currency"), rs.getLong("goods_amount_fen"),
                    rs.getLong("freight_amount_fen"), rs.getLong("payable_amount_fen"), rs.getLong("total_weight_gram"),
                    items, address, time(rs, "created_at"), time(rs, "expires_at"), time(rs, "paid_at"),
                    rs.getString("payment_no"), shipment, rs.getString("failure_code"), rs.getString("exception_code"));
            return new Stored(rs.getLong("id"), rs.getString("request_hash"), view);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid stored order snapshot");
        }
    }

    static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : OffsetDateTime.ofInstant(value.toInstant(), BUSINESS_ZONE);
    }

    record Quote(String id, long addressId, String addressSnapshot, String itemSnapshot, String currency, long goods,
                 long freight, long payable, long weight, String hash, String consumedOrderNo, boolean expired) { }
    record QuotedItem(ProductQuote.Line product, int quantity) { }
    record Stored(long id, String hash, OrderView view) { }
    record EventOrder(long userId, String status, String itemSnapshot) { }
}
