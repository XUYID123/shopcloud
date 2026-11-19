package org.spring.shopproduct.cache;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在商品库业务事务内登记失效。回滚时业务写入和对应任务一起回滚。 */
@Component
public class ProductCacheInvalidationOutbox {
    public enum Type { SALE_STATUS, SKU_CHANGE }

    private final JdbcTemplate jdbc;

    public ProductCacheInvalidationOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(long productId, Type type) {
        String key = ProductCacheKeys.detail(productId);
        if (type == Type.SALE_STATUS) {
            // 同一条 SQL 中 NOW(3) 恒定，两行严格相差一秒。
            jdbc.update("""
                    INSERT INTO cache_invalidation(cache_key,type,execute_at) VALUES
                    (?,?,NOW(3)),(?,?,NOW(3) + INTERVAL 1 SECOND)
                    """, key, type.name(), key, type.name());
        } else {
            jdbc.update("""
                    INSERT INTO cache_invalidation(cache_key,type,execute_at) VALUES (?,?,NOW(3))
                    """, key, type.name());
        }
    }
}
