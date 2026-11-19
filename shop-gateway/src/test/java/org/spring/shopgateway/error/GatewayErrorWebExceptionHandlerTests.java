package org.spring.shopgateway.error;

import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.spring.shopgateway.web.*;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import java.net.ConnectException;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import static org.assertj.core.api.Assertions.*;

/** 统一错误语义、关联 ID、脱敏、已提交响应和 Sentinel 委派边界，无外部依赖。 */
class GatewayErrorWebExceptionHandlerTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GatewayErrorWebExceptionHandler handler = new GatewayErrorWebExceptionHandler(new GatewayResponses(mapper));

    @Test void unauthorizedUsesStandardResultAndSameRequestIdWithoutLeakingCause() throws Exception {
        var exchange = exchange();
        String id = UUID.randomUUID().toString();
        exchange.getAttributes().put(GatewayRequestFilter.TRACE_ID, id);
        handler.handle(exchange, GatewayException.unauthenticated(new IllegalArgumentException("secret-token"))).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst("WWW-Authenticate")).contains("Bearer");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id")).isEqualTo(id);
        assertThat(exchange.getResponse().getHeaders().containsHeader("Retry-After")).isFalse();
        var body = mapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(body.get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(body.get("traceId").asText()).isEqualTo(id);
        assertThat(body.toString()).doesNotContain("secret-token");
    }

    @Test void connectionTimeoutAndUnexpectedErrorsHaveDistinctStatusesAndSafeJson() throws Exception {
        assertError(new ConnectException("private-host:1234"), 503, "SERVICE_UNAVAILABLE");
        assertError(new TimeoutException("private-host:1234"), 504, "UPSTREAM_TIMEOUT");
        assertError(new IllegalStateException("private-host:1234"), 500, "INTERNAL_ERROR");
        assertError(new ResponseStatusException(HttpStatus.NOT_FOUND, "private-host:1234"), 404, "NOT_FOUND");
        assertError(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "private-host:1234"), 429, "RATE_LIMITED");
        assertError(GatewayException.forbidden(), 403, "FORBIDDEN");
    }

    @Test void replacementErrorBodyClearsStaleLengthCompressionAndCacheHeaders() {
        var exchange = exchange();
        var headers = exchange.getResponse().getHeaders();
        headers.setContentLength(999); headers.set("Content-Encoding", "gzip"); headers.setETag("\"old\"");
        headers.set("Access-Control-Allow-Origin", "https://front.example");
        handler.handle(exchange, new ConnectException("offline")).block();
        assertThat(headers.getFirst("Content-Encoding")).isNull();
        assertThat(headers.getETag()).isNull();
        assertThat(headers.getContentLength()).isNotEqualTo(999);
        assertThat(headers.getCacheControl()).isEqualTo("no-store");
        assertThat(headers.getFirst("Access-Control-Allow-Origin")).isEqualTo("https://front.example");
    }

    @Test void committedResponseAndSentinelBlockAreDelegatedWithoutWritingAgain() {
        var committed = exchange();
        committed.getResponse().setComplete().block();
        var original = new IllegalStateException("already committed");
        assertThatThrownBy(() -> handler.handle(committed, original).block()).isSameAs(original);
        var block = new FlowException("capacity");
        var untouched = exchange();
        Throwable delegated = catchThrowable(() -> handler.handle(untouched, block).block());
        assertThat(reactor.core.Exceptions.unwrap(delegated)).isSameAs(block);
        assertThat(untouched.getResponse().isCommitted()).isFalse();
    }

    private void assertError(Throwable error, int status, String code) throws Exception {
        var exchange = exchange();
        handler.handle(exchange, error).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(status);
        var body = mapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(body.get("code").asText()).isEqualTo(code);
        String id = exchange.getAttribute(GatewayRequestFilter.TRACE_ID);
        assertThat(id).matches("[0-9a-f]{16}");
        assertThat(body.get("traceId").asText()).isEqualTo(id);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id")).isEqualTo(id);
        assertThat(body.toString()).doesNotContain("private-host");
    }
    private MockServerWebExchange exchange() { return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders")); }
}
