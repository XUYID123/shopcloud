package org.spring.shopgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spring.shopgateway.config.ProtectionProperties;
import org.spring.shopgateway.protection.RedisAbuseLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.protection.sentinel-qps=10000",
        "gateway.protection.anti-abuse.requests-per-window=30",
        "gateway.protection.anti-abuse.policies.register.limit=2",
        "gateway.protection.anti-abuse.policies.login.limit=2",
        "gateway.protection.anti-abuse.policies.checkout.methods[0]=POST",
        "gateway.protection.anti-abuse.policies.checkout.paths[0]=/api/v1/checkout/**",
        "gateway.protection.anti-abuse.policies.checkout.limit=1",
        "gateway.protection.anti-abuse.window=30s"
})
class GatewayAntiAbuseTests {
    private static final HttpBackend BACKEND = new HttpBackend();
    private static final String PREFIX = "gateway:test:" + UUID.randomUUID().toString().replace("-", "");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @LocalServerPort private int port;
    @Autowired private ReactiveStringRedisTemplate redis;
    @Autowired private ObjectMapper mapper;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("gateway.protection.anti-abuse.key-prefix", () -> PREFIX);
        for (String service : List.of("shop-user", "shop-product", "shop-order")) {
            registry.add("spring.cloud.discovery.client.simple.instances." + service + "[0].uri", BACKEND::uri);
        }
    }

    @BeforeEach
    @AfterEach
    void cleanup() {
        List<String> keys = keys();
        if (!keys.isEmpty()) {
            redis.delete(keys.toArray(String[]::new)).block(Duration.ofSeconds(5));
        }
        BACKEND.calls.set(0);
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.close();
    }

    @Test
    void registrationAliasesAndEncodedPathsShareOneQuotaWithoutTrustingSpoofedHeaders() throws Exception {
        assertThat(send("POST", "/api/v1/users", "198.51.100.1").statusCode()).isEqualTo(200);
        assertThat(send("POST", "/shop-user/api/v1/%75sers?from=alias", "198.51.100.2").statusCode()).isEqualTo(200);
        HttpResponse<String> rejected = send("POST", "/shop-user/api/v1/users/", "198.51.100.3");
        assertThat(rejected.statusCode()).isEqualTo(429);
        JsonNode body = mapper.readTree(rejected.body());
        assertThat(body.get("code").asText()).isEqualTo("ABUSE_LIMITED");
        String requestId = body.get("traceId").asText();
        assertThat(requestId).matches("[0-9a-f]{16}");
        assertThat(requestId).isNotEqualTo("gateway-test-request");
        assertThat(body.has("data")).isTrue();
        assertThat(rejected.headers().allValues("X-Request-Id")).containsExactly(requestId);
        assertThat(Long.parseLong(rejected.headers().firstValue("Retry-After").orElseThrow())).isPositive();
        assertThat(send("POST", "/api/v1/users;source=alias", "198.51.100.4").statusCode()).isEqualTo(429);
        assertThat(BACKEND.calls.get()).isEqualTo(2);
        assertThat(send("GET", "/api/v1/products/1", "198.51.100.4").statusCode()).isEqualTo(200);
    }

    @Test
    void repeatedViolationsProduceAnExpiringBan() throws Exception {
        send("POST", "/api/v1/users", "198.51.100.1");
        send("POST", "/api/v1/users", "198.51.100.1");
        for (int i = 0; i < 2; i++) {
            assertThat(send("POST", "/api/v1/users", "198.51.100.1").statusCode()).isEqualTo(429);
        }
        HttpResponse<String> banned = send("POST", "/api/v1/users", "198.51.100.1");
        assertThat(banned.statusCode()).isEqualTo(429);
        assertThat(Long.parseLong(banned.headers().firstValue("Retry-After").orElseThrow())).isGreaterThan(290);
        String banKey = keys().stream().filter(key -> key.endsWith(":ban")).findFirst().orElseThrow();
        assertThat(redis.getExpire(banKey).block()).isBetween(Duration.ofSeconds(290), Duration.ofMinutes(5));
        assertThat(BACKEND.calls.get()).isEqualTo(2);
    }

    @Test
    void loginQuotaCountsAttemptsAndDoesNotBlockReadsOfTheSamePath() throws Exception {
        assertThat(send("POST", "/api/v1/auth/sessions", "198.51.100.1").statusCode()).isEqualTo(200);
        assertThat(send("POST", "/shop-user/api/v1/auth/sessions", "198.51.100.2").statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/v1/auth/sessions", "198.51.100.3").statusCode()).isEqualTo(429);
        assertThat(send("GET", "/api/v1/auth/sessions", "198.51.100.4").statusCode()).isEqualTo(200);
    }

    @Test
    void anAdditionalConfiguredPolicyProtectsANewBusinessPathWithoutFilterChanges() throws Exception {
        assertThat(send("POST", "/api/v1/checkout/quotes", "198.51.100.1").statusCode()).isEqualTo(200);
        assertThat(send("POST", "/shop-order/api/v1/checkout/quotes", "198.51.100.2").statusCode()).isEqualTo(429);
        assertThat(send("GET", "/api/v1/checkout/quotes", "198.51.100.3").statusCode()).isEqualTo(200);
        assertThat(BACKEND.calls.get()).isEqualTo(2);
    }

    @Test
    void generalIpQuotaAppliesAcrossDifferentResources() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(send("GET", "/api/v1/products/" + i, "198.51.100." + i).statusCode()).isEqualTo(200);
        }
        assertThat(send("GET", "/api/v1/users/me", "203.0.113.8").statusCode()).isEqualTo(429);
        assertThat(BACKEND.calls.get()).isEqualTo(30);
    }

    @Test
    void untrustedForwardingHeadersAreReplacedBeforeReachingTheBackend() throws Exception {
        var response = send("GET", "/api/v1/users/me", "198.51.100.99");
        JsonNode body = mapper.readTree(response.body());
        String requestId = body.get("requestId").asText();
        assertThat(requestId).matches("[0-9a-f]{16}");
        assertThat(requestId).isNotEqualTo("gateway-test-request");
        assertThat(response.headers().allValues("X-Request-Id")).containsExactly(requestId);
        assertThat(body.get("forwardedFor").asText()).isEqualTo("127.0.0.1");
    }

    @Test
    void twoLimiterInstancesShareAnAtomicQuotaUnderConcurrency() {
        ProtectionProperties properties = properties("atomic", Duration.ofSeconds(30), Duration.ofMinutes(5));
        RedisAbuseLimiter first = new RedisAbuseLimiter(redis, properties);
        RedisAbuseLimiter second = new RedisAbuseLimiter(redis, properties);
        List<RedisAbuseLimiter.Decision> decisions = Flux.range(0, 40)
                .flatMap(index -> (index % 2 == 0 ? first : second).check("register", "198.51.100.42", 5), 16)
                .collectList().block(Duration.ofSeconds(10));
        assertThat(decisions).hasSize(40);
        assertThat(decisions.stream().filter(RedisAbuseLimiter.Decision::allowed).count()).isEqualTo(5);
        assertThat(keys()).allMatch(key -> !key.contains("198.51.100.42"));
        for (String key : keys()) {
            assertThat(redis.getExpire(key).block()).isPositive();
            if (key.endsWith(":events")) {
                assertThat(redis.opsForZSet().size(key).block()).isLessThanOrEqualTo(5L);
            }
        }
    }

    @Test
    void quotasAndBansRecoverAfterExpiry() {
        RedisAbuseLimiter limiter = new RedisAbuseLimiter(redis,
                properties("expiry", Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThat(limiter.check("register", "198.51.100.77", 1).block().allowed()).isTrue();
        assertThat(limiter.check("register", "198.51.100.77", 1).block().allowed()).isFalse();
        assertThat(limiter.check("register", "198.51.100.77", 1).block().allowed()).isFalse();
        assertThat(limiter.check("register", "198.51.100.77", 1).block().banned()).isTrue();
        Mono.delay(Duration.ofMillis(1100)).block();
        assertThat(limiter.check("register", "198.51.100.77", 1).block().allowed()).isTrue();
    }

    private ProtectionProperties properties(String suffix, Duration window, Duration ban) {
        return new ProtectionProperties(10000, new ProtectionProperties.AntiAbuse(
                true, 30, window, 3, ban, Duration.ofSeconds(1), PREFIX + ":" + suffix, Map.of()), List.of());
    }

    private List<String> keys() {
        return redis.scan(ScanOptions.scanOptions().match(PREFIX + ":*").count(100).build())
                .collectList().block(Duration.ofSeconds(5));
    }

    private HttpResponse<String> send(String method, String path, String spoofedIp) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("X-Request-Id", "gateway-test-request")
                .header("X-Forwarded-For", spoofedIp).header("X-Real-IP", spoofedIp)
                .header("Forwarded", "for=" + spoofedIp);
        builder.method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString("{}")
                : HttpRequest.BodyPublishers.noBody());
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
