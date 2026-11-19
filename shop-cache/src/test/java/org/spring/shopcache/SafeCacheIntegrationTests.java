package org.spring.shopcache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** 真实业务 Redis/Redisson 集成，数据库 loader 用可控制函数测试故障，不依赖业务表。 */
@SpringBootTest(classes = SafeCacheIntegrationTests.TestApp.class, properties = {
        "spring.config.import=file:examples/application.yml", "spring.main.web-application-type=none",
        "spring.data.redis.host=${SHOP_CACHE_REDIS_HOST:127.0.0.1}",
        "spring.data.redis.port=${SHOP_CACHE_REDIS_PORT:6381}",
        "spring.data.redis.password=${SHOP_CACHE_REDIS_PASSWORD:shop_cache_local_dev}"
})
class SafeCacheIntegrationTests {
    private static final String NAMESPACE = "safe-cache-test-" + UUID.randomUUID().toString().replace("-", "");
    @SpringBootConfiguration @EnableAutoConfiguration static class TestApp { }
    @Autowired private SafeCacheTemplate cache;
    @Autowired private SafeCacheProperties properties;
    @Autowired private CacheDependencyExecutor dependencies;
    @Autowired private ObjectMapper mapper;
    @Autowired private CircuitBreakerRegistry breakers;
    @Autowired private RetryRegistry retries;
    @Autowired private TimeLimiterRegistry limiters;
    @Autowired @Qualifier("safeCacheRedisTemplate") private StringRedisTemplate redis;
    @Autowired @Qualifier("safeCacheRedisson") private RedissonClient redisson;
    @Autowired @Qualifier("safeCacheRedisResources") private SafeCacheAutoConfiguration.RedisResources resources;

    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("shop.cache.namespace", () -> NAMESPACE);
        // 使用不同于 Java 默认值的测试阈值，证明 YAML/Boot Registry 装配确实生效。
        registry.add("resilience4j.timelimiter.instances.safeCacheDatabase.timeout-duration", () -> "900ms");
    }

    @BeforeEach void ready() {
        // 先验证真实 Redis 就绪并建立共享连接；不把 JVM 初次类加载/建连时间当作缓存业务耗时。
        try (var connection = resources.factory().getConnection()) { assertThat(connection.ping()).isEqualTo("PONG"); }
    }

    @AfterEach void cleanup() {
        breakers.getAllCircuitBreakers().forEach(breaker -> breaker.reset());
        // SCAN 只删本测试 UUID 命名空间；不 KEYS、不 FLUSHDB、不动真实业务数据。
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(NAMESPACE + ":*").count(100).build())) {
            List<String> keys = new ArrayList<>();
            cursor.forEachRemaining(keys::add);
            if (!keys.isEmpty()) redis.delete(keys);
        }
    }

    @Test void exampleYamlBindsActualResilienceRegistriesAndBusinessRedis() {
        int port = Integer.parseInt(System.getenv().getOrDefault("SHOP_CACHE_REDIS_PORT", "6381"));
        assertThat(resources.factory().getPort()).isEqualTo(port);
        assertThat(redisson.getConfig().useSingleServer().getAddress()).endsWith(":" + port);
        assertThat(retries.retry(CacheDependencyExecutor.REDIS_READ).getRetryConfig().getMaxAttempts()).isEqualTo(2);
        assertThat(limiters.timeLimiter(CacheDependencyExecutor.REDIS_READ).getTimeLimiterConfig().getTimeoutDuration()).isEqualTo(Duration.ofMillis(250));
        assertThat(limiters.timeLimiter(CacheDependencyExecutor.DATABASE).getTimeLimiterConfig().getTimeoutDuration()).isEqualTo(Duration.ofMillis(900));
        assertThat(breakers.circuitBreaker(CacheDependencyExecutor.DATABASE).getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(10);
    }

    @Test void warmCacheAvoidsLoaderAndNormalTtlIncludesBoundedJitter() {
        AtomicInteger loads = new AtomicInteger();
        var expected = new Profile(42, "flour");
        assertThat(cache.get("profile:42", Profile.class, () -> { loads.incrementAndGet(); return expected; })).isEqualTo(expected);
        assertThat(cache.get("profile:42", Profile.class, () -> { throw new AssertionError("warm cache must not query"); })).isEqualTo(expected);
        assertThat(loads.get()).isEqualTo(1);
        Duration ttl = Duration.ofMillis(Objects.requireNonNull(redis.getExpire(cache.keys("profile:42").data(), TimeUnit.MILLISECONDS)));
        assertThat(ttl).isBetween(properties.ttl().minusSeconds(2), properties.ttl().plus(properties.jitter()));
    }

    @Test void absentDatabaseValueHasShortNegativeCacheAndDoesNotRepeatedlyLoad() {
        AtomicInteger loads = new AtomicInteger();
        for (int i = 0; i < 3; i++) assertThat(cache.get("missing:7", Profile.class, () -> { loads.incrementAndGet(); return null; })).isNull();
        assertThat(loads.get()).isEqualTo(1);
        String dataKey = cache.keys("missing:7").data();
        assertThat(redis.opsForValue().get(dataKey)).isEqualTo("NULL_VAL");
        assertThat(redis.getExpire(dataKey, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(properties.nullTtl().toMillis());
    }

    @Test void realStringCannotCollideWithNegativeMarkerAndGenericValuesKeepTheirType() {
        assertThat(cache.get("string", String.class, () -> "NULL_VAL")).isEqualTo("NULL_VAL");
        assertThat(cache.get("string", String.class, () -> "wrong")).isEqualTo("NULL_VAL");
        var type = new TypeReference<List<Profile>>() { };
        cache.get("list", type, () -> List.of(new Profile(1, "a")), error -> { throw error; });
        assertThat(cache.get("list", type, () -> List.of(), error -> { throw error; })).containsExactly(new Profile(1, "a"));
    }

    @Test void twoIndependentRedissonClientsMergeTwentyFourConcurrentLoads() throws Exception {
        RedissonClient otherClient = Redisson.create(new Config(redisson.getConfig()));
        var second = new SafeCacheTemplate(redis, otherClient, mapper, properties, dependencies);
        ExecutorService callers = Executors.newFixedThreadPool(24);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        try {
            List<Future<Profile>> pending = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                SafeCacheTemplate target = i % 2 == 0 ? cache : second;
                pending.add(callers.submit(() -> target.get("concurrent", Profile.class, () -> {
                    loads.incrementAndGet(); started.countDown(); release.await(2, TimeUnit.SECONDS); return new Profile(42, "shared");
                })));
            }
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(redis.opsForHash().size(cache.keys("concurrent").lock())).isEqualTo(1);
            release.countDown();
            for (var future : pending) assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(new Profile(42, "shared"));
            assertThat(loads.get()).isEqualTo(1);
            assertThat(redis.hasKey(cache.keys("concurrent").lock())).isFalse();
        } finally { release.countDown(); callers.shutdownNow(); otherClient.shutdown(); }
    }

    @Test void databaseFailureIsNotNegativeCachedAndBusinessErrorsAreNotRetried() {
        AtomicInteger loads = new AtomicInteger();
        assertThatThrownBy(() -> cache.get("invalid", Profile.class, () -> {
            loads.incrementAndGet(); throw new IllegalArgumentException("invalid query");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(loads.get()).isEqualTo(1);
        assertThat(redis.hasKey(cache.keys("invalid").data())).isFalse();
        assertThat(redis.hasKey(cache.keys("invalid").lock())).isFalse();
        assertThatThrownBy(() -> cache.get("db-failed", Profile.class, () -> { throw new java.sql.SQLException("offline"); }))
                .isInstanceOf(CacheUnavailableException.class);
        assertThat(redis.hasKey(cache.keys("db-failed").data())).isFalse();
    }

    @Test void redisCircuitOpenUsesExplicitFallbackWithoutDatabaseOrCachingFallbackValue() {
        breakers.circuitBreaker(CacheDependencyExecutor.REDIS_READ).transitionToOpenState();
        AtomicInteger loads = new AtomicInteger();
        assertThat(cache.get("fallback", Profile.class, () -> { loads.incrementAndGet(); return new Profile(1, "db"); },
                error -> new Profile(0, "temporarily unavailable"))).isEqualTo(new Profile(0, "temporarily unavailable"));
        assertThat(loads.get()).isZero();
        assertThat(redis.hasKey(cache.keys("fallback").data())).isFalse();
    }

    @Test void databaseCircuitOpenStillAllowsExistingWarmCache() {
        cache.get("warm", Profile.class, () -> new Profile(1, "warm"));
        breakers.circuitBreaker(CacheDependencyExecutor.DATABASE).transitionToOpenState();
        assertThat(cache.get("warm", Profile.class, () -> { throw new AssertionError(); })).isEqualTo(new Profile(1, "warm"));
        assertThatThrownBy(() -> cache.get("cold", Profile.class, () -> { throw new AssertionError(); })).isInstanceOf(CacheUnavailableException.class);
        assertThat(redis.hasKey(cache.keys("cold").data())).isFalse();
    }

    @Test void refillFailureReturnsSuccessfulDatabaseResultWithoutAnotherQuery() {
        AtomicInteger loads = new AtomicInteger();
        Profile actual = cache.get("write-failure", Profile.class, () -> {
            loads.incrementAndGet(); breakers.circuitBreaker(CacheDependencyExecutor.REDIS_WRITE).transitionToOpenState();
            return new Profile(2, "from-database");
        });
        assertThat(actual).isEqualTo(new Profile(2, "from-database"));
        assertThat(loads.get()).isEqualTo(1);
        assertThat(redis.hasKey(cache.keys("write-failure").data())).isFalse();
        assertThat(redis.hasKey(cache.keys("write-failure").lock())).isFalse();
    }

    @Test void corruptedCacheDoesNotBecomeMissAndHammerDatabase() {
        redis.opsForValue().set(cache.keys("corrupt").data(), "bad-json");
        AtomicInteger loads = new AtomicInteger();
        assertThatThrownBy(() -> cache.get("corrupt", Profile.class, () -> { loads.incrementAndGet(); return null; }))
                .isInstanceOf(CacheUnavailableException.class);
        assertThat(loads.get()).isZero();
    }

    @Test void invalidationDuringDatabaseLoadPreventsOldRefill() throws Exception {
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            var pending = caller.submit(() -> cache.get("race", Profile.class, () -> {
                started.countDown(); release.await(2, TimeUnit.SECONDS); return new Profile(1, "old");
            }));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            cache.evict("race");
            release.countDown();
            assertThatThrownBy(() -> pending.get(3, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CacheUnavailableException.class);
            assertThat(redis.hasKey(cache.keys("race").data())).isFalse();
            assertThat(redis.hasKey(cache.keys("race").lock())).isFalse();
        } finally { release.countDown(); caller.shutdownNow(); }
    }

    @Test void timedOutLoaderCannotRefillLaterEvenIfItDoesNotImmediatelyStopOnInterrupt() throws Exception {
        CountDownLatch release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        try {
            assertThatThrownBy(() -> cache.get("slow-loader", Profile.class, () -> {
                loads.incrementAndGet();
                try {
                    // 模拟不能立刻响应中断的 JDBC；测试结束会主动释放，不遗留线程。
                    try { release.await(2, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { release.await(2, TimeUnit.SECONDS); }
                    return new Profile(1, "late");
                } finally { finished.countDown(); }
            })).isInstanceOf(CacheUnavailableException.class).hasCauseInstanceOf(TimeoutException.class);
            assertThat(redis.hasKey(cache.keys("slow-loader").lock())).isFalse();
            release.countDown();
            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(loads.get()).isEqualTo(1);
            assertThat(redis.hasKey(cache.keys("slow-loader").data())).isFalse();
        } finally { release.countDown(); }
    }

    @Test void lockCompetitionTimesOutWithoutDatabaseFallback() throws Exception {
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService owner = Executors.newSingleThreadExecutor();
        var holding = owner.submit(() -> {
            var lock = redisson.getLock(cache.keys("busy").lock());
            lock.lock(5, TimeUnit.SECONDS);
            try { held.countDown(); release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { lock.unlock(); }
        });
        try {
            assertThat(held.await(1, TimeUnit.SECONDS)).isTrue();
            AtomicInteger loads = new AtomicInteger();
            assertThatThrownBy(() -> cache.get("busy", Profile.class, () -> { loads.incrementAndGet(); return null; }))
                    .isInstanceOf(CacheUnavailableException.class).hasMessageContaining("busy");
            assertThat(loads.get()).isZero();
            release.countDown(); holding.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); owner.shutdownNow(); }
    }

    @Test void evictRemovesWarmAndNegativeCacheAndIsIdempotent() {
        cache.get("evict", Profile.class, () -> new Profile(1, "old"));
        cache.evict("evict"); cache.evict("evict");
        assertThat(cache.get("evict", Profile.class, () -> new Profile(1, "new"))).isEqualTo(new Profile(1, "new"));
        cache.get("absent-evict", Profile.class, () -> null);
        cache.evict("absent-evict");
        assertThat(cache.get("absent-evict", Profile.class, () -> new Profile(2, "created"))).isEqualTo(new Profile(2, "created"));
    }

    public record Profile(long id, String name) { }
}
