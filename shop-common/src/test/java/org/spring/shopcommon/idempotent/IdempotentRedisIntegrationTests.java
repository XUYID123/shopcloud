package org.spring.shopcommon.idempotent;

import org.junit.jupiter.api.*;
import org.spring.shopcommon.exception.BusinessException;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 真实双 Redis：原子并发占位、TTL 到期重入、失败标记保留以及 6380/6381 物理隔离。 */
class IdempotentRedisIntegrationTests {
    private static final String APPLICATION = "test-idempotent-" + UUID.randomUUID();
    private static LettuceConnectionFactory securityFactory, businessFactory;
    private static StringRedisTemplate redis, business;
    private final Set<String> ownedKeys = new HashSet<>();
    private final Operations target = new Operations();

    @BeforeAll static void connect() {
        securityFactory = factory("SHOP_GATEWAY_REDIS", 6380, "shop_gateway_local_dev");
        businessFactory = factory("SHOP_CACHE_REDIS", 6381, "shop_cache_local_dev");
        redis = new StringRedisTemplate(securityFactory);
        business = new StringRedisTemplate(businessFactory);
    }
    @AfterAll static void close() {
        if (securityFactory != null) securityFactory.destroy();
        if (businessFactory != null) businessFactory.destroy();
    }
    @AfterEach void cleanupOwnKeysOnly() { if (!ownedKeys.isEmpty()) redis.delete(ownedKeys); }

    @Test void thirtyTwoConcurrentCallsAcrossTwoProxiesExecuteBusinessOnce() throws Exception {
        String value = UUID.randomUUID().toString(); String key = key("submit", value);
        Operations first = proxy(), second = proxy();
        var callers = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                Operations selected = i % 2 == 0 ? first : second;
                calls.add(callers.submit(() -> {
                    start.await();
                    try { selected.submit(value); return true; }
                    catch (BusinessException error) {
                        assertThat(error.getBusinessCode()).isEqualTo("DUPLICATE_REQUEST"); return false;
                    }
                }));
            }
            start.countDown(); int accepted = 0;
            for (var future : calls) if (future.get(5, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1); assertThat(target.calls.get()).isEqualTo(1);
            assertThat(redis.opsForValue().get(key)).isEqualTo("1");
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(5000);
            assertThat(business.hasKey(key)).isFalse();
        } finally { callers.shutdownNow(); }
    }

    @Test void successfulMarkerSurvivesReturnAndExpiresBeforeNextAdmission() throws Exception {
        String value = UUID.randomUUID().toString(); String key = key("shortWindow", value);
        var proxy = proxy(); proxy.shortWindow(value);
        assertThatThrownBy(() -> proxy.shortWindow(value)).isInstanceOf(BusinessException.class);
        await().atMost(Duration.ofSeconds(4)).pollInterval(Duration.ofMillis(50)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        proxy.shortWindow(value);
        assertThat(target.calls.get()).isEqualTo(2);
    }

    @Test void businessFailureIsPreservedAndMarkerIsNotBlindlyDeleted() throws Exception {
        String value = UUID.randomUUID().toString(); String key = key("fail", value);
        var proxy = proxy();
        assertThatThrownBy(() -> proxy.fail(value)).isInstanceOf(IllegalArgumentException.class).hasMessage("business failure");
        assertThat(redis.hasKey(key)).isTrue();
        assertThatThrownBy(() -> proxy.fail(value)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getBusinessCode()).isEqualTo("DUPLICATE_REQUEST"));
    }

    private Operations proxy() {
        var factory = new AspectJProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAspect(new IdempotentAspect(new RedisIdempotentStore(redis), new IdempotentKeyResolver(APPLICATION)));
        return factory.getProxy();
    }
    private String key(String methodName, String value) throws Exception {
        var method = Operations.class.getMethod(methodName, String.class);
        String key = new IdempotentKeyResolver(APPLICATION).resolve(target, method, new Object[]{value}, method.getAnnotation(Idempotent.class));
        ownedKeys.add(key); return key;
    }
    private static LettuceConnectionFactory factory(String variable, int defaultPort, String defaultPassword) {
        String host = System.getenv().getOrDefault(variable + "_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault(variable + "_PORT", Integer.toString(defaultPort)));
        var server = new RedisStandaloneConfiguration(host, port);
        server.setPassword(System.getenv().getOrDefault(variable + "_PASSWORD", defaultPassword));
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).clientOptions(
                io.lettuce.core.ClientOptions.builder().socketOptions(io.lettuce.core.SocketOptions.builder()
                        .connectTimeout(Duration.ofSeconds(1)).build()).build()).build();
        var factory = new LettuceConnectionFactory(server, client); factory.afterPropertiesSet(); return factory;
    }
    public static class Operations {
        final AtomicInteger calls = new AtomicInteger();
        @Idempotent(prefix = "test:idempotent:", key = "#id") public void submit(String id) { calls.incrementAndGet(); }
        @Idempotent(prefix = "test:idempotent:", key = "#id", expireTime = 1) public void shortWindow(String id) { calls.incrementAndGet(); }
        @Idempotent(prefix = "test:idempotent:", key = "#id") public void fail(String id) { throw new IllegalArgumentException("business failure"); }
    }
}
