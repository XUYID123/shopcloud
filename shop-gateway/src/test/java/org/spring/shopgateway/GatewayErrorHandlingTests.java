package org.spring.shopgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 真实 Gateway HTTP：无实例、连接拒绝、业务错误不改写、404、有效跨域预检与错误响应头。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.protection.anti-abuse.enabled=false", "gateway.auth.enabled=false",
        "spring.cloud.loadbalancer.cache.enabled=false", "spring.config.import=file:examples/application-governance.yml"
})
class GatewayErrorHandlingTests {
    private static final HttpServer BACKEND = backend();
    private static final int CLOSED_PORT = closedPort();
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @DynamicPropertySource static void discovery(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.shop-product[0].uri", () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.shop-order[0].uri", () -> "http://127.0.0.1:" + CLOSED_PORT);
    }
    @AfterAll static void close() { BACKEND.stop(0); }

    @Test void missingDiscoveryInstanceAndRefusedConnectionAreStandard503() throws Exception {
        assertError(get("/api/v1/payment-sessions/1", null), 503, "SERVICE_UNAVAILABLE");
        assertError(get("/api/v1/cart/items", null), 503, "SERVICE_UNAVAILABLE");
    }
    @Test void missingRouteIsStandard404ButCompletedDownstreamBusinessErrorIsPreserved() throws Exception {
        assertError(get("/no-such-route", null), 404, "NOT_FOUND");
        var response = get("/api/v1/products/business-error", null);
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).isEqualTo("{\"code\":\"BUSINESS_ERROR\",\"message\":\"from backend\"}");
    }
    @Test void preflightDoesNotRequireAuthenticationAndIncludesRequestId() throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/orders/1")).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type").build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(response.headers().firstValue("X-Trace-Id")).isEqualTo(response.headers().firstValue("X-Request-Id"));
    }
    @Test void allowedCorsHeadersSurviveUnifiedErrorResponseAndUnknownOriginIsRejected() throws Exception {
        var response = get("/api/v1/orders/1", "http://localhost:5173");
        assertError(response, 503, "SERVICE_UNAVAILABLE");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");
        assertThat(response.headers().firstValue("Access-Control-Expose-Headers").orElseThrow()).contains("X-Request-Id", "X-Trace-Id");
        var rejected = get("/api/v1/products/business-error", "https://untrusted.invalid");
        assertThat(rejected.statusCode()).isEqualTo(403);
        assertThat(rejected.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        String id = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(response.headers().firstValue("X-Trace-Id")).contains(id);
        assertThat(id).matches("[0-9a-f]{16}");
        var body = mapper.readTree(response.body());
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("traceId").asText()).isEqualTo(id);
    }
    private HttpResponse<String> get(String path, String origin) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(6));
        if (origin != null) request.header("Origin", origin);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private static int closedPort() {
        try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static HttpServer backend() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    byte[] json = "{\"code\":\"BUSINESS_ERROR\",\"message\":\"from backend\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(502, json.length); exchange.getResponseBody().write(json);
                }
            });
            server.start(); return server;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
