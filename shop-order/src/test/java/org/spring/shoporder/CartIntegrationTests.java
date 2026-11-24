package org.spring.shoporder;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CartIntegrationTests {
    private static final String PATH = "/api/v1/cart/items";
    private static final String PRODUCT_PATH = "/internal/v1/products/skus/cart-metadata";
    private static final String TRACE = "cart-integration-check";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Map<String, Long> PRICES = new ConcurrentHashMap<>();
    private static final Set<String> UNAVAILABLE = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean FAIL_PRODUCT = new AtomicBoolean();
    private static final AtomicReference<List<String>> LAST_BATCH = new AtomicReference<>();
    private static final RSAPrivateKey GATEWAY_KEY = gatewayKey();
    private static final HttpServer PRODUCT = productStub();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @LocalServerPort private int port;
    private final long user = ThreadLocalRandom.current().nextLong(1_000_000, 1_000_000_000);
    private final long other = user + 1;
    private final long sku = user + 100;

    @DynamicPropertySource
    static void productAddress(DynamicPropertyRegistry registry) {
        registry.add("shop.order.cart.product-url", () -> "http://127.0.0.1:" + PRODUCT.getAddress().getPort());
    }

    @AfterAll static void stopProduct() { PRODUCT.stop(0); }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM cart_item WHERE user_id IN (?,?)", user, other);
        jdbc.update("DELETE FROM cart_owner WHERE user_id IN (?,?)", user, other);
        PRICES.clear();
        UNAVAILABLE.clear();
        FAIL_PRODUCT.set(false);
    }

    @Test void ownerCanSetListAndRemoveWithAbsoluteQuantityAndNoIdentityOverride() throws Exception {
        assertThat(check(sendUser("GET", PATH, null, user), 200, "OK").path("data").isEmpty()).isTrue();
        check(put(sku, 3, user), 200, "OK");
        var replay = check(put(sku, 3, user), 200, "OK").path("data");
        assertThat(replay.path("skuId").textValue()).isEqualTo(Long.toString(sku));
        assertThat(replay.path("quantity").asInt()).isEqualTo(3);
        assertThat(replay.path("priceFen").asInt()).isEqualTo(1200);
        assertThat(replay.path("availableForSale").asBoolean()).isTrue();
        check(put(sku, 2, user), 200, "OK");
        check(put(sku, 5, other), 200, "OK");
        assertThat(check(sendUser("GET", PATH, null, user), 200, "OK").path("data").get(0).path("quantity").asInt())
                .isEqualTo(2);
        for (int count = 0; count < 2; count++) {
            assertThat(check(sendUser("DELETE", PATH + "/" + sku, null, user), 200, "OK").path("data").isNull())
                    .isTrue();
        }
        assertThat(check(sendUser("GET", PATH, null, user), 200, "OK").path("data").isEmpty()).isTrue();
        assertThat(check(sendUser("GET", PATH, null, other), 200, "OK").path("data").get(0).path("quantity").asInt())
                .isEqualTo(5);
    }

    @Test void resolvesFreshPriceAndPreservesAnUnavailableSkuInOneProductBatch() throws Exception {
        check(put(sku, 1, user), 200, "OK");
        check(put(sku + 1, 4, user), 200, "OK");
        PRICES.put(Long.toString(sku), 1700L);
        UNAVAILABLE.add(Long.toString(sku + 1));
        JsonNode items = check(sendUser("GET", PATH, null, user), 200, "OK").path("data");
        assertThat(LAST_BATCH.get()).containsExactly(Long.toString(sku), Long.toString(sku + 1));
        assertThat(items.get(0).path("priceFen").asLong()).isEqualTo(1700);
        assertThat(items.get(1).path("availableForSale").asBoolean(true)).isFalse();
        assertThat(items.get(1).path("quantity").asInt()).isEqualTo(4);
        for (String field : List.of("priceFen", "productName", "specName", "weightGram")) {
            assertThat(items.get(1).path(field).isNull()).isTrue();
        }
        check(put(sku + 1, 5, user), 404, "SKU_NOT_FOUND");
        assertThat(quantity(sku + 1)).isEqualTo(4);
    }

    @Test void rejectsBadQuantityForeignAudienceAndUnsignedIdentity() throws Exception {
        String path = PATH + "/" + sku;
        for (String body : List.of("{}", "{\"quantity\":0}", "{\"quantity\":101}", "{\"quantity\":null}",
                "{\"quantity\":\"3\"}", "{\"quantity\":2.3}", "{\"quantity\":1e0}",
                "{\"quantity\":2,\"quantity\":3}", "{\"quantity\":3,\"userId\":4}")) {
            check(sendUser("PUT", path, body, user), 400, "VALIDATION_ERROR");
        }
        check(sendUser("PUT", PATH + "/9223372036854775808", "{\"quantity\":3}", user), 400, "VALIDATION_ERROR");
        check(send("GET", PATH, null, null), 401, "INVALID_INTERNAL_TOKEN");
        check(send("GET", PATH, null, token("shop-user", Long.toString(user), "user", "GET", PATH)),
                401, "INVALID_INTERNAL_TOKEN");
        check(send("GET", PATH, null, token("shop-order", "anonymous", "anonymous", "GET", PATH)),
                403, "FORBIDDEN");
        assertThat(rowCount()).isZero();
    }

    @Test void parallelNewSkusCannotExceedTheLimitAndExistingSkuCanStillBeChanged() throws Exception {
        jdbc.update("INSERT INTO cart_owner (user_id) VALUES (?)", user);
        for (int offset = 0; offset < 19; offset++) {
            jdbc.update("INSERT INTO cart_item (user_id,sku_id,quantity) VALUES (?,?,1)", user, sku + offset);
        }
        var workers = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        try {
            var first = workers.submit(() -> { start.await(5, TimeUnit.SECONDS); return put(sku + 19, 2, user); });
            var second = workers.submit(() -> { start.await(5, TimeUnit.SECONDS); return put(sku + 20, 2, user); });
            var results = List.of(first.get(12, TimeUnit.SECONDS), second.get(12, TimeUnit.SECONDS));
            assertThat(results.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 409);
            check(results.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow(),
                    409, "CART_LIMIT_EXCEEDED");
        } finally {
            workers.shutdownNow();
        }
        check(put(sku, 100, user), 200, "OK");
        assertThat(rowCount()).isEqualTo(20);
        assertThat(quantity(sku)).isEqualTo(100);
    }

    @Test void productFailureKeepsRowsUnchangedAndDeleteDoesNotNeedProduct() throws Exception {
        check(put(sku, 2, user), 200, "OK");
        FAIL_PRODUCT.set(true);
        check(put(sku, 10, user), 503, "DEPENDENCY_UNAVAILABLE");
        check(sendUser("GET", PATH, null, user), 503, "DEPENDENCY_UNAVAILABLE");
        assertThat(quantity(sku)).isEqualTo(2);
        check(sendUser("DELETE", PATH + "/" + sku, null, user), 200, "OK");
        check(sendUser("GET", PATH, null, user), 200, "OK");
        assertThat(rowCount()).isZero();
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cart_item WHERE user_id=?", Integer.class, user);
    }

    private int quantity(long skuId) {
        return jdbc.queryForObject("SELECT quantity FROM cart_item WHERE user_id=? AND sku_id=?",
                Integer.class, user, skuId);
    }

    private HttpResponse<String> put(long skuId, int quantity, long owner) throws Exception {
        return sendUser("PUT", PATH + "/" + skuId, "{\"quantity\":" + quantity + "}", owner);
    }

    private HttpResponse<String> sendUser(String method, String path, String body, long owner) throws Exception {
        return send(method, path, body, token("shop-order", Long.toString(owner), "user", method, path));
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(8)).header("X-User-Id", Long.toString(other))
                .header("X-Trace-Id", TRACE).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header(InternalJwtProtocol.HEADER, token);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode check(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("traceId").asText()).isEqualTo(TRACE);
        return body;
    }

    private static HttpServer productStub() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var scope = new ScopedServiceJwt.Scope("shop-order", "shop-product", "product:cart-metadata:read");
            var verifier = ScopedServiceJwt.Verifier.forQueryBoundEndpoint(
                    ScopedServiceJwt.publicKey(new FileSystemResource("../infra/security/test-fixtures/internal-test-public.pem")),
                    scope, Clock.systemUTC());
            var json = new ObjectMapper();
            server.createContext(PRODUCT_PATH, exchange -> {
                try (exchange) {
                    String query = exchange.getRequestURI().getRawQuery();
                    try {
                        verifier.verify(exchange.getRequestHeaders().getFirst(InternalJwtProtocol.HEADER),
                                exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), query);
                    } catch (IllegalArgumentException invalid) {
                        exchange.sendResponseHeaders(401, -1);
                        return;
                    }
                    if (!TRACE.equals(exchange.getRequestHeaders().getFirst("X-Trace-Id")) || FAIL_PRODUCT.get()) {
                        exchange.sendResponseHeaders(503, -1);
                        return;
                    }
                    var ids = Arrays.stream(query.split("&")).map(pair -> pair.substring("skuId=".length())).toList();
                    LAST_BATCH.set(ids);
                    var items = ids.stream().filter(id -> !UNAVAILABLE.contains(id))
                            .map(id -> Map.of("skuId", id, "productName", "面粉", "specName", "5kg",
                                    "weightGram", 5000, "priceFen", PRICES.getOrDefault(id, 1200L))).toList();
                    byte[] bytes = json.writeValueAsBytes(Map.of("code", "OK", "data", items, "traceId", TRACE));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            return server;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot start product fixture", failure);
        }
    }

    private static RSAPrivateKey gatewayKey() {
        try {
            return ScopedServiceJwt.privateKey(new FileSystemResource("../infra/security/test-fixtures/internal-test-private.pem"));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read gateway test key", failure);
        }
    }

    private static String token(String audience, String subject, String kind, String method, String path) {
        var now = Instant.now();
        return Jwts.builder().header().type(InternalJwtProtocol.TYPE).and()
                .issuer(InternalJwtProtocol.ISSUER).subject(subject).audience().add(audience).and()
                .claim("userId", subject).claim("kind", kind)
                .claim("permissionsDigest", InternalJwtProtocol.permissionsDigest(Set.of("cart:write:own")))
                .claim("method", method).claim("path", path).claim("queryHash", InternalJwtProtocol.digest(""))
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(45)))
                .signWith(GATEWAY_KEY, Jwts.SIG.RS256).compact();
    }
}
