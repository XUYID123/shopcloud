package org.spring.shopcommon.trace;

import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.RequestLine;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/** 不仅验证模板字段，也通过真实 Feign HTTP 请求验证接收端获得唯一同值追踪头。 */
class TraceFeignRequestInterceptorTests {
    @AfterEach void clear() { MDC.clear(); }
    @Test void overwritesConflictingHeadersAndDoesNotLeakPreviousCallsWithoutContext() {
        var interceptor = new TraceFeignRequestInterceptor();
        var template = new RequestTemplate();
        template.header("x-trace-id", "spoofed"); template.header("X-Request-Id", "old", "other");
        MDC.put("traceId", "current-request"); interceptor.apply(template);
        assertThat(template.headers().get(TraceContext.HEADER)).containsExactly("current-request");
        assertThat(template.headers().get(TraceContext.LEGACY_HEADER)).containsExactly("current-request");
        MDC.clear(); interceptor.apply(template);
        assertThat(template.headers()).doesNotContainKeys(TraceContext.HEADER, TraceContext.LEGACY_HEADER);
    }

    @Test void realFeignRequestReceivesMdcTraceId() throws Exception {
        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", exchange -> {
            try (exchange) {
                String id = exchange.getRequestHeaders().getFirst(TraceContext.HEADER);
                received.set(id);
                byte[] body = String.valueOf(id).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var client = Feign.builder().requestInterceptor(new TraceFeignRequestInterceptor())
                    .target(Probe.class, "http://127.0.0.1:" + server.getAddress().getPort());
            try (var ignored = TraceContext.open("real-feign-trace")) {
                assertThat(client.probe()).isEqualTo("real-feign-trace");
            }
            assertThat(received.get()).isEqualTo("real-feign-trace");
            assertThat(MDC.get("traceId")).isNull();
        } finally { server.stop(0); }
    }
    interface Probe { @RequestLine("GET /probe") String probe(); }
}
