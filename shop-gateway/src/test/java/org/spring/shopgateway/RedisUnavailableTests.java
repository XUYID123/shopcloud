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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.data.redis.port=1", "gateway.protection.anti-abuse.redis-timeout=200ms"
})
class RedisUnavailableTests {
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
    void redisFailureRejectsRequestsWithoutCallingTheBackendOrLeakingDetails() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/users"))
                .timeout(Duration.ofSeconds(5)).header("X-Request-Id", "bad request id")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(503);
        var body = new ObjectMapper().readTree(response.body());
        assertThat(body.get("code").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(body.get("traceId").asText()).isNotEqualTo("bad request id").isNotBlank();
        assertThat(response.headers().allValues("X-Request-Id")).containsExactly(body.get("traceId").asText());
        assertThat(response.body()).doesNotContain("RedisConnection", "shop_gateway_local_dev");
        assertThat(response.headers().firstValue("Retry-After")).contains("1");
        assertThat(BACKEND.calls.get()).isZero();
    }
}
