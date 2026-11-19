package org.spring.shopgateway.auth;

import org.spring.shopgateway.auth.application.GatewayAccessService;
import org.spring.shopgateway.web.GatewayRequestFilter;
import org.spring.shopgateway.web.GatewayRequestHeaders;
import org.spring.shopgateway.web.GatewayRequestPath;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * HTTP 适配器：清理身份头、构造用例输入、通过后注入可信 ID 并转发。
 * 不解析 JWT、不查 Redis/Feign、不比较权限、不写错误 JSON。
 * 同层 GlobalFilter 顺序仍为 Sentinel(-200) → 防刷(-100) → 本过滤器(-50)。
 */
@Component
@ConditionalOnProperty(prefix = "gateway.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuthenticationAuthorizationFilter implements GlobalFilter, Ordered {
    private final GatewayAccessService access;
    /** 只依赖应用用例，保持过滤链与权限基础设施解耦。 */
    public AuthenticationAuthorizationFilter(GatewayAccessService access) { this.access = access; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return Mono.defer(() -> {
            // 匿名白名单也先清理外部身份；保留 Authorization 和业务请求体。
            var clean = exchange.mutate().request(exchange.getRequest().mutate()
                    .headers(GatewayRequestHeaders::removeIdentity).build()).build();
            Route route = clean.getAttribute(GATEWAY_ROUTE_ATTR);
            var request = new GatewayAccessService.Request(clean.getRequest().getMethod().name(), GatewayRequestPath.resolve(clean),
                    route == null ? null : route.getId(), clean.getAttribute(GatewayRequestFilter.TRACE_ID),
                    clean.getRequest().getHeaders().get(HttpHeaders.AUTHORIZATION));
            return access.authorize(request).flatMap(identity -> {
                // 保存本次已授权身份及同一权限快照摘要；转发前的签发器只读取此结果，不信任请求头。
                clean.getAttributes().put(InternalTokenRelayFilter.IDENTITY, identity);
                if (identity.userId() == null) return chain.filter(clean);
                var authenticated = clean.getRequest().mutate()
                        .headers(headers -> headers.set(GatewayRequestHeaders.USER_ID, identity.userId())).build();
                // 下游错误不进入认证用例的异常转换范围，保持真实业务/网络语义。
                return chain.filter(clean.mutate().request(authenticated).build());
            });
        });
    }
    /** 只在 GlobalFilter 层排序，不与 WebFilter 或异常处理器混排。 */
    @Override public int getOrder() { return -50; }
}
