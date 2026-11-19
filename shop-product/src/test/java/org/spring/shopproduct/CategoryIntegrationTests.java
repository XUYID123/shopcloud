package org.spring.shopproduct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.spring.shopcommon.internalauth.InternalTestTokens;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 真实 HTTP/MySQL/Flyway，验证并发唯一约束及服务入口防绕过；仅清理自己的随机名称。 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test")
class CategoryIntegrationTests {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    private final String name = "test_cat_" + UUID.randomUUID().toString().replace("-", "");
    @BeforeEach void configureClient() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(15)); http.getRestTemplate().setRequestFactory(factory);
    }
    @AfterEach void cleanup() {
        // 只清理本测试随机命名的记录，不修改开发库里的其他分类。
        jdbc.update("DELETE FROM category WHERE name IN (?,?,?,?,?)", name, name+"_a", name+"_b", name+"_c", name+"_off");
    }

    @Test void listsEnabledCategoriesInStableOrderWithOnlyPublicFields() throws Exception {
        // 打乱插入顺序并加入一个排序更靠前的禁用分类，验证过滤和排序均在数据库完成。
        jdbc.update("INSERT INTO category(name,sort,enabled) VALUES (?,20,1),(?,10,1),(?,10,1),(?,0,0)",
                name+"_c", name+"_a", name+"_b", name+"_off");
        var response = http.exchange("/api/v1/categories", HttpMethod.GET, new HttpEntity<>(listHeaders()), String.class);
        assertCode(response, 200, "OK");
        var data = json.readTree(response.getBody()).path("data");
        assertThat(data.isArray()).isTrue();
        List<String> actualIds = new ArrayList<>();
        List<String> ownNames = new ArrayList<>();
        for (var item : data) {
            assertThat(item.size()).isEqualTo(3);
            assertThat(item.path("categoryId").isTextual()).isTrue();
            assertThat(item.has("name") && item.has("sort")).isTrue();
            actualIds.add(item.path("categoryId").asText());
            if (item.path("name").asText().startsWith(name)) ownNames.add(item.path("name").asText());
        }
        assertThat(ownNames).containsExactly(name+"_a", name+"_b", name+"_c");
        assertThat(actualIds).containsExactlyElementsOf(jdbc.queryForList(
                "SELECT CAST(id AS CHAR) FROM category WHERE enabled=1 ORDER BY sort,id", String.class));
    }

    @Test void publicListStillRequiresGatewaySignedInternalJwt() throws Exception {
        // 匿名是无需外部 Access Token；绕过网关直连商品服务仍然需要内部签名。
        var forged = new HttpHeaders(); forged.set("X-User-Id", "1");
        assertCode(http.exchange("/api/v1/categories", HttpMethod.GET, new HttpEntity<>(forged), String.class),
                401, "INVALID_INTERNAL_TOKEN");
        forged.set("X-Internal-Token", "old-static-token");
        assertCode(http.exchange("/api/v1/categories", HttpMethod.GET, new HttpEntity<>(forged), String.class),
                401, "INVALID_INTERNAL_TOKEN");
    }

    private HttpHeaders listHeaders() {
        var headers = new HttpHeaders();
        headers.set("X-Internal-Token", InternalTestTokens.token("shop-product", null, "anonymous", "GET", "/api/v1/categories"));
        headers.set("X-Trace-Id", "category-list-trace");
        return headers;
    }

    @Test void createsTrimmedCategoryWith201LocationSafeVoAndConsistentTrace() throws Exception {
        var response = post(Map.of("name", "  " + name + "  ", "sort", 1, "enabled", false), headers());
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        var body = json.readTree(response.getBody()); var data = body.path("data");
        assertThat(data.path("name").asText()).isEqualTo(name);
        assertThat(data.path("sort").asInt()).isEqualTo(1);
        assertThat(data.path("enabled").asBoolean()).isFalse();
        assertThat(data.path("categoryId").isTextual()).isTrue();
        assertThat(data.size()).isEqualTo(4);
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("/api/v1/admin/categories/" + data.path("categoryId").asText());
        assertThat(body.path("traceId").asText()).isEqualTo("category-test-trace");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("category-test-trace");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE name=? AND enabled=0 AND created_at IS NOT NULL AND updated_at IS NOT NULL", Integer.class, name)).isEqualTo(1);
    }

    @Test void duplicateNameAndDatabaseCollationBothReturn409() throws Exception {
        assertThat(post(input(), headers()).getStatusCode().value()).isEqualTo(201);
        assertCode(post(input(), headers()), 409, "CATEGORY_NAME_EXISTS");
        var uppercase = new HashMap<>(input()); uppercase.put("name", name.toUpperCase(Locale.ROOT));
        assertCode(post(uppercase, headers()), 409, "CATEGORY_NAME_EXISTS");
    }

    @Test void rejectsMissingFieldsInvalidRangesExtraFieldsAndScalarCoercion() throws Exception {
        List<Map<String, Object>> invalid = new ArrayList<>();
        invalid.add(Map.of());
        invalid.add(Map.of("name", "   ", "sort", 0, "enabled", true));
        invalid.add(Map.of("name", "x".repeat(51), "sort", 0, "enabled", true));
        invalid.add(Map.of("name", name, "sort", -1, "enabled", true));
        invalid.add(Map.of("name", name, "sort", 10001, "enabled", true));
        invalid.add(Map.of("name", name, "sort", 0));
        invalid.add(Map.of("name", name, "sort", 1.5, "enabled", true));
        invalid.add(Map.of("name", name, "sort", "1", "enabled", true));
        invalid.add(Map.of("name", 123, "sort", 0, "enabled", true));
        invalid.add(Map.of("name", name, "sort", 0, "enabled", true, "categoryId", "999"));
        for (var input : invalid) assertCode(post(input, headers()), 400, "VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE name=?", Integer.class, name)).isZero();
    }

    @Test void directCallsCannotBypassGatewayUsingBearerOrForgedIdentity() throws Exception {
        var missing = new HttpHeaders(); missing.setContentType(MediaType.APPLICATION_JSON);
        assertCode(post(input(), missing), 401, "INVALID_INTERNAL_TOKEN");
        missing.set("Authorization", "Bearer forged-token"); missing.set("X-User-Id", "1");
        assertCode(post(input(), missing), 401, "INVALID_INTERNAL_TOKEN");
        missing.set("X-Internal-Token", "wrong-internal-token");
        assertCode(post(input(), missing), 401, "INVALID_INTERNAL_TOKEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE name=?", Integer.class, name)).isZero();
    }

    /** 使用真实 HTTP 入口，验证其他服务/接口的凭据和过期、篡改凭据不会执行 INSERT。 */
    @Test void rejectsWrongAudienceExpiredTamperedAndMisboundInternalJwt() throws Exception {
        String path = "/api/v1/admin/categories";
        String valid = InternalTestTokens.token("shop-product", "42", "user", "POST", path);
        String[] parts = valid.split("\\.");
        parts[2] = (parts[2].startsWith("a") ? "b" : "a") + parts[2].substring(1);
        var rejected = List.of(
                InternalTestTokens.token("shop-user", "42", "user", "POST", path),
                InternalTestTokens.token("shop-product", "42", "user", "POST", path, Instant.now().minusSeconds(90)),
                InternalTestTokens.token("shop-product", "42", "user", "POST", "/api/v1/admin/products"),
                InternalTestTokens.token("shop-product", "42", "user", "GET", path),
                String.join(".", parts));
        for (String token : rejected) {
            var headers = headers();
            headers.set("X-Internal-Token", token);
            // 同时伪造身份/权限头，也不能挽救无效签名或错误请求绑定。
            headers.set("X-User-Id", "1"); headers.set("X-User-Permissions", "product:write");
            assertCode(post(input(), headers), 401, "INVALID_INTERNAL_TOKEN");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE name=?", Integer.class, name)).isZero();
    }

    /** 有效签名也必须符合用途：匿名凭据与权限冷加载凭据不能用于分类写入。 */
    @Test void rejectsAnonymousAndPermissionQueryCredentialsOnCategoryCreation() throws Exception {
        for (String kind : List.of("anonymous", "permission-query")) {
            var headers = headers();
            headers.set("X-Internal-Token", InternalTestTokens.token("shop-product",
                    kind.equals("anonymous") ? null : "42", kind, "POST", "/api/v1/admin/categories"));
            headers.set("X-User-Id", "1"); headers.set("X-User-Roles", "ADMIN");
            assertCode(post(input(), headers), 403, "FORBIDDEN");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE name=?", Integer.class, name)).isZero();
    }

    @Test void twelveConcurrentCreatesProduceOneRowAndElevenConflicts() throws Exception {
        var executor = Executors.newFixedThreadPool(12); var start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<String>>> responses = new ArrayList<>();
            for (int i = 0; i < 12; i++) responses.add(executor.submit(() -> { start.await(); return post(input(), headers()); }));
            start.countDown(); int created = 0, conflicts = 0;
            for (var pending : responses) {
                var response = pending.get(20, TimeUnit.SECONDS);
                if (response.getStatusCode().value() == 201) created++;
                else { assertCode(response, 409, "CATEGORY_NAME_EXISTS"); conflicts++; }
            }
            assertThat(created).isEqualTo(1); assertThat(conflicts).isEqualTo(11);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE name=?", Integer.class, name)).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
    private Map<String, Object> input() { return Map.of("name", name, "sort", 0, "enabled", true); }
    private HttpHeaders headers() {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        // 正常请求仅提供签名身份：没有外部 X-User-Id，仍应成功创建分类。
        headers.set("X-Internal-Token", InternalTestTokens.token("shop-product", "42", "user", "POST", "/api/v1/admin/categories"));
        headers.set("X-Trace-Id", "category-test-trace"); return headers;
    }
    private ResponseEntity<String> post(Object body, HttpHeaders headers) {
        return http.postForEntity("/api/v1/admin/categories", new HttpEntity<>(body, headers), String.class);
    }
    private void assertCode(ResponseEntity<String> response, int status, String code) throws Exception {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(json.readTree(response.getBody()).path("code").asText()).isEqualTo(code);
        // 拒绝响应同样延续公共 Trace Filter 产生的关联 ID。
        String trace = response.getHeaders().getFirst("X-Trace-Id");
        assertThat(trace).isNotBlank();
        assertThat(json.readTree(response.getBody()).path("traceId").asText()).isEqualTo(trace);
    }
}
