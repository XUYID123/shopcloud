package org.spring.shopgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.protection.sentinel-qps=1", "gateway.protection.anti-abuse.enabled=false"
})
class SentinelCapacityTests {
    private static final HttpBackend BACKEND = new HttpBackend();
    @LocalServerPort private int port;

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.shop-user[0].uri", BACKEND::uri);
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.close();
    }

    @Test
    void sentinelActuallyBlocksRequestsAcrossBothPublicAndDiscoveryRoutes() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var futures = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        for (int i = 0; i < 20; i++) {
            String path = (i % 2 == 0 ? "" : "/shop-user") + "/api/v1/users/me";
            futures.add(client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()));
        }
        int admitted = 0;
        int blocked = 0;
        for (var future : futures) {
            HttpResponse<String> response = future.get();
            if (response.statusCode() == 429) {
                blocked++;
                var body = new ObjectMapper().readTree(response.body());
                assertThat(body.get("code").asText()).isEqualTo("RATE_LIMITED");
                assertThat(body.get("traceId").asText()).isNotBlank();
                assertThat(response.headers().firstValue("X-Request-Id")).contains(body.get("traceId").asText());
                assertThat(response.headers().firstValue("Retry-After")).contains("1");
            } else {
                assertThat(response.statusCode()).isEqualTo(200);
                admitted++;
            }
        }
        assertThat(blocked).isPositive();
        assertThat(admitted + blocked).isEqualTo(20);
        assertThat(BACKEND.calls.get()).isEqualTo(admitted);
    }
}
