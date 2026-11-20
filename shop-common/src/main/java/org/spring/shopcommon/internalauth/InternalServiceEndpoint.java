package org.spring.shopcommon.internalauth;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;

/** 接收方显式注册的服务身份入口，所有普通请求仍走既有网关 JWT 验签。 */
public record InternalServiceEndpoint(String method, PathPattern path, ScopedServiceJwt.Verifier verifier) {
    boolean matches(String requestMethod, String requestPath) {
        return method.equals(requestMethod) && requestPath != null && requestPath.startsWith("/internal/")
                && path.matches(PathContainer.parsePath(requestPath));
    }
}
