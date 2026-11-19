package org.spring.shopcommon.trace;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.spring.shopcommon.result.Result;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;

/** 真实 Filter 调用：头优先级、Result/响应一致性、异步分派复用与异常后的整个 MDC 清理。 */
class TraceServletFilterTests {
    private final TraceServletFilter filter = new TraceServletFilter();
    @AfterEach void clear() { MDC.clear(); }

    @Test void traceHeaderWinsAndMdcIsClearedOnSuccess() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(TraceContext.HEADER, "frontend-trace-123");
        request.addHeader(TraceContext.LEGACY_HEADER, "different-legacy-id");
        var response = new MockHttpServletResponse();
        MDC.put("stale", "previous-request");
        filter.doFilter(request, response, (incoming, outgoing) -> {
            var http = (jakarta.servlet.http.HttpServletRequest) incoming;
            assertThat(http.getHeader(TraceContext.LEGACY_HEADER)).isEqualTo("frontend-trace-123");
            assertThat(MDC.get("stale")).isNull();
            assertThat(MDC.get("traceId")).isEqualTo("frontend-trace-123");
            assertThat(Result.success(null).getTraceId()).isEqualTo("frontend-trace-123");
            MDC.put("business", "must-also-be-cleared");
        });
        assertThat(response.getHeader(TraceContext.HEADER)).isEqualTo("frontend-trace-123");
        assertThat(response.getHeader(TraceContext.LEGACY_HEADER)).isEqualTo("frontend-trace-123");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test void missingInvalidAndAmbiguousHeadersGenerateBoundedIds() throws Exception {
        for (int scenario = 0; scenario < 4; scenario++) {
            var request = new MockHttpServletRequest();
            if (scenario == 1) request.addHeader(TraceContext.HEADER, "injected\nline");
            if (scenario == 2) request.addHeader(TraceContext.HEADER, "a".repeat(65));
            if (scenario == 3) { request.addHeader(TraceContext.HEADER, "first"); request.addHeader(TraceContext.HEADER, "second"); }
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (in, out) -> assertThat(MDC.get("traceId")).matches("[0-9a-f]{16}"));
            assertThat(response.getHeader(TraceContext.HEADER)).matches("[0-9a-f]{16}");
        }
    }

    @Test void acceptsOldInternalHeaderButDoesNotFallbackWhenNewHeaderIsInvalid() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(TraceContext.LEGACY_HEADER, "permission-internal-test");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (in, out) -> {});
        assertThat(response.getHeader(TraceContext.HEADER)).isEqualTo("permission-internal-test");
        request = new MockHttpServletRequest();
        request.addHeader(TraceContext.HEADER, "invalid trace");
        request.addHeader(TraceContext.LEGACY_HEADER, "legacy-must-not-win");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (in, out) -> {});
        assertThat(response.getHeader(TraceContext.HEADER)).matches("[0-9a-f]{16}");
    }

    @Test void failureAndRedispatchReuseOneIdWithoutLeakingThreadState() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request, response, (in, out) -> {
            MDC.put("business", "secret");
            throw new IllegalStateException("business failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        String original = response.getHeader(TraceContext.HEADER);
        for (var dispatch : new DispatcherType[]{DispatcherType.ASYNC, DispatcherType.ERROR}) {
            request.setDispatcherType(dispatch);
            var next = new MockHttpServletResponse();
            filter.doFilter(request, next, (in, out) -> assertThat(MDC.get("traceId")).isEqualTo(original));
            assertThat(next.getHeader(TraceContext.HEADER)).isEqualTo(original);
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        }
    }
}
