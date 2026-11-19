package org.spring.shopgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.config.Config;
import org.spring.shopcommon.auth.PermissionCacheKeys;
import org.spring.shopcommon.auth.UserPermissionSnapshot;
import org.spring.shopgateway.auth.*;
import org.spring.shopgateway.protection.RedisAbuseLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** 真实 HTTP、RSA、双 Redis 与实际 OpenFeign/服务发现客户端；不使用本地权限缓存。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.auth.enabled=true", "gateway.auth.clock-skew=0s", "gateway.auth.cache-ttl=2s",
        "spring.config.import=file:examples/application-governance.yml",
        "gateway.protection.sentinel-qps=10000", "gateway.protection.anti-abuse.requests-per-window=1000",
        "gateway.protection.anti-abuse.policies.register.limit=1000"
})
class GatewayAuthenticationTests {
    private static final HttpBackend BACKEND = new HttpBackend();
    private static final String PREFIX = "gateway:auth-test:" + UUID.randomUUID().toString().replace("-", "");
    private static final RedisScript<Long> INVALIDATE = RedisScript.of(new ClassPathResource("redis/rbac-user-invalidate.lua"), Long.class);
    @LocalServerPort private int port;
    @Autowired private RbacPermissionCache cache;
    @Autowired private RbacRedisPublisher publisher;
    @Autowired private GatewayAuthProperties properties;
    @Autowired private RbacCodec codec;
    @Autowired private ObjectMapper mapper;
    @Autowired private ApplicationContext application;
    @Autowired private RedisAbuseLimiter limiter;
    @Autowired private UserPermissionClient permissionClient;
    @Autowired @Qualifier("permissionFeignScheduler") private Scheduler scheduler;
    @Autowired @Qualifier("businessRedissonClient") private RedissonReactiveClient redisson;
    @Autowired @Qualifier("businessRedisTemplate") private ReactiveStringRedisTemplate business;
    @Autowired @Qualifier("reactiveStringRedisTemplate") private ReactiveStringRedisTemplate security;
    @Autowired @Qualifier("businessRedisConnectionFactory") private LettuceConnectionFactory businessFactory;
    @Autowired @Qualifier("redisConnectionFactory") private LettuceConnectionFactory securityFactory;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private String rulesVersion;

    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        registry.add("gateway.auth.public-key-location", () -> AuthTestSupport.PUBLIC_KEY);
        registry.add("gateway.auth.issuer", () -> AuthTestSupport.ISSUER);
        registry.add("gateway.auth.audience", () -> AuthTestSupport.AUDIENCE);
        registry.add("gateway.auth.key-prefix", () -> PREFIX + ":rbac");
        registry.add("gateway.protection.anti-abuse.key-prefix", () -> PREFIX + ":abuse");
        for (String name : List.of("shop-user", "shop-product")) {
            registry.add("spring.cloud.discovery.client.simple.instances." + name + "[0].uri", BACKEND::uri);
        }
    }

    @BeforeEach void prepare() {
        cleanup();
        BACKEND.grants.put("42", new UserPermissionSnapshot("42", true, Set.of("product:write")));
        BACKEND.grants.put("43", new UserPermissionSnapshot("43", true, Set.of()));
        rulesVersion = UUID.randomUUID().toString();
        publisher.publishRules(null, rules(rulesVersion, "product:write")).block(Duration.ofSeconds(3));
    }

    @AfterEach void cleanup() {
        if (BACKEND.continueLoad != null) BACKEND.continueLoad.countDown();
        BACKEND.loadStarted = null;
        BACKEND.continueLoad = null;
        BACKEND.permissionStatus = 200;
        BACKEND.permissionCalls.set(0);
        BACKEND.calls.set(0);
        BACKEND.grants.clear();
        deleteTestKeys(business);
        deleteTestKeys(security);
    }
    @AfterAll static void closeBackend() { BACKEND.close(); }

    @Test void whitelistStripsSpoofedIdentityAndSupportsDiscoveryAlias() throws Exception {
        var response = send("POST", "/api/v1/users", null, "999");
        assertThat(response.statusCode()).isEqualTo(200);
        var body = mapper.readTree(response.body());
        assertThat(body.get("userIdCount").asInt()).isZero();
        assertThat(body.get("roles").asText()).isEmpty();
        assertThat(body.get("permissions").asText()).isEmpty();
        assertThat(body.get("internalTokenPresent").asBoolean()).isTrue();
        assertThat(body.get("requestBody").asText()).isEqualTo("{}");
        assertThat(send("POST", "/shop-user/api/v1/users", null, "999").statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/users", null, "999").statusCode()).isEqualTo(401);
        assertThat(BACKEND.permissionCalls.get()).isZero();
    }

    /** 真正启用 JWT/RBAC 的网关中，合法跨域预检由 CORS 层处理，实际业务请求仍需认证。 */
    @Test void corsPreflightDoesNotAuthenticateButActualProtectedRequestStillRequiresToken() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/products/1"))
                .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(BACKEND.permissionCalls.get()).isZero();
        assertThat(BACKEND.calls.get()).isZero();
        assertThat(send("GET", "/api/v1/admin/products/1", null, null).statusCode()).isEqualTo(401);
    }

    @Test void noEarlierSecurityChainAndNoLocalPermissionCacheOrBroadcastListener() {
        assertThat(application.getBeanNamesForType(ReactiveUserDetailsService.class)).isEmpty();
        assertThat(application.containsBean("springSecurityWebFilterChainFilter")).isFalse();
        assertThat(application.containsBean("rbacBroadcastListener")).isFalse();
        assertThat(application.containsBean("rbacMessageListenerContainer")).isFalse();
        int businessPort = Integer.parseInt(System.getenv().getOrDefault("SHOP_CACHE_REDIS_PORT", "6381"));
        int securityPort = Integer.parseInt(System.getenv().getOrDefault("SHOP_GATEWAY_REDIS_PORT", "6380"));
        assertThat(businessFactory.getPort()).isEqualTo(businessPort);
        assertThat(securityFactory.getPort()).isEqualTo(securityPort);
        assertThat(security.hasKey(cache.rulesKey()).block()).isFalse();
    }

    @Test void ipBanRejectsBeforeMissingTokenAuthentication() throws Exception {
        for (int i = 0; i < 4; i++) limiter.check("all", "127.0.0.1", 1).block(Duration.ofSeconds(3));
        assertThat(send("GET", "/api/v1/admin/products/1", null, null).statusCode()).isEqualTo(429);
        assertThat(BACKEND.permissionCalls.get()).isZero();
        assertThat(BACKEND.calls.get()).isZero();
    }

    @Test void missingAndForgedTokensAre401AndPermissionsDoNotComeFromHeaders() throws Exception {
        var missing = send("GET", "/api/v1/admin/products/1", null, "42");
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(missing.headers().firstValue("WWW-Authenticate")).isPresent();
        assertThat(missing.headers().firstValue("Retry-After")).isEmpty();
        assertThat(send("GET", "/api/v1/admin/products/1", "bad.token.signature", "42").statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/v1/admin/products/1", AuthTestSupport.token("43"), "42").statusCode()).isEqualTo(403);
        assertThat(BACKEND.calls.get()).isZero();
    }

    @Test void expiredAccessTokenHasSpecific401CodeWithoutLoadingPermissions() throws Exception {
        String expired = AuthTestSupport.token("42", AuthTestSupport.ISSUER, AuthTestSupport.AUDIENCE,
                java.time.Instant.now().minusSeconds(60), java.util.Map.of(), AuthTestSupport.KEYS);
        var response = send("GET", "/api/v1/admin/products/1", expired, null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("ACCESS_TOKEN_EXPIRED");
        assertThat(BACKEND.permissionCalls.get()).isZero();
        assertThat(BACKEND.calls.get()).isZero();
    }

    @Test void validTokenColdLoadsViaFeignInjectsOnlyVerifiedIdentityAndPreservesDownstreamErrors() throws Exception {
        var allowed = send("GET", "/shop-product/api/v1/admin/products/1", AuthTestSupport.token("42"), "999");
        assertThat(allowed.statusCode()).isEqualTo(200);
        var body = mapper.readTree(allowed.body());
        assertThat(body.get("userId").asText()).isEqualTo("42");
        assertThat(body.get("userIdCount").asInt()).isEqualTo(1);
        assertThat(body.get("roles").asText()).isEmpty();
        assertThat(body.get("permissions").asText()).isEmpty();
        assertThat(body.get("internalTokenPresent").asBoolean()).isTrue();
        assertThat(BACKEND.permissionRequestId).isEqualTo(allowed.headers().firstValue("X-Request-Id").orElseThrow());
        assertThat(BACKEND.permissionTraceId).isEqualTo(allowed.headers().firstValue("X-Trace-Id").orElseThrow());
        assertThat(BACKEND.permissionTraceId).isEqualTo(BACKEND.permissionRequestId);
        assertThat(BACKEND.permissionCalls.get()).isEqualTo(1);
        assertThat(business.getExpire(cache.userKey("42")).block()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(send("GET", "/api/v1/admin/categories", AuthTestSupport.token("42"), null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/v1/admin/products/backend-error", AuthTestSupport.token("42"), null).statusCode()).isEqualTo(502);
    }

    @Test void deletingRedisIsEnoughForTheNextRequestToColdLoadNewPermissions() throws Exception {
        assertThat(send("GET", "/api/v1/admin/products/1", AuthTestSupport.token("42"), null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/admin/products/1", AuthTestSupport.token("42"), null).statusCode()).isEqualTo(200);
        assertThat(BACKEND.permissionCalls.get()).isEqualTo(1);
        BACKEND.grants.put("42", new UserPermissionSnapshot("42", true, Set.of()));
        invalidate("42");
        assertThat(send("GET", "/api/v1/admin/products/1", AuthTestSupport.token("42"), null).statusCode()).isEqualTo(403);
        assertThat(BACKEND.permissionCalls.get()).isEqualTo(2);
    }

    @Test void twoGatewayObjectsShareOneColdLoadUnderConcurrency() {
        var second = new RbacPermissionCache(business, properties, codec, permissionClient, scheduler, redisson);
        second.setInternalSigner(application.getBean(InternalTokenSigner.class));
        var decisions = Flux.range(0, 40).flatMap(i -> (i % 2 == 0 ? cache : second)
                .authorize("42", "GET", PathContainer.parsePath("/api/v1/admin/products/1"), "shop-product-api", "concurrent-test"), 40)
                .collectList().block(Duration.ofSeconds(10));
        assertThat(decisions).hasSize(40).allMatch(Boolean::booleanValue);
        assertThat(BACKEND.permissionCalls.get()).isEqualTo(1);
    }

    /** 两个独立 Redisson 客户端竞争真实 Redis 锁，验证不是 JVM 内互斥。 */
    @Test void independentRedissonClientsShareOneColdLoadAndUseOnlyBusinessRedis() throws Exception {
        var secondClient = Redisson.createReactive(new Config(redisson.getConfig()));
        BACKEND.loadStarted = new CountDownLatch(1);
        BACKEND.continueLoad = new CountDownLatch(1);
        try {
            var second = new RbacPermissionCache(business, properties, codec, permissionClient, scheduler, secondClient);
            second.setInternalSigner(application.getBean(InternalTokenSigner.class));
            var pending = Flux.range(0, 40).flatMap(i -> (i % 2 == 0 ? cache : second)
                    .authorize("42", "GET", PathContainer.parsePath("/api/v1/admin/products/1"), "shop-product-api", "redisson-concurrent"), 40)
                    .collectList().toFuture();
            assertThat(BACKEND.loadStarted.await(2, TimeUnit.SECONDS)).isTrue();
            String lockKey = PermissionCacheKeys.lock(properties.keyPrefix(), "42");
            // 真正的 Redisson 锁是 Hash；防刷 Redis 中没有对应 Key，租期有限且只有一个所有者。
            assertThat(business.opsForHash().size(lockKey).block()).isEqualTo(1);
            assertThat(business.getExpire(lockKey).block()).isPositive().isLessThanOrEqualTo(properties.loadTimeout().plusSeconds(1));
            assertThat(security.hasKey(lockKey).block()).isFalse();
            BACKEND.continueLoad.countDown();
            assertThat(pending.get(8, TimeUnit.SECONDS)).hasSize(40).allMatch(Boolean::booleanValue);
            assertThat(BACKEND.permissionCalls.get()).isEqualTo(1);
            assertThat(business.hasKey(lockKey).block()).isFalse();
        } finally {
            // 即使断言失败也释放 HTTP 测试夹具，随后关闭本测试单独创建的客户端。
            BACKEND.continueLoad.countDown();
            secondClient.shutdown();
        }
    }

    /** 模拟加载锁过期/被淘汰后由其他请求持有，旧加载不能回填或误解锁。 */
    @Test void losingRedissonOwnershipRejectsOldRefillAndPreservesNewOwnersLock() throws Exception {
        BACKEND.loadStarted = new CountDownLatch(1);
        BACKEND.continueLoad = new CountDownLatch(1);
        String lockKey = PermissionCacheKeys.lock(properties.keyPrefix(), "42");
        var replacement = redisson.getLock(lockKey);
        long replacementOwner = Long.MAX_VALUE;
        var pending = client.sendAsync(request("GET", "/api/v1/admin/products/1", AuthTestSupport.token("42"), null),
                HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(BACKEND.loadStarted.await(2, TimeUnit.SECONDS)).isTrue();
            // 删除仅限本测试随机命名空间，模拟 Redis 对已持有锁的淘汰，不触碰其他业务数据。
            business.delete(lockKey).block();
            assertThat(replacement.tryLock(0, 5, TimeUnit.SECONDS, replacementOwner).block()).isTrue();
            BACKEND.continueLoad.countDown();
            var response = pending.get(5, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
            assertThat(business.hasKey(cache.userKey("42")).block()).isFalse();
            assertThat(replacement.isHeldByThread(replacementOwner).block()).isTrue();
            assertThat(BACKEND.calls.get()).isZero();
        } finally {
            BACKEND.continueLoad.countDown();
            if (Boolean.TRUE.equals(replacement.isHeldByThread(replacementOwner).block())) replacement.unlock(replacementOwner).block();
        }
    }

    /** 调用方取消订阅后，usingWhen 仍负责异步解锁；不依靠等待租期来清理正常取消。 */
    @Test void cancellingColdLoadReleasesRedissonLockWithoutRefillingCache() throws Exception {
        BACKEND.loadStarted = new CountDownLatch(1);
        BACKEND.continueLoad = new CountDownLatch(1);
        String lockKey = PermissionCacheKeys.lock(properties.keyPrefix(), "42");
        var pending = cache.authorize("42", "GET", PathContainer.parsePath("/api/v1/admin/products/1"),
                "shop-product-api", "cancelled-load").toFuture();
        try {
            assertThat(BACKEND.loadStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(business.hasKey(lockKey).block()).isTrue();
            pending.cancel(true);
            // 仅测试线程同步等待；生产清理仍是响应式，最多给异步解锁 2 秒完成。
            Boolean removed = Flux.interval(Duration.ofMillis(20))
                    .concatMap(tick -> business.hasKey(lockKey)).filter(exists -> !exists)
                    .next().map(exists -> true).block(Duration.ofSeconds(2));
            assertThat(removed).isTrue();
            assertThat(business.hasKey(cache.userKey("42")).block()).isFalse();
            assertThat(BACKEND.calls.get()).isZero();
        } finally {
            pending.cancel(true);
            BACKEND.continueLoad.countDown();
        }
    }

    @Test void invalidationDuringFeignLoadPreventsOldPermissionRefill() throws Exception {
        BACKEND.loadStarted = new CountDownLatch(1);
        BACKEND.continueLoad = new CountDownLatch(1);
        var pending = client.sendAsync(request("GET", "/api/v1/admin/products/1", AuthTestSupport.token("42"), null),
                HttpResponse.BodyHandlers.ofString());
        assertThat(BACKEND.loadStarted.await(2, TimeUnit.SECONDS)).isTrue();
        BACKEND.grants.put("42", new UserPermissionSnapshot("42", true, Set.of()));
        invalidate("42");
        BACKEND.continueLoad.countDown();
        assertThat(pending.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(503);
        assertThat(business.hasKey(cache.userKey("42")).block()).isFalse();
        assertThat(BACKEND.calls.get()).isZero();
        assertThat(send("GET", "/api/v1/admin/products/1", AuthTestSupport.token("42"), null).statusCode()).isEqualTo(403);
    }

    @Test void rulesAreReadFromRedisEveryTimeAndCasPreventsStaleWrites() throws Exception {
        assertThat(send("GET", "/api/v1/admin/products/1", AuthTestSupport.token("42"), null).statusCode()).isEqualTo(200);
        publisher.publishRules(rulesVersion, rules("r2", "order:ship")).block();
        assertThat(send("GET", "/api/v1/admin/products/1", AuthTestSupport.token("42"), null).statusCode()).isEqualTo(403);
        assertThatThrownBy(() -> publisher.publishRules(rulesVersion, rules("r3", "product:write")).block())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void feignFailureIs503WithoutRefillOrDownstreamForwarding() throws Exception {
        BACKEND.permissionStatus = 503;
        assertThat(send("GET", "/api/v1/admin/products/1", AuthTestSupport.token("42"), null).statusCode()).isEqualTo(503);
        assertThat(business.hasKey(cache.userKey("42")).block()).isFalse();
        assertThat(BACKEND.calls.get()).isZero();
        assertThat(BACKEND.permissionCalls.get()).isEqualTo(1);
    }

    @Test void corruptCacheIsNotTreatedAsAMissAndCannotHammerUserService() throws Exception {
        business.opsForValue().set(cache.userKey("42"), "broken json").block();
        assertThat(send("GET", "/api/v1/admin/products/1", AuthTestSupport.token("42"), null).statusCode()).isEqualTo(503);
        assertThat(BACKEND.permissionCalls.get()).isZero();
        assertThat(BACKEND.calls.get()).isZero();
    }

    @Test void mismatchedUserServiceIdentityIsRejectedWithoutCaching() throws Exception {
        BACKEND.grants.put("42", new UserPermissionSnapshot("999", true, Set.of("product:write")));
        assertThat(send("GET", "/api/v1/admin/products/1", AuthTestSupport.token("42"), null).statusCode()).isEqualTo(503);
        assertThat(business.hasKey(cache.userKey("42")).block()).isFalse();
        assertThat(BACKEND.calls.get()).isZero();
    }

    @Test void expiredRedisPermissionsReloadWithoutAnyBroadcast() throws Exception {
        assertThat(send("GET", "/api/v1/admin/products/1", AuthTestSupport.token("42"), null).statusCode()).isEqualTo(200);
        BACKEND.grants.put("42", new UserPermissionSnapshot("42", true, Set.of()));
        reactor.core.publisher.Mono.delay(Duration.ofMillis(2100)).block();
        assertThat(send("GET", "/api/v1/admin/products/1", AuthTestSupport.token("42"), null).statusCode()).isEqualTo(403);
        assertThat(BACKEND.permissionCalls.get()).isEqualTo(2);
    }

    @Test void slowFeignIsBoundedAndReleasesTheLoadLock() throws Exception {
        BACKEND.loadStarted = new CountDownLatch(1);
        BACKEND.continueLoad = new CountDownLatch(1);
        assertThat(send("GET", "/api/v1/admin/products/1", AuthTestSupport.token("42"), null).statusCode()).isEqualTo(503);
        assertThat(business.hasKey(cache.userKey("42")).block()).isFalse();
        assertThat(business.hasKey(PermissionCacheKeys.lock(properties.keyPrefix(), "42")).block()).isFalse();
        assertThat(BACKEND.calls.get()).isZero();
        BACKEND.continueLoad.countDown();
    }

    private void invalidate(String userId) {
        business.execute(INVALIDATE, List.of(PermissionCacheKeys.epoch(properties.keyPrefix(), userId), cache.userKey(userId)),
                List.of(UUID.randomUUID().toString())).single().block(Duration.ofSeconds(3));
    }
    private static RbacModels.RuleSet rules(String version, String permission) {
        return new RbacModels.RuleSet(version, List.of(new RbacModels.Rule("admin-products",
                new RbacModels.Endpoint(Set.of("GET"), List.of("/api/v1/admin/products/**")), Set.of(), Set.of(permission), RbacModels.Match.ALL)));
    }
    private HttpRequest request(String method, String path, String token, String spoofedUser) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(6))
                .header("X-Request-Id", "auth-test-request").header("X-Internal-Token", "external-forged");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (spoofedUser != null) builder.header("X-User-Id", spoofedUser).header("X-User-Id", "888")
                .header("X-User-Roles", "ADMIN").header("X-User-Permissions", "product:write");
        return builder.method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString("{}")
                : HttpRequest.BodyPublishers.noBody()).build();
    }
    private HttpResponse<String> send(String method, String path, String token, String spoofedUser) throws Exception {
        var response = client.send(request(method, path, token, spoofedUser), HttpResponse.BodyHandlers.ofString());
        String id = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(id).matches("[0-9a-f]{16}");
        assertThat(id).isNotIn("auth-test-request", "backend-supplied-id");
        assertThat(response.headers().allValues("X-Request-Id")).containsExactly(id);
        var body = mapper.readTree(response.body());
        assertThat(body.get(body.has("requestId") ? "requestId" : "traceId").asText()).isEqualTo(id);
        return response;
    }
    private static void deleteTestKeys(ReactiveStringRedisTemplate redis) {
        var keys = redis.scan(ScanOptions.scanOptions().match(PREFIX + ":*").count(100).build()).collectList().block(Duration.ofSeconds(5));
        if (keys != null && !keys.isEmpty()) redis.delete(keys.toArray(String[]::new)).block(Duration.ofSeconds(5));
    }
}
