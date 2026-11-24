package org.spring.shoporder.cart;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.List;

@Repository
class CartStore {
    private static final ResultSetExtractor<Boolean> HAS_ROW = ResultSet::next;
    private final JdbcTemplate jdbc;

    CartStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Entry> list(long userId) {
        return jdbc.query("SELECT sku_id, quantity FROM cart_item WHERE user_id=? ORDER BY sku_id",
                (row, index) -> new Entry(row.getLong("sku_id"), row.getInt("quantity")), userId);
    }

    void lockOrCreateOwner(long userId) {
        // The duplicate-key update acquires an exclusive lock even for a no-op update.
        jdbc.update("INSERT INTO cart_owner (user_id) VALUES (?) ON DUPLICATE KEY UPDATE user_id=user_id", userId);
    }

    boolean lockOwner(long userId) {
        return Boolean.TRUE.equals(jdbc.query("SELECT user_id FROM cart_owner WHERE user_id=? FOR UPDATE",
                HAS_ROW, userId));
    }

    int size(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cart_item WHERE user_id=?", Integer.class, userId);
    }

    boolean contains(long userId, long skuId) {
        return Boolean.TRUE.equals(jdbc.query("SELECT sku_id FROM cart_item WHERE user_id=? AND sku_id=?",
                HAS_ROW, userId, skuId));
    }

    void put(long userId, long skuId, int quantity) {
        jdbc.update("""
                INSERT INTO cart_item (user_id, sku_id, quantity) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE quantity=?
                """, userId, skuId, quantity, quantity);
    }

    void delete(long userId, long skuId) {
        jdbc.update("DELETE FROM cart_item WHERE user_id=? AND sku_id=?", userId, skuId);
    }

    record Entry(long skuId, int quantity) { }
}
