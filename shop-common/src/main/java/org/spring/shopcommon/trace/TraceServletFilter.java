package org.spring.shopcommon.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;

/**
 * 所有 Servlet 微服务共用的请求入口，由自动配置以最高优先级注册一次。
 * REQUEST/ASYNC/ERROR 分派复用 request attribute 中的 ID，每次分派只绑定当前工作线程。
 */
public class TraceServletFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(TraceServletFilter.class);
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = (String) request.getAttribute(TraceContext.REQUEST_ATTRIBUTE);
        if (traceId == null) {
            // 新头优先；只有新头完全不存在时才兼容旧内部调用的 X-Request-Id。
            traceId = TraceContext.singleValid(Collections.list(request.getHeaders(TraceContext.HEADER)));
            if (traceId == null && request.getHeader(TraceContext.HEADER) == null) {
                traceId = TraceContext.singleValid(Collections.list(request.getHeaders(TraceContext.LEGACY_HEADER)));
            }
            if (traceId == null) traceId = TraceContext.newId();
            request.setAttribute(TraceContext.REQUEST_ATTRIBUTE, traceId);
        }
        // 请求线程属于 Servlet 容器：清除旧状态，不将上个请求遗留的其他 MDC 字段带进来。
        MDC.clear();
        MDC.put(TraceContext.MDC_KEY, traceId);
        setResponseHeaders(response, traceId);
        var access=org.spring.shopcommon.logging.ServletAccessLog.start(request,response);
        try {
            // 调试日志可用于核验真实 HTTP 接收线程；不输出请求体、查询参数或认证头。
            log.debug("Servlet request entered; method={}, dispatcher={}", request.getMethod(), request.getDispatcherType());
            // 统一头值供后续 Filter/Controller 使用；不访问或包装业务请求体。
            chain.doFilter(withHeaders(request, traceId), response);
        } catch(IOException | ServletException | RuntimeException | Error failure) {
            access.failed();throw failure;
        } finally {
            access.returned();
            // 正常返回、业务异常及启动异步后返回均清理；不能只 remove traceId 留下其他敏感 MDC。
            MDC.clear();
        }
    }

    /** 容器在原调用栈内嵌套 ERROR 分派时外层绑定仍有效，避免提前清除外层 MDC。 */
    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(withHeaders(request, (String) request.getAttribute(TraceContext.REQUEST_ATTRIBUTE)), response);
    }

    private static void setResponseHeaders(HttpServletResponse response, String id) {
        response.setHeader(TraceContext.HEADER, id);
        response.setHeader(TraceContext.LEGACY_HEADER, id);
    }

    /** 消除两个别名头之间的歧义，保持大小写不敏感语义及正常业务头不变。 */
    private static HttpServletRequest withHeaders(HttpServletRequest request, String id) {
        return new HttpServletRequestWrapper(request) {
            private boolean isTrace(String name) {
                return TraceContext.HEADER.equalsIgnoreCase(name) || TraceContext.LEGACY_HEADER.equalsIgnoreCase(name);
            }
            @Override public String getHeader(String name) { return isTrace(name) ? id : super.getHeader(name); }
            @Override public Enumeration<String> getHeaders(String name) {
                return isTrace(name) ? Collections.enumeration(Collections.singletonList(id)) : super.getHeaders(name);
            }
            @Override public Enumeration<String> getHeaderNames() {
                var names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
                names.removeIf(this::isTrace);
                names.add(TraceContext.HEADER);
                names.add(TraceContext.LEGACY_HEADER);
                return Collections.enumeration(names);
            }
        };
    }
}
