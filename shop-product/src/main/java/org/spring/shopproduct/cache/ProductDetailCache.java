package org.spring.shopproduct.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spring.shopproduct.vo.ProductCreatedVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** 公开商品详情的 Cache-Aside。空值表示数据库里没有可见商品。 */
@Component
public class ProductDetailCache {
    private static final Logger log = LoggerFactory.getLogger(ProductDetailCache.class);
    private static final String NULL_VALUE = "__NULL__";
    private static final Duration NULL_TTL = Duration.ofMinutes(3);
    private static final Duration DETAIL_TTL = Duration.ofMinutes(60);
    private static final String METRIC = "product_cache_requests_total";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Counter hit;
    private final Counter miss;
    private final Counter nullHit;
    private final Counter fallback;

    public ProductDetailCache(StringRedisTemplate redis, ObjectMapper json, MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.hit = meters.counter(METRIC, "result", "hit");
        this.miss = meters.counter(METRIC, "result", "miss");
        this.nullHit = meters.counter(METRIC, "result", "null_hit");
        this.fallback = meters.counter(METRIC, "result", "fallback");
    }

    public ProductCreatedVO getOrLoad(long productId, Supplier<ProductCreatedVO> loadFromDatabase) {
        String key = ProductCacheKeys.detail(productId);
        String stored;
        try {
            stored = redis.opsForValue().get(key);
        } catch (RuntimeException failure) {
            warnFallback(productId, "get", failure);
            fallback.increment();
            // Redis 已经不可用，本次只查数据库，避免再等一次 Redis 超时。
            return loadFromDatabase.get();
        }

        if (NULL_VALUE.equals(stored)) {
            nullHit.increment();
            return null;
        }
        if (stored != null) {
            try {
                ProductCreatedVO detail = json.readValue(stored, ProductCreatedVO.class);
                if (detail == null || !Long.toString(productId).equals(detail.productId())
                        || !"ON_SALE".equals(detail.status()) || detail.skus() == null) {
                    throw new IllegalArgumentException("Invalid cached product detail");
                }
                hit.increment();
                return detail;
            } catch (JsonProcessingException | RuntimeException failure) {
                warnFallback(productId, "decode", failure);
                fallback.increment();
            }
        }

        // 数据库异常在 Redis 捕获范围外，交由查询用例按原有语义处理。
        ProductCreatedVO detail;
        try {
            detail = loadFromDatabase.get();
        } catch (RuntimeException databaseFailure) {
            if (stored == null) {
                miss.increment();
            }
            throw databaseFailure;
        }
        boolean written = write(key, productId, detail);
        if (stored == null) {
            (written ? miss : fallback).increment();
        }
        return detail;
    }

    private boolean write(String key, long productId, ProductCreatedVO detail) {
        try {
            String value = detail == null ? NULL_VALUE : json.writeValueAsString(detail);
            Duration ttl = detail == null ? NULL_TTL : DETAIL_TTL.plusSeconds(ThreadLocalRandom.current().nextInt(601));
            redis.opsForValue().set(key, value, ttl);
            return true;
        } catch (JsonProcessingException | RuntimeException failure) {
            warnFallback(productId, "set", failure);
            return false;
        }
    }

    private void warnFallback(long productId, String operation, Exception failure) {
        log.warn("event=PRODUCT_CACHE_FALLBACK productId={} operation={} errorType={}",
                productId, operation, failure.getClass().getSimpleName());
    }
}
