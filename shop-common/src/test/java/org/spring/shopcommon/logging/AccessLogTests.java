package org.spring.shopcommon.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.*;
import org.spring.shopcommon.trace.*;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

/** 入口日志含 trace、最终拒绝状态；不输出查询串/认证头，并支持异步真正完成后一次性记录。 */
class AccessLogTests {
    final ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)LoggerFactory.getLogger(ServletAccessLog.class);
    final ListAppender<ILoggingEvent> logs=new ListAppender<>(){
        @Override protected void append(ILoggingEvent e){e.prepareForDeferredProcessing();super.append(e);}
    };
    @BeforeEach void start(){logs.start();logger.addAppender(logs);}
    @AfterEach void end(){logger.detachAppender(logs);MDC.clear();}
    @Test void denialIsLoggedOnceWithoutSensitiveHeadersOrQuery() throws Exception {
        var req=new MockHttpServletRequest("GET","/api/v1/products/1");req.setQueryString("password=secret");req.addHeader("Authorization","Bearer secret");
        req.addHeader("X-Trace-Id","access-test");var res=new MockHttpServletResponse();
        new TraceServletFilter().doFilter(req,res,(r,s)->res.setStatus(401));
        assertThat(logs.list).hasSize(1);assertThat(logs.list.get(0).getFormattedMessage()).contains("status=401","code=HTTP_401","path=/api/v1/products/1").doesNotContain("secret","password");
        assertThat(logs.list.get(0).getMDCPropertyMap()).containsEntry("traceId","access-test");assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
    @Test void asyncCompletionLogsAfterCompletionAndOnlyOnce() throws Exception {
        var req=new MockHttpServletRequest("GET","/async");req.setAsyncSupported(true);req.addHeader("X-Trace-Id","async-test");var res=new MockHttpServletResponse();
        new TraceServletFilter().doFilter(req,res,(r,s)->r.startAsync(r,s));assertThat(logs.list).isEmpty();
        res.setStatus(503);ServletResultCodes.record(req,"DEPENDENCY_UNAVAILABLE");req.getAsyncContext().complete();
        assertThat(logs.list).hasSize(1);assertThat(logs.list.get(0).getFormattedMessage()).contains("status=503","code=DEPENDENCY_UNAVAILABLE");
        assertThat(logs.list.get(0).getMDCPropertyMap()).containsEntry("traceId","async-test");
    }
}
