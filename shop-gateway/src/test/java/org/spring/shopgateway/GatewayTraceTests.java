package org.spring.shopgateway;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.spring.shopcommon.trace.TraceContext;
import org.spring.shopgateway.config.ProtectionProperties;
import org.spring.shopgateway.config.TraceReactorConfiguration;
import org.spring.shopgateway.web.ClientIpResolver;
import org.spring.shopgateway.web.GatewayRequestFilter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** Reactor 真实线程切换与并发隔离，不能以“Context 中有值”替代“MDC 日志有值”的验证。 */
class GatewayTraceTests {
    @BeforeAll static void bridge() {
        new TraceReactorConfiguration();
        // 单元测试未启动 Boot；集成测试/实际应用由 spring.reactor.context-propagation=auto 开启。
        Hooks.enableAutomaticContextPropagation();
    }

    @Test void incomingTraceIsReusedAcrossContextMdcHeadersAndCommit() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products")
                .header(TraceContext.HEADER, "front-123")
                .header(TraceContext.LEGACY_HEADER, "must-be-replaced"));
        MDC.put("traceId", "caller-before-subscription");
        try {
            filter().filter(exchange, next -> {
                assertThat(MDC.get("traceId")).isEqualTo("front-123");
                assertThat(next.getRequest().getHeaders().get(TraceContext.HEADER)).containsExactly("front-123");
                assertThat(next.getRequest().getHeaders().get(TraceContext.LEGACY_HEADER)).containsExactly("front-123");
                return Mono.deferContextual(context -> {
                    assertThat(context.<String>get(TraceContext.REACTOR_KEY)).isEqualTo("front-123");
                    return Mono.delay(Duration.ofMillis(5)).publishOn(Schedulers.boundedElastic()).flatMap(ignored -> {
                        assertThat(MDC.get("traceId")).isEqualTo("front-123");
                        next.getResponse().getHeaders().set(TraceContext.HEADER, "backend-override");
                        return next.getResponse().setComplete();
                    });
                });
            }).block(Duration.ofSeconds(5));
            assertThat(MDC.get("traceId")).isEqualTo("caller-before-subscription");
            assertThat(exchange.getResponse().getHeaders().get(TraceContext.HEADER)).containsExactly("front-123");
            assertThat(exchange.getResponse().getHeaders().get(TraceContext.LEGACY_HEADER)).containsExactly("front-123");
        } finally { MDC.clear(); }
    }

    @Test void eightyInterleavedRequestsDoNotShareMdcAcrossSchedulers() {
        Flux.range(0, 80).flatMap(number -> {
            String id = "parallel-" + number;
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/probe").header(TraceContext.HEADER, id));
            return filter().filter(exchange, next -> Mono.delay(Duration.ofMillis(number % 5 + 1))
                    .publishOn(Schedulers.boundedElastic())
                    .flatMap(ignored -> Mono.deferContextual(context -> {
                        assertThat(context.<String>get(TraceContext.REACTOR_KEY)).isEqualTo(id);
                        assertThat(MDC.get("traceId")).isEqualTo(id);
                        return next.getResponse().setComplete();
                    }))).subscribeOn(Schedulers.parallel());
        }, 16).then().block(Duration.ofSeconds(10));
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test void rejectsAmbiguousAndUnsafeIdsAndRestoresMdcAfterFailure() {
        for (List<String> values : List.of(List.of("bad value"), List.of("first", "second"), List.of("a".repeat(65)))) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/probe")
                    .header(TraceContext.HEADER, values.toArray(String[]::new)));
            assertThatThrownBy(() -> filter().filter(exchange, next -> Mono.deferContextual(context -> {
                assertThat(MDC.get("traceId")).matches("[0-9a-f]{16}");
                return Mono.error(new IllegalStateException("expected failure"));
            })).block(Duration.ofSeconds(3))).isInstanceOf(IllegalStateException.class);
            assertThat(MDC.get("traceId")).isNull();
        }
    }

    private GatewayRequestFilter filter() {
        return new GatewayRequestFilter(new ClientIpResolver(new ProtectionProperties(200, null, List.of())));
    }
}
