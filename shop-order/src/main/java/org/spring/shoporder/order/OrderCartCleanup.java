package org.spring.shoporder.order;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** An auxiliary operation, independent of the inventory result transaction. */
public final class OrderCartCleanup {
    private final JdbcClient jdbc;
    private final OrderStore orders;
    private final TransactionTemplate transactions;

    OrderCartCleanup(JdbcClient jdbc, OrderStore orders, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.orders = orders;
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public void process() {
        transactions.executeWithoutResult(status -> {
            String orderNo = jdbc.sql("""
                    SELECT order_no FROM order_cart_cleanup WHERE completed_at IS NULL
                    ORDER BY order_no LIMIT 1 FOR UPDATE SKIP LOCKED
                    """).query(String.class).optional().orElse(null);
            if (orderNo == null) return;
            var order = orders.forResult(orderNo);
            boolean existing = !"FAILED".equals(order.status())
                    && jdbc.sql("SELECT user_id FROM cart_owner WHERE user_id=? FOR UPDATE").param(order.userId())
                    .query(Long.class).optional().isPresent();
            if (existing) {
                for (var item : orders.items(order.itemSnapshot())) {
                    long sku = Long.parseLong(item.product().skuId());
                    jdbc.sql("DELETE FROM cart_item WHERE user_id=? AND sku_id=? AND quantity<=?")
                            .params(order.userId(), sku, item.quantity()).update();
                    jdbc.sql("UPDATE cart_item SET quantity=quantity-? WHERE user_id=? AND sku_id=?")
                            .params(item.quantity(), order.userId(), sku).update();
                }
            }
            jdbc.sql("UPDATE order_cart_cleanup SET completed_at=CURRENT_TIMESTAMP(3) WHERE order_no=?")
                    .param(orderNo).update();
        });
    }
}
