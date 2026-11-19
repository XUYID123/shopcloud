package org.spring.shopgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.spring.shopgateway.config.ProtectionProperties;
import org.spring.shopgateway.web.ClientIpResolver;
import org.spring.shopgateway.web.GatewayRequestFilter;
import org.spring.shopgateway.web.GatewayResponses;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 入口信任边界：无需 Redis，直接验证上下文、转发头、响应头和请求体的同一次请求约定。 */
class GatewayRequestFilterTests {
    @Test
    void replacesEvenValidMultipleClientIdsAndKeepsOneIdThroughResponseCommit() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products")
                .header("X-Request-Id", "valid-client-id", "another-client-id"));
        AtomicReference<String> forwardedId = new AtomicReference<>();

        filter(List.of()).filter(exchange, next -> {
            String id = next.getAttribute(GatewayRequestFilter.TRACE_ID);
            assertThat(id).matches("[0-9a-f]{16}");
            assertThat(id).isNotIn("valid-client-id", "another-client-id");
            assertThat(next.getRequest().getHeaders().get("X-Request-Id")).containsExactly(id);
            assertThat(next.getResponse().getHeaders().getFirst("X-Request-Id")).isEqualTo(id);
            forwardedId.set(id);
            // 模拟下游覆盖同名响应头；入口注册的 beforeCommit 必须恢复原来的同一个 ID。
            next.getResponse().getHeaders().set("X-Request-Id", "backend-supplied-id");
            return next.getResponse().setComplete();
        }).block(Duration.ofSeconds(3));

        assertThat(exchange.<String>getAttribute(GatewayRequestFilter.TRACE_ID)).isEqualTo(forwardedId.get());
        assertThat(exchange.getResponse().getHeaders().get("X-Request-Id")).containsExactly(forwardedId.get());
    }

    @Test
    void stripsExternalIdentityAndProxyHeadersWhilePreservingAuthorizationBusinessHeadersAndBody() {
        String body = "{\"description\":\"面粉订单\",\"quantity\":2}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        AtomicInteger bodySubscriptions = new AtomicInteger();
        var request = MockServerHttpRequest.post("/api/v1/orders?source=web")
                .remoteAddress(new InetSocketAddress("198.51.100.8", 1234))
                .header("x-user-id", "999", "888")
                .header("X-USER-ROLES", "ADMIN")
                .header("x-user-permissions", "*")
                .header("X-Forwarded-For", "203.0.113.99")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "evil.invalid")
                .header("X-Real-IP", "203.0.113.98")
                .header("Forwarded", "for=203.0.113.97")
                .header("Authorization", "Bearer unchanged-business-token")
                .header("X-Business-Tag", "flour")
                .header("Content-Type", "application/json")
                .body(Flux.defer(() -> {
                    bodySubscriptions.incrementAndGet();
                    return Flux.just(new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap(bytes));
                }));
        var exchange = MockServerWebExchange.from(request);

        filter(List.of()).filter(exchange, next -> {
            assertThat(bodySubscriptions.get()).isZero();
            var headers = next.getRequest().getHeaders();
            assertThat(headers.containsHeader("X-User-Id")).isFalse();
            assertThat(headers.containsHeader("X-User-Roles")).isFalse();
            assertThat(headers.containsHeader("X-User-Permissions")).isFalse();
            assertThat(headers.containsHeader("Forwarded")).isFalse();
            assertThat(headers.containsHeader("X-Real-IP")).isFalse();
            assertThat(headers.containsHeader("X-Forwarded-Proto")).isFalse();
            assertThat(headers.containsHeader("X-Forwarded-Host")).isFalse();
            assertThat(headers.get("X-Forwarded-For")).containsExactly("198.51.100.8");
            assertThat(next.<String>getAttribute(GatewayRequestFilter.CLIENT_IP)).isEqualTo("198.51.100.8");
            assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer unchanged-business-token");
            assertThat(headers.getFirst("X-Business-Tag")).isEqualTo("flour");
            assertThat(headers.getFirst("Content-Type")).isEqualTo("application/json");
            assertThat(next.getRequest().getURI().getRawQuery()).isEqualTo("source=web");
            // 只在模拟的后续处理器里消费请求体，入口自身不能订阅或读取。
            return DataBufferUtils.join(next.getRequest().getBody()).flatMap(buffer -> {
                try {
                    byte[] received = new byte[buffer.readableByteCount()];
                    buffer.read(received);
                    assertThat(received).isEqualTo(bytes);
                } finally {
                    DataBufferUtils.release(buffer);
                }
                return next.getResponse().setComplete();
            });
        }).block(Duration.ofSeconds(3));
        assertThat(bodySubscriptions.get()).isEqualTo(1);
    }

    @Test
    void resolvesTrustedProxyChainBeforeReplacingItsHeaders() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 1234))
                .header("X-Forwarded-For", "203.0.113.99, 192.0.2.8, 10.0.0.2"));

        filter(List.of("10.0.0.0/8")).filter(exchange, next -> {
            assertThat(next.<String>getAttribute(GatewayRequestFilter.CLIENT_IP)).isEqualTo("192.0.2.8");
            assertThat(next.getRequest().getHeaders().get("X-Forwarded-For")).containsExactly("192.0.2.8");
            return next.getResponse().setComplete();
        }).block(Duration.ofSeconds(3));
    }

    @Test
    void anonymousRequestsWithoutClientIdsGetDifferentIdsAndRejectionsReuseTheContextId() throws Exception {
        var first = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me"));
        var second = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me"));
        var filter = filter(List.of());
        var mapper = new ObjectMapper();
        var responses = new GatewayResponses(mapper);

        for (var exchange : List.of(first, second)) {
            filter.filter(exchange, next -> responses.reject(next, HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED", "请提供有效的登录凭据")).block(Duration.ofSeconds(3));
            String id = exchange.getAttribute(GatewayRequestFilter.TRACE_ID);
            assertThat(id).matches("[0-9a-f]{16}");
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(exchange.getResponse().getHeaders().get("X-Request-Id")).containsExactly(id);
            assertThat(mapper.readTree(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(3)))
                    .get("traceId").asText()).isEqualTo(id);
        }
        assertThat(first.<String>getAttribute(GatewayRequestFilter.TRACE_ID))
                .isNotEqualTo(second.<String>getAttribute(GatewayRequestFilter.TRACE_ID));
    }

    private GatewayRequestFilter filter(List<String> trusted) {
        return new GatewayRequestFilter(new ClientIpResolver(new ProtectionProperties(200, null, trusted)));
    }
}
