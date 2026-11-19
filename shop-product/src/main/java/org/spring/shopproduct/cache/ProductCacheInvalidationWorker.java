package org.spring.shopproduct.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** 每个商品实例独立扫描，Redis DEL 与条件确认可以重复执行。 */
@Component
@DependsOnDatabaseInitialization
public class ProductCacheInvalidationWorker {
    private static final Logger log = LoggerFactory.getLogger(ProductCacheInvalidationWorker.class);
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public ProductCacheInvalidationWorker(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Scheduled(fixedDelayString = "${shop.product.cache.invalidation.poll-interval-ms:500}")
    public void poll() {
        List<Invalidation> pending;
        try {
            pending = jdbc.query("""
                    SELECT id,cache_key,type FROM cache_invalidation
                    WHERE done=0 AND execute_at <= NOW(3)
                    ORDER BY id LIMIT 50
                    """, (row, index) -> new Invalidation(row.getLong("id"), row.getString("cache_key"), row.getString("type")));
        } catch (RuntimeException failure) {
            log.warn("event=PRODUCT_CACHE_INVALIDATION_SCAN_FAILED errorType={}", failure.getClass().getSimpleName());
            return;
        }
        for (Invalidation task : pending) {
            try {
                // key 已经不存在时 DEL 返回 false，同样算成功。null 表示没有得到明确结果。
                if (redis.delete(task.key()) == null) {
                    throw new IllegalStateException("Missing Redis DEL result");
                }
            } catch (RuntimeException failure) {
                warn(task, "delete", failure);
                return;
            }
            try {
                int marked = jdbc.update("UPDATE cache_invalidation SET done=1 WHERE id=? AND done=0", task.id());
                if (marked == 1) {
                    log.debug("event=PRODUCT_CACHE_INVALIDATION_DONE id={} type={}", task.id(), task.type());
                }
            } catch (RuntimeException failure) {
                warn(task, "ack", failure);
                return;
            }
        }
    }

    private void warn(Invalidation task, String operation, RuntimeException failure) {
        log.warn("event=PRODUCT_CACHE_INVALIDATION_RETRY id={} type={} operation={} errorType={}",
                task.id(), task.type(), operation, failure.getClass().getSimpleName());
    }

    private record Invalidation(long id, String key, String type) { }
}
