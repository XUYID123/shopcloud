package org.spring.shopgateway.auth.port;

import org.springframework.http.server.PathContainer;
import reactor.core.publisher.Mono;

/** 权限判断端口，只处理已验证用户，不向过滤器暴露 Redis、锁、Feign 或数据库模型。 */
public interface PermissionAuthorizer {
    /** false 表示明确无权；依赖故障必须返回错误，禁止降级为 true。 */
    Mono<Boolean> authorize(String userId, String method, PathContainer path, String routeId, String requestId);
    default Mono<Decision> authorizeWithSnapshot(String userId, String method, PathContainer path, String routeId, String requestId) {
        return authorize(userId, method, path, routeId, requestId).map(allowed -> new Decision(allowed, null));
    }
    record Decision(boolean allowed, String permissionsDigest) { }
}
