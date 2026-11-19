package org.spring.shopgateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.loadbalancer.cache.enabled=false",
        "gateway.protection.anti-abuse.enabled=false"
})
class GatewayRoutingTests {

    private static final Map<String, HttpServer> BACKENDS = startBackends();

    @LocalServerPort
    private int gatewayPort;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void discoveryInstances(DynamicPropertyRegistry registry) {
        BACKENDS.forEach((service, server) -> registry.add(
                "spring.cloud.discovery.client.simple.instances." + service + "[0].uri",
                () -> "http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterAll
    static void stopBackends() {
        BACKENDS.values().forEach(server -> server.stop(0));
    }

    @Test
    void restfulRequestsReachTheOwningServiceWithoutChangingThePath() throws Exception {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("/api/v1/users/me", "shop-user");
        cases.put("/api/v1/addresses/123", "shop-user");
        cases.put("/api/v1/auth/sessions/current", "shop-user");
        cases.put("/api/v1/products/123?view=detail", "shop-product");
        cases.put("/api/v1/categories", "shop-product");
        cases.put("/api/v1/admin/products/123", "shop-product");
        cases.put("/api/v1/cart/items", "shop-cart");
        cases.put("/api/v1/orders/123", "shop-order");
        cases.put("/api/v1/checkout/quotes", "shop-order");
        cases.put("/api/v1/admin/orders/123", "shop-order");
        cases.put("/api/v1/payment-sessions/123", "shop-pay");
        cases.put("/callbacks/payments/mock", "shop-pay");
        for (var entry : cases.entrySet()) {
            HttpResponse<String> response = get(entry.getKey());
            assertThat(response.statusCode()).as(entry.getKey()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(entry.getValue() + "|GET|" + entry.getKey() + "|");
        }

        String body = "{\"username\":\"route_test\",\"password\":\"example123\"}";
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/users"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("shop-user|POST|/api/v1/users|" + body);
    }

    @Test
    void discoveryRoutesIncludeOnlyTheFiveBusinessServices() throws Exception {
        for (String service : new String[]{"shop-user", "shop-product", "shop-cart", "shop-order", "shop-pay"}) {
            HttpResponse<String> response = get("/" + service + "/probe");
            assertThat(response.statusCode()).as(service).isEqualTo(200);
            assertThat(response.body()).isEqualTo(service + "|GET|/probe|");
        }
        // Common and the gateway are advertised by the test registry but must not become routes.
        assertThat(get("/shop-common/probe").statusCode()).isEqualTo(404);
        assertThat(get("/gateway-service/probe").statusCode()).isEqualTo(404);
    }

    @Test
    void existingUserEntryPreservesItsCurrentlyConfiguredPath() throws Exception {
        HttpResponse<String> response = get("/api/user/x");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("shop-user|GET|/api/user/x|");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + gatewayPort + path);
    }

    private static Map<String, HttpServer> startBackends() {
        Map<String, HttpServer> servers = new LinkedHashMap<>();
        try {
            for (String service : new String[]{"shop-user", "shop-product", "shop-cart", "shop-order",
                    "shop-pay", "shop-common", "gateway-service"}) {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/", exchange -> {
                    try (exchange) {
                        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        byte[] result = (service + "|" + exchange.getRequestMethod() + "|"
                                + exchange.getRequestURI() + "|" + body).getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                        exchange.sendResponseHeaders(200, result.length);
                        exchange.getResponseBody().write(result);
                    }
                });
                server.start();
                servers.put(service, server);
            }
            return servers;
        } catch (IOException exception) {
            servers.values().forEach(server -> server.stop(0));
            throw new IllegalStateException("Cannot start routing test backends", exception);
        }
    }
}
