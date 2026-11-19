package org.spring.shopgateway.auth.application;

import feign.FeignException;
import org.redisson.client.RedisException;
import org.spring.shopgateway.auth.*;
import org.spring.shopgateway.auth.port.PermissionAuthorizer;
import org.spring.shopgateway.error.GatewayException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.server.PathContainer;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.stereotype.Service;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 应用用例：白名单 → Bearer → 本地 JWT → 权限端口 → 身份结果。
 * 不依赖 exchange、响应写入或过滤链，不管理业务用户/角色，不查询业务表。
 */
@Service
@ConditionalOnProperty(prefix = "gateway.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GatewayAccessService {
    private final JwtIdentityVerifier jwt;
    private final PermissionAuthorizer permissions;
    private final GatewayAuthProperties properties;
    private final List<PublicEndpoint> whitelist;

    /** 静态白名单启动时编译；动态权限继续每次查询业务 Redis。 */
    public GatewayAccessService(JwtIdentityVerifier jwt, PermissionAuthorizer permissions, GatewayAuthProperties properties) {
        this.jwt = jwt;
        this.permissions = permissions;
        this.properties = properties;
        whitelist = properties.whitelist().stream().map(endpoint -> new PublicEndpoint(endpoint, RbacCodec.patterns(endpoint))).toList();
    }

    /** 异常分类只覆盖认证鉴权本身，绝不包含后续下游业务转发。 */
    public Mono<Identity> authorize(Request request) {
        return Mono.defer(() -> {
            if (whitelist.stream().anyMatch(rule -> rule.matches(request))) return Mono.just(new Identity(null,
                    org.spring.shopcommon.internalauth.InternalJwtProtocol.permissionsDigest(java.util.Set.of())));
            return token(request.authorization()).flatMap(jwt::userId)
                    .onErrorMap(JwtException.class, error -> error instanceof JwtValidationException validation
                            && validation.getErrors().stream().anyMatch(item -> "access_token_expired".equals(item.getErrorCode()))
                            ? GatewayException.accessTokenExpired(error) : GatewayException.unauthenticated(error))
                    .onErrorMap(RejectedExecutionException.class, GatewayException::authorizationUnavailable)
                    .switchIfEmpty(Mono.error(GatewayException.authorizationUnavailable(null)))
                    .flatMap(userId -> Mono.defer(() -> permissions.authorizeWithSnapshot(userId, request.method(), request.path(),
                                    request.routeId(), request.requestId()))
                            // 仅转换已知依赖异常；空指针等程序错误保持 500，不误报 Redis 故障。
                            .onErrorMap(GatewayAccessService::isDependencyFailure, GatewayException::authorizationUnavailable)
                            .switchIfEmpty(Mono.error(GatewayException.authorizationUnavailable(null)))
                            .flatMap(decision -> decision.allowed() ? Mono.just(new Identity(userId, decision.permissionsDigest())) : Mono.error(GatewayException.forbidden())));
        });
    }

    /** 只检查唯一 Bearer、长度和 compact JWT 外形，真实性仍由现有公钥验证器决定。 */
    private Mono<String> token(List<String> headers) {
        if (headers == null || headers.size() != 1) return Mono.error(new BadJwtException("Exactly one bearer token required"));
        String value = headers.get(0);
        if (value.length() > properties.maxTokenLength() || !value.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            return Mono.error(new BadJwtException("Invalid bearer header"));
        }
        String token = value.substring(7);
        return token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
                ? Mono.just(token) : Mono.error(new BadJwtException("Invalid compact token"));
    }

    /** 缓存/锁/协议数据/权限 HTTP/加载超时/隔离池拒绝均属于依赖不可用。 */
    private static boolean isDependencyFailure(Throwable error) {
        return error instanceof RbacUnavailableException || error instanceof DataAccessException || error instanceof RedisException
                || error instanceof FeignException || error instanceof TimeoutException || error instanceof RejectedExecutionException;
    }
    /** 只包含 HTTP 方法、路径、实际路由和入口关联 ID；没有来自外部头的用户身份字段。 */
    public record Request(String method, PathContainer path, String routeId, String requestId, List<String> authorization) { }
    /** null 仅代表匿名白名单；非空值只能来自 JWT 验证且鉴权通过的身份。 */
    public record Identity(String userId, String permissionsDigest) { }
    /** 预编译的静态公开路径，不是本地用户权限缓存。 */
    private record PublicEndpoint(RbacModels.Endpoint endpoint, List<PathPattern> patterns) {
        boolean matches(Request request) {
            return endpoint.methods().contains(request.method()) && patterns.stream().anyMatch(pattern -> pattern.matches(request.path()));
        }
    }
}
