package org.spring.shopcommon.internalauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.spring.shopcommon.result.Result;
import org.springframework.web.filter.GenericFilterBean;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.http.server.PathContainer;
import java.io.IOException;
import java.util.*;

/**
 * 所有下游 Servlet 服务共用且默认强制的身份入口。任何路径都必须持内部 JWT，包括公开业务入口。
 * 匿名仅是已签名身份的一种：还要同时命中本服务公开路径；内部权限查询凭据不得访问业务接口。
 */
public final class InternalAuthenticationFilter extends GenericFilterBean {
    private final InternalJwtVerifier verifier;
    private final ObjectMapper json;
    private final List<PublicEndpoint> endpoints;
    private final String audience;
    public InternalAuthenticationFilter(InternalJwtVerifier verifier, ObjectMapper json, InternalAuthProperties properties, String audience) {
        this.verifier = verifier; this.json = json; this.audience = audience;
        // 公开路径只在启动时编译；默认空列表，未配置的路径不接受匿名凭据。
        endpoints = properties.publicEndpoints().stream().map(e -> new PublicEndpoint(e.methods(),
                e.paths().stream().map(PathPatternParser.defaultInstance::parse).toList())).toList();
    }
    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws ServletException, IOException {
        doFilterInternal((HttpServletRequest) request, (HttpServletResponse) response, chain);
    }
    private void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        InternalIdentity identity;
        // 同一请求的异步/错误分派复用已验证身份，避免短 JWT 在长请求结束时掩盖原业务异常。
        // FORWARD/INCLUDE 不走这个分支，目标路径变化必须再次通过签名绑定校验。
        if ((request.getDispatcherType() == DispatcherType.ASYNC || request.getDispatcherType() == DispatcherType.ERROR)
                && request.getAttribute(InternalIdentity.ATTRIBUTE) instanceof InternalIdentity previous) {
            chain.doFilter(withVerifiedHeaders(request, previous), response); return;
        }
        // INCLUDE 的有效目标在分派属性中；签名校验和权限范围判断必须使用同一目标路径。
        String path = request.getDispatcherType() == DispatcherType.INCLUDE
                ? (String) request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) : request.getRequestURI();
        try {
            // 拒绝缺少或多值内部头；旧共享静态令牌不会被当作兼容凭据。
            var headers = Collections.list(request.getHeaders(InternalJwtProtocol.HEADER));
            if (headers.size() != 1) throw new IllegalArgumentException();
            String query = request.getDispatcherType() == DispatcherType.INCLUDE
                    ? (String) request.getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING) : request.getQueryString();
            identity = verifier.verify(headers.get(0), request.getMethod(), path, query);
        } catch (IllegalArgumentException invalid) {
            reject(request,response, 401, "INVALID_INTERNAL_TOKEN", "内部身份凭据无效或已过期"); return;
        }
        // permission-query 仅供网关在 RBAC 冷加载时查同一用户；它不表示通过了业务接口鉴权。
        // 有效签名仍须检查用途，普通/匿名 JWT 也不能访问内部权限查询路径。
        boolean permissionEndpoint = "shop-user".equals(audience)
                && "GET".equals(request.getMethod())
                && path.equals("/internal/v1/users/" + identity.userId() + "/permissions");
        if (path.startsWith("/internal/")
                ? !(permissionEndpoint && "permission-query".equals(identity.kind()))
                : "permission-query".equals(identity.kind()) || (identity.anonymous() && endpoints.stream().noneMatch(e -> e.matches(request.getMethod(), path)))) {
            reject(request,response, 403, "FORBIDDEN", "内部凭据无权访问此资源"); return;
        }
        // 仅将验签得到的身份向下游传递：删除伪造用户/角色/权限头，再由 JWT 重建用户 ID。
        request.setAttribute(InternalIdentity.ATTRIBUTE, identity);
        chain.doFilter(withVerifiedHeaders(request, identity), response);
    }
    private void reject(HttpServletRequest request,HttpServletResponse response, int status, String code, String message) throws IOException {
        org.spring.shopcommon.logging.ServletResultCodes.record(request,code);
        // TraceServletFilter 已先写响应追踪头和 MDC；Result.fail 沿用同一 traceId，拒绝不进入业务链。
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "InternalJWT realm=\"shop\"");
        json.writeValue(response.getOutputStream(), Result.fail(code, message));
    }
    private static HttpServletRequest withVerifiedHeaders(HttpServletRequest request, InternalIdentity identity) {
        // 只包装身份元信息，不读流、不缓存也不修改业务请求体；所有头名称按大小写不敏感处理。
        return new HttpServletRequestWrapper(request) {
            private boolean identityHeader(String name) { return Set.of("x-user-id", "x-user-roles", "x-user-permissions").contains(name.toLowerCase(Locale.ROOT)); }
            @Override public String getHeader(String name) {
                return identityHeader(name) ? ("x-user-id".equalsIgnoreCase(name) ? identity.userId() : null) : super.getHeader(name);
            }
            @Override public Enumeration<String> getHeaders(String name) {
                if (!identityHeader(name)) return super.getHeaders(name);
                String value = getHeader(name); return Collections.enumeration(value == null ? List.of() : List.of(value));
            }
            @Override public Enumeration<String> getHeaderNames() {
                var names = new LinkedHashSet<>(Collections.list(super.getHeaderNames())); names.removeIf(this::identityHeader);
                if (identity.userId() != null) names.add("X-User-Id"); return Collections.enumeration(names);
            }
            // Principal 与 X-User-Id 来自同一验证结果，匿名两者均为空。
            @Override public java.security.Principal getUserPrincipal() { return identity.userId() == null ? null : identity::userId; }
        };
    }
    private record PublicEndpoint(Set<String> methods, List<PathPattern> paths) {
        boolean matches(String method, String path) { return methods.contains(method) && paths.stream().anyMatch(p -> p.matches(PathContainer.parsePath(path))); }
    }
}
