package org.spring.shopproduct;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopproduct.cache.ProductCacheInvalidationWorker;
import org.spring.shopproduct.cache.ProductCacheKeys;
import org.spring.shopproduct.dto.ProductSaleStatusRequest;
import org.spring.shopproduct.dto.SkuCreateRequest;
import org.spring.shopproduct.service.ProductQueryService;
import org.spring.shopproduct.service.ProductSaleService;
import org.spring.shopproduct.service.SkuService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 MySQL 和 Redis，验证事务回滚、双删时间、并发确认和接管。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProductCacheInvalidationIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ProductSaleService sales;
    @Autowired ProductQueryService queries;
    @Autowired SkuService skus;
    @Autowired ProductCacheInvalidationWorker worker;

    private final String marker = "outbox_" + UUID.randomUUID().toString().replace("-", "");
    private long categoryId;
    private long productId;
    private String key;

    @BeforeEach void seed() {
        jdbc.update("INSERT INTO category(name,sort,enabled) VALUES (?,0,1)", marker);
        categoryId = jdbc.queryForObject("SELECT id FROM category WHERE name=?", Long.class, marker);
        jdbc.update("""
                INSERT INTO product_spu(category_id,name,brand,gluten_type,usage_text,ingredients,storage,
                                        shelf_life_days,image_url,status)
                VALUES (?,?,'麦香','HIGH','面包','小麦','干燥',180,'https://example.com/a.jpg','ON_SALE')
                """, categoryId, marker);
        productId = jdbc.queryForObject("SELECT id FROM product_spu WHERE category_id=?", Long.class, categoryId);
        key = ProductCacheKeys.detail(productId);
    }

    @AfterEach void cleanup() {
        redis.delete(key);
        jdbc.update("DELETE FROM cache_invalidation WHERE cache_key=?", key);
        jdbc.update("DELETE FROM product_sku WHERE product_id=?", productId);
        jdbc.update("DELETE FROM product_spu WHERE id=?", productId);
        jdbc.update("DELETE FROM category WHERE id=?", categoryId);
    }

    @Test void saleWritesTwoRowsAtomicallyAndDeletesAfterCommit() {
        queries.detail(Long.toString(productId));
        var offShelf = new ProductSaleStatusRequest(ProductSaleStatusRequest.SaleStatus.OFF_SHELF, 1L);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            sales.changeStatus(Long.toString(productId), offShelf);
            assertThat(eventCount()).isEqualTo(2);
            assertThat(redis.hasKey(key)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(eventCount()).isZero();
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM product_spu WHERE id=?", String.class, productId)).isEqualTo("ON_SALE");

        sales.changeStatus(Long.toString(productId), offShelf);
        assertThat(jdbc.queryForList("SELECT type FROM cache_invalidation WHERE cache_key=? ORDER BY id", String.class, key))
                .containsExactly("SALE_STATUS", "SALE_STATUS");
        assertThat(jdbc.queryForList("""
                SELECT TIMESTAMPDIFF(MICROSECOND,created_at,execute_at)
                FROM cache_invalidation WHERE cache_key=? ORDER BY id
                """, Long.class, key)).containsExactly(0L, 1_000_000L);
        long delayedId = jdbc.queryForObject("SELECT MAX(id) FROM cache_invalidation WHERE cache_key=?", Long.class, key);
        // 先验证真实写入的时间差，再控制第二条任务的到期时间，避免慢 CI 恰好跨过一秒。
        jdbc.update("UPDATE cache_invalidation SET execute_at=NOW(3) + INTERVAL 1 DAY WHERE id=?", delayedId);
        worker.poll();
        assertThat(doneCount()).isEqualTo(1);
        assertThat(redis.hasKey(key)).isFalse();
        assertNotFound();
        assertThat(redis.opsForValue().get(key)).isEqualTo("__NULL__");
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(175L, 180L);

        jdbc.update("UPDATE cache_invalidation SET execute_at=NOW(3) WHERE id=?", delayedId);
        worker.poll();
        assertThat(doneCount()).isEqualTo(2);
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test void skuCreationDeletesCachedDetailAndNextReadGetsNewSku() {
        assertThat(queries.detail(Long.toString(productId)).skus()).isEmpty();
        createSku();
        assertThat(jdbc.queryForList("SELECT type FROM cache_invalidation WHERE cache_key=?", String.class, key))
                .containsExactly("SKU_CHANGE");
        worker.poll();
        assertThat(doneCount()).isEqualTo(1);
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(queries.detail(Long.toString(productId)).skus()).singleElement().satisfies(sku -> {
            assertThat(sku.skuCode()).isEqualTo(marker);
            assertThat(sku.estimatedAvailable()).isNull();
        });
    }

    @Test void twoConsumersCanDeleteAndConditionallyConfirmTheSameRow() throws Exception {
        queries.detail(Long.toString(productId));
        createSku();
        // 两个 worker 先读到同一待办，再在相同 Redis/MySQL 上并发删除与确认。
        var barrier = new CyclicBarrier(2);
        var coordinatedRedis = mock(StringRedisTemplate.class);
        when(coordinatedRedis.delete(anyString())).thenAnswer(call -> {
            String cacheKey = call.getArgument(0);
            if (key.equals(cacheKey)) {
                barrier.await(5, TimeUnit.SECONDS);
            }
            return redis.delete(cacheKey);
        });
        var firstWorker = new ProductCacheInvalidationWorker(jdbc, coordinatedRedis);
        var secondWorker = new ProductCacheInvalidationWorker(jdbc, coordinatedRedis);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(firstWorker::poll);
            var second = workers.submit(secondWorker::poll);
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
        verify(coordinatedRedis, times(2)).delete(key);
        assertThat(doneCount()).isEqualTo(1);
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test void redisFailureAndLostAckLeaveWorkForAnotherConsumer() {
        queries.detail(Long.toString(productId));
        createSku();
        var offlineRedis = mock(StringRedisTemplate.class);
        when(offlineRedis.delete(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        new ProductCacheInvalidationWorker(jdbc, offlineRedis).poll();
        assertThat(doneCount()).isZero();
        assertThat(redis.hasKey(key)).isTrue();

        var lostAck = spy(jdbc);
        // 模拟进程在 Redis DEL 成功、数据库确认前中断。
        doThrow(new DataAccessResourceFailureException("lost before ack"))
                .when(lostAck).update(eq("UPDATE cache_invalidation SET done=1 WHERE id=? AND done=0"), anyLong());
        new ProductCacheInvalidationWorker(lostAck, redis).poll();
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(doneCount()).isZero();
        worker.poll();
        assertThat(doneCount()).isEqualTo(1);
        assertThat(redis.hasKey(key)).isFalse();
    }

    private void createSku() {
        skus.create(new SkuCreateRequest(Long.toString(productId), marker, "5kg", 5000L, 2990L, true));
    }

    private void assertNotFound() {
        assertThatThrownBy(() -> queries.detail(Long.toString(productId))).isInstanceOfSatisfying(BusinessException.class,
                error -> {
                    assertThat(error.getCode()).isEqualTo(404);
                    assertThat(error.getBusinessCode()).isEqualTo("PRODUCT_NOT_FOUND");
                });
    }

    private long eventCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cache_invalidation WHERE cache_key=?", Long.class, key);
    }

    private long doneCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cache_invalidation WHERE cache_key=? AND done=1", Long.class, key);
    }
}
