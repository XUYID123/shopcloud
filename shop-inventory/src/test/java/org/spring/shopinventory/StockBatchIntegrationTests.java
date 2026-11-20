package org.spring.shopinventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.spring.shopcommon.internalauth.InternalJwtProtocol;
import org.spring.shopcommon.internalauth.ScopedServiceJwt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 实际 MVC/Feign/MySQL 流程。商品端替身验签并模拟稳定或失败的商品元数据响应。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.docker.compose.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "shop.internal-auth.public-key-location=file:../infra/security/test-fixtures/internal-test-public.pem",
        "shop.inventory.signing-private-key-location=file:../infra/security/test-fixtures/internal-test-private.pem"
})
class StockBatchIntegrationTests {
    static final String PATH = "/api/v1/admin/inventory/batches";
    static final String TRACE = "d7865bd561e70bc5";
    static final AtomicBoolean PRODUCT_HAS_TRACE = new AtomicBoolean();
    static final HttpServer PRODUCT = productStub();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final long sku = uniqueId();
    final long actor = uniqueId();
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @DynamicPropertySource
    static void productAddress(DynamicPropertyRegistry registry) {
        registry.add("shop.inventory.product-url", () -> "http://127.0.0.1:" + PRODUCT.getAddress().getPort());
    }

    @AfterAll static void stopProduct() { PRODUCT.stop(0); }

    @AfterEach void cleanTestRows() {
        jdbc.update("DELETE FROM stock_batch_receipt WHERE actor_id=?", actor);
        jdbc.update("DELETE FROM stock_ledger WHERE actor_id=?", actor);
        jdbc.update("DELETE FROM stock_batch WHERE sku_id=?", sku);
        jdbc.update("DELETE FROM sku_stock WHERE sku_id=?", sku);
    }

    @Test void createsStockAndReplaysTheOriginalSnapshotWithTrace() throws Exception {
        var input = input();
        var key = key();
        var response = post(input, key);
        check(response, 201, "OK");
        var data = body(response).path("data");
        assertThat(data.size()).isEqualTo(12);
        assertThat(data.path("skuId").asText()).isEqualTo(Long.toString(sku));
        assertThat(data.path("batchId").isTextual()).isTrue();
        assertThat(data.path("qualityStatus").asText()).isEqualTo("SELLABLE");
        assertThat(data.path("available").asLong()).isEqualTo(100);
        assertThat(data.path("version").asLong()).isEqualTo(1);
        for (String field : Set.of("reserved", "committed", "unavailable")) assertThat(data.path(field).asLong()).isZero();
        assertThat(PRODUCT_HAS_TRACE).isTrue();
        long batch = data.path("batchId").asLong();
        assertThat(jdbc.queryForObject("SELECT delta_available FROM stock_ledger WHERE batch_id=? AND actor_id=?",
                Long.class, batch, actor)).isEqualTo(100L);
        assertRows(1, 100, 1);

        // 模拟后续库存状态变化。重放仍返回首次响应，锁定前无需再查商品服务。
        jdbc.update("UPDATE stock_batch SET available=0, reserved=100, version=2 WHERE batch_id=?", batch);
        var replay = post(input, key);
        check(replay, 200, "OK");
        assertThat(body(replay).path("data")).isEqualTo(data);
        input.put("quantity", 101);
        check(post(input, key), 409, "IDEMPOTENCY_CONFLICT");
        assertRows(1, 100, 1);
    }

    @Test void serializesSameKeyWithExactlyOneWrite() throws Exception {
        var input = input();
        var key = key();
        var responses = concurrent(input, key, input, key);
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 200);
        assertThat(body(responses.get(0)).path("data")).isEqualTo(body(responses.get(1)).path("data"));
        assertRows(1, 100, 1);
    }

    @Test void rollsBackAggregateAndClaimWhenConcurrentBatchNumberConflicts() throws Exception {
        var input = input();
        var responses = concurrent(input, key(), input, key());
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 409);
        check(responses.stream().filter(item -> item.statusCode() == 409).findFirst().orElseThrow(), 409, "STOCK_BATCH_EXISTS");
        assertRows(1, 100, 1);
    }

    @Test void concurrentDistinctBatchesAccumulateWithoutLostUpdates() throws Exception {
        var first = input();
        var other = input();
        other.put("batchNo", "SECOND");
        var responses = concurrent(first, key(), other, key());
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactly(201, 201);
        assertRows(2, 200, 2);
    }

    @Test void validatesRequestAndBusinessDatesBeforeAnyWrite() throws Exception {
        check(post(input(), null), 400, "VALIDATION_ERROR");
        check(post(input(), "spaces not allowed"), 400, "VALIDATION_ERROR");
        Object[][] invalid = {{"skuId", "9223372036854775808"}, {"skuId", sku}, {"skuId", "01"},
                {"quantity", "1"}, {"quantity", 1.5}, {"quantity", 1_000_001}, {"quantity", 0},
                {"warehouseId", "WH-OTHER"}, {"batchNo", "   "}, {"batchNo", "has\ncontrol"},
                {"available", 100}, {"manufacturedOn", "2026-02-30"}, {"actorId", 1}};
        for (var invalidField : invalid) {
            var input = input();
            input.put((String) invalidField[0], invalidField[1]);
            check(post(input, key()), 400, "VALIDATION_ERROR");
        }
        LocalDate today = today();
        var input = input();
        input.put("expiresOn", today.toString());
        check(post(input, key()), 422, "INVALID_BATCH_DATES");
        input.put("manufacturedOn", today.plusDays(1).toString());
        input.put("expiresOn", today.plusDays(7).toString());
        check(post(input, key()), 422, "INVALID_BATCH_DATES");
        input.put("manufacturedOn", today.minusDays(1).toString());
        input.put("expiresOn", today.plusDays(181).toString());
        check(post(input, key()), 422, "INVALID_BATCH_DATES");
        assertRows(0, 0, 0);
    }

    @Test void keepsSkuNotFoundAndDependencyFailureSeparateAndRejectsUntrustedIdentity() throws Exception {
        var input = input();
        input.put("skuId", Long.toString(Long.MAX_VALUE));
        check(post(input, key()), 404, "SKU_NOT_FOUND");
        input.put("skuId", Long.toString(Long.MAX_VALUE - 1));
        check(post(input, key()), 503, "DEPENDENCY_UNAVAILABLE");
        input.put("skuId", Long.toString(Long.MAX_VALUE - 2));
        check(post(input, key()), 503, "DEPENDENCY_UNAVAILABLE");
        check(post(input(), key(), null), 401, "INVALID_INTERNAL_TOKEN");
        check(post(input(), key(), gatewayToken("shop-product", Long.toString(actor), "user", Instant.now())),
                401, "INVALID_INTERNAL_TOKEN");
        check(post(input(), key(), gatewayToken("shop-inventory", Long.toString(actor), "user", Instant.now().minusSeconds(90))),
                401, "INVALID_INTERNAL_TOKEN");
        check(post(input(), key(), gatewayToken("shop-inventory", "anonymous", "anonymous", Instant.now())), 403, "FORBIDDEN");
        assertRows(0, 0, 0);
    }

    private void assertRows(int batches, long available, int receipts) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_batch WHERE sku_id=?", Integer.class, sku)).isEqualTo(batches);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(available),0) FROM sku_stock WHERE sku_id=?", Long.class, sku)).isEqualTo(available);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE actor_id=?", Integer.class, actor)).isEqualTo(batches);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_batch_receipt WHERE actor_id=?", Integer.class, actor)).isEqualTo(receipts);
    }

    private java.util.List<HttpResponse<String>> concurrent(Map<String, Object> first, String firstKey,
                                                            Map<String, Object> second, String secondKey) throws Exception {
        var start = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return post(first, firstKey); });
            var two = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return post(second, secondKey); });
            return java.util.List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private HttpResponse<String> post(Map<String, Object> input, String key) throws Exception {
        return post(input, key, gatewayToken("shop-inventory", Long.toString(actor), "user", Instant.now()));
    }

    private HttpResponse<String> post(Map<String, Object> input, String key, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-Trace-Id", TRACE).header("X-User-Id", "1");
        if (key != null) req.header("Idempotency-Key", key);
        if (token != null) req.header(InternalJwtProtocol.HEADER, token);
        return HTTP.send(req.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> result) throws IOException { return json.readTree(result.body()); }

    private void check(HttpResponse<String> result, int status, String code) throws IOException {
        assertThat(result.statusCode()).as(result.body()).isEqualTo(status);
        assertThat(body(result).path("code").asText()).isEqualTo(code);
        assertThat(body(result).path("traceId").asText()).isEqualTo(TRACE);
    }

    private Map<String, Object> input() {
        LocalDate today = today();
        return new LinkedHashMap<>(Map.of("skuId", Long.toString(sku), "warehouseId", "WH-SH-01", "batchNo", "TEST-001",
                "manufacturedOn", today.minusDays(1).toString(), "expiresOn", today.plusDays(90).toString(), "quantity", 100));
    }

    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static long uniqueId() { return 1_000_000_000L + (UUID.randomUUID().getLeastSignificantBits() & 0x0000_FFFF_FFFF_FFFFL); }

    private static String gatewayToken(String audience, String actor, String kind, Instant issued) throws Exception {
        var key = ScopedServiceJwt.privateKey(fixture("private"));
        return Jwts.builder().header().type(InternalJwtProtocol.TYPE).and().issuer(InternalJwtProtocol.ISSUER)
                .subject(actor).claim("userId", actor).claim("kind", kind).claim("permissionsDigest", InternalJwtProtocol.digest("test"))
                .audience().add(audience).and().id(key()).issuedAt(Date.from(issued)).expiration(Date.from(issued.plusSeconds(45)))
                .claim("method", "POST").claim("path", PATH).claim("queryHash", InternalJwtProtocol.digest(""))
                .signWith(key, Jwts.SIG.RS256).compact();
    }

    private static FileSystemResource fixture(String kind) {
        return new FileSystemResource("../infra/security/test-fixtures/internal-test-" + kind + ".pem");
    }

    private static HttpServer productStub() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var scope = new ScopedServiceJwt.Scope("shop-inventory", "shop-product", "product:stock-metadata:read");
            var verifier = new ScopedServiceJwt.Verifier(ScopedServiceJwt.publicKey(fixture("public")), scope, Clock.systemUTC());
            server.createContext("/internal/v1/products/skus/", exchange -> {
                try (exchange) {
                    String path = exchange.getRequestURI().getRawPath();
                    int status = 200;
                    String body = "{\"code\":\"OK\",\"data\":{\"shelfLifeDays\":180}}";
                    try {
                        verifier.verify(exchange.getRequestHeaders().getFirst(InternalJwtProtocol.HEADER), exchange.getRequestMethod(), path, null);
                        PRODUCT_HAS_TRACE.set(TRACE.equals(exchange.getRequestHeaders().getFirst("X-Trace-Id")));
                        if (path.endsWith("/" + Long.MAX_VALUE + "/stock-metadata")) {
                            status = 404;
                            body = "{\"code\":\"SKU_NOT_FOUND\"}";
                        } else if (path.endsWith("/" + (Long.MAX_VALUE - 1) + "/stock-metadata")) {
                            status = 503;
                            body = "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}";
                        } else if (path.endsWith("/" + (Long.MAX_VALUE - 2) + "/stock-metadata")) {
                            status = 404;
                            body = "{\"code\":\"NOT_FOUND\"}";
                        }
                    } catch (IllegalArgumentException invalid) {
                        status = 401;
                        body = "{\"code\":\"INVALID_INTERNAL_TOKEN\"}";
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            return server;
        } catch (Exception unavailable) {
            throw new IllegalStateException("Cannot start product metadata fixture", unavailable);
        }
    }
}
