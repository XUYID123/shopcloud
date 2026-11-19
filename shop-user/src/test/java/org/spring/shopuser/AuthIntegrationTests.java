package org.spring.shopuser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.spring.shopuser.auth.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 真实 MySQL/MyBatis-Plus、BCrypt、JJWT、6380/6381 和 Servlet HTTP，不使用假用户表。 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.docker.compose.skip.in-tests=false", "shop.permissions.key-prefix=test:auth:permissions"})
class AuthIntegrationTests extends AuthIntegrationSupport {
    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JwtUtils jwt;
    @Autowired private RefreshTokenStore store;
    @Autowired private AuthProperties properties;
    @Autowired private AuthRedisConfiguration.Resources authRedis;
    @Autowired private StringRedisTemplate business;
    private final String username = "auth_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String PASSWORD = "FlourAuth123!";
    private long userId;
    private final Set<String> tokens = new HashSet<>();

    @BeforeEach void createOwnUser() {
        // JDK HttpURLConnection 的流式请求无法读取部分 401 响应，改用无认证重试的 JDK HttpClient。
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        http.getRestTemplate().setRequestFactory(factory);
        jdbc.update("INSERT INTO user_account(username,password_hash,status) VALUES (?,?,'ENABLED')", username, passwords.encode(PASSWORD));
        userId = jdbc.queryForObject("SELECT id FROM user_account WHERE username=?", Long.class, username);
    }
    @AfterEach void cleanup() {
        List<String> keys = new ArrayList<>(); keys.add(store.userKey(userId));
        tokens.forEach(token -> keys.add(store.lookupKey(token)));
        // 失败断言可能尚未来得及记录新 Token；只清理本 JVM 命名空间中、值等于本测试用户的反向索引。
        try (var cursor = authRedis.template().scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                .match(AUTH_PREFIX + ":refresh_lookup:*").count(100).build())) {
            cursor.forEachRemaining(key -> { if (Long.toString(userId).equals(authRedis.template().opsForValue().get(key))) keys.add(key); });
        }
        authRedis.template().delete(keys);
        jdbc.update("DELETE FROM user_account WHERE id=? AND username=?", userId, username);
    }

    @Test void loginUsesPlusAndBcryptReturnsSafeTokensAndOnlyStoresRefreshInSecurityRedis() throws Exception {
        var response = login(PASSWORD);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        JsonNode body = body(response);
        var data = body.path("data");
        String access = data.path("accessToken").asText(), token = remember(data);
        assertThat(jwt.parseAccessToken(access)).isEqualTo(new JwtUtils.Identity(userId, username));
        assertThat(data.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(data.path("expiresIn").asLong()).isEqualTo(1800);
        assertThat(data.path("refreshExpiresIn").asLong()).isEqualTo(604800);
        assertThat(data.toString()).doesNotContain("password", "passwordHash", "roles", "permissions");
        int securityPort = Integer.parseInt(System.getenv().getOrDefault("SHOP_GATEWAY_REDIS_PORT", "6380"));
        assertThat(authRedis.factory().getPort()).isEqualTo(securityPort);
        assertThat(authRedis.template().opsForValue().get(store.userKey(userId))).isEqualTo(token);
        assertThat(business.hasKey(store.userKey(userId))).isFalse();
        assertThat(authRedis.template().getExpire(store.userKey(userId), TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(properties.refreshTtl().toMillis());
    }

    @Test void invalidPasswordMissingAccountAndDisabledAccountHaveSame401AndNoRefreshState() throws Exception {
        var wrong = login("WrongAuth123!"); assertCode(wrong, 401, "INVALID_CREDENTIALS");
        var missing = post("/api/auth/login", Map.of("username", "missing_" + username, "password", PASSWORD));
        assertCode(missing, 401, "INVALID_CREDENTIALS");
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", userId);
        assertCode(login(PASSWORD), 401, "INVALID_CREDENTIALS");
        assertThat(authRedis.template().hasKey(store.userKey(userId))).isFalse();
    }

    @Test void refreshRotatesOncePreservesAbsoluteExpiryAndOldTokenCannotBeReused() throws Exception {
        String old = remember(body(login(PASSWORD)).path("data"));
        authRedis.template().expire(store.userKey(userId), Duration.ofSeconds(60));
        authRedis.template().expire(store.lookupKey(old), Duration.ofSeconds(60));
        var refreshed = post("/api/auth/refresh", Map.of("refreshToken", old));
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = body(refreshed).path("data"); String replacement = remember(data);
        assertThat(replacement).isNotEqualTo(old);
        assertThat(jwt.parseAccessToken(data.path("accessToken").asText()).userId()).isEqualTo(userId);
        assertThat(data.path("refreshExpiresIn").asLong()).isBetween(1L, 60L);
        assertThat(authRedis.template().hasKey(store.lookupKey(old))).isFalse();
        assertCode(post("/api/auth/refresh", Map.of("refreshToken", old)), 401, "INVALID_REFRESH_TOKEN");
    }

    @Test void concurrentRefreshOnlyOneRequestConsumesOldToken() throws Exception {
        String old = remember(body(login(PASSWORD)).path("data"));
        ExecutorService callers = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<String>>> pending = new ArrayList<>();
            for (int i = 0; i < 12; i++) pending.add(callers.submit(() -> { start.await(); return post("/api/auth/refresh", Map.of("refreshToken", old)); }));
            start.countDown(); int successes = 0;
            for (var future : pending) {
                var response = future.get(10, TimeUnit.SECONDS);
                if (response.getStatusCode() == HttpStatus.OK) { successes++; remember(body(response).path("data")); }
                else assertCode(response, 401, "INVALID_REFRESH_TOKEN");
            }
            assertThat(successes).isEqualTo(1);
        } finally { callers.shutdownNow(); }
    }

    @Test void reloginInvalidatesOldRefreshAndOldLogoutCannotDeleteNewSession() throws Exception {
        String old = remember(body(login(PASSWORD)).path("data"));
        String current = remember(body(login(PASSWORD)).path("data"));
        assertCode(post("/api/auth/refresh", Map.of("refreshToken", old)), 401, "INVALID_REFRESH_TOKEN");
        assertThat(post("/api/auth/logout", Map.of("refreshToken", old)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(store.resolve(current)).isEqualTo(userId);
        assertThat(post("/api/auth/logout", Map.of("refreshToken", current)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/auth/logout", Map.of("refreshToken", current)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertCode(post("/api/auth/refresh", Map.of("refreshToken", current)), 401, "INVALID_REFRESH_TOKEN");
    }

    @Test void jwtInterceptorIgnoresSpoofedIdentityAndLogoutDoesNotPretendToRevokeStatelessAccess() throws Exception {
        var data = body(login(PASSWORD)).path("data"); String token = remember(data);
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(data.path("accessToken").asText()); headers.set("X-User-Id", "999");
        headers.set("X-Internal-Token", org.spring.shopcommon.internalauth.InternalTestTokens.token("shop-user", Long.toString(userId), "user", "GET", "/api/v1/user"));
        // 既有 TestController 设计上返回业务 400，证明通过 JWT；没有 Token 则 401。
        var accepted = http.exchange("/api/v1/user", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertCode(http.getForEntity("/api/v1/user", String.class), 401, "INVALID_INTERNAL_TOKEN");
        post("/api/auth/logout", Map.of("refreshToken", token));
        assertThat(http.exchange("/api/v1/user", HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void forgedUnknownAndExpiredRefreshCredentialsAreRejectedWithoutUserIdInput() throws Exception {
        assertCode(post("/api/auth/refresh", Map.of("refreshToken", "not-a-uuid")), 401, "INVALID_REFRESH_TOKEN");
        assertCode(post("/api/auth/refresh", Map.of("refreshToken", RefreshTokenStore.newToken())), 401, "INVALID_REFRESH_TOKEN");
        String token = remember(body(login(PASSWORD)).path("data"));
        authRedis.template().delete(store.userKey(userId));
        assertCode(post("/api/auth/refresh", Map.of("refreshToken", token)), 401, "INVALID_REFRESH_TOKEN");
        assertCode(post("/api/auth/refresh", Map.of("refreshToken", token, "userId", "999")), 400, "VALIDATION_ERROR");
    }

    @Test void disabledUserCannotRefreshAndNeverGetsNewTokens() throws Exception {
        String token = remember(body(login(PASSWORD)).path("data"));
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", userId);
        assertCode(post("/api/auth/refresh", Map.of("refreshToken", token)), 401, "INVALID_REFRESH_TOKEN");
        assertThat(authRedis.template().hasKey(store.userKey(userId))).isFalse();
    }

    @Test void expiredInternalJwtIsRejectedBeforeBusiness() throws Exception {
        var headers = new HttpHeaders();
        headers.set("X-Internal-Token", org.spring.shopcommon.internalauth.InternalTestTokens.token("shop-user", Long.toString(userId), "user", "GET", "/api/v1/user", java.time.Instant.now().minusSeconds(60)));
        assertCode(http.exchange("/api/v1/user", HttpMethod.GET, new HttpEntity<>(headers), String.class),401,"INVALID_INTERNAL_TOKEN");
    }

    private ResponseEntity<String> login(String password) { return post("/api/auth/login", Map.of("username", username, "password", password)); }
    private ResponseEntity<String> post(String path, Object value) { var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", org.spring.shopcommon.internalauth.InternalTestTokens.token("shop-user", null, "anonymous", "POST", path));
        return http.postForEntity(path, new HttpEntity<>(value, headers), String.class); }
    private JsonNode body(ResponseEntity<String> response) throws Exception {
        var body = mapper.readTree(response.getBody());
        assertThat(body.path("traceId").asText()).isEqualTo(response.getHeaders().getFirst("X-Request-Id"));
        return body;
    }
    private String remember(JsonNode data) { String token = data.path("refreshToken").asText(); tokens.add(token); return token; }
    private void assertCode(ResponseEntity<String> response, int status, String code) throws Exception {
        assertThat(response.getStatusCode().value()).isEqualTo(status); assertThat(body(response).path("code").asText()).isEqualTo(code);
    }
}
