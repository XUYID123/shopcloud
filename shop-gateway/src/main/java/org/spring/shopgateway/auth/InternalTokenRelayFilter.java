package org.spring.shopgateway.auth;

import org.spring.shopcommon.internalauth.InternalJwtProtocol;
import org.spring.shopgateway.auth.application.GatewayAccessService;
import org.spring.shopgateway.error.GatewayException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/** 路径重写和 lb 解析后、网络转发前签发；RSA 计算放有界工作线程。 */
@Component
@ConditionalOnProperty(prefix="gateway.auth", name="enabled", havingValue="true", matchIfMissing=true)
public class InternalTokenRelayFilter implements GlobalFilter, Ordered {
    public static final String IDENTITY = InternalTokenRelayFilter.class.getName() + ".identity";
    private final InternalTokenSigner signer;
    public InternalTokenRelayFilter(InternalTokenSigner signer) { this.signer = signer; }
    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return Mono.defer(() -> {
            // 身份由前面的认证鉴权用例写入 attributes；缺少授权结果时绝不裸转发。
            GatewayAccessService.Identity identity = exchange.getAttribute(IDENTITY);
            Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
            if (identity == null || route == null || !"lb".equals(route.getUri().getScheme())) return Mono.error(GatewayException.authorizationUnavailable(null));
            // Route 的 lb:// 服务名由配置决定，不能用外部 Host、X-User-Id 或 aud 参数选择受众。
            String audience = route.getUri().getHost().toLowerCase(java.util.Locale.ROOT);
            var request = exchange.getRequest();
            // 此时 StripPrefix/RewritePath 已执行：签最终下游路径，兼容 /shop-product/... 发现路由。
            // fromCallable + boundedElastic 隔离 RSA 计算；只有签名成功后才调用下游链。
            return Mono.fromCallable(() -> signer.sign(identity.userId(), identity.userId() == null ? "anonymous" : "user",
                    identity.permissionsDigest(), audience, request.getMethod().name(), request.getURI().getRawPath(), request.getURI().getRawQuery()))
                    .subscribeOn(Schedulers.boundedElastic())
                    // set 覆盖同名多值头，不给客户端携带的内部凭据留下合并或优先级歧义。
                    .flatMap(token -> chain.filter(exchange.mutate().request(request.mutate()
                            .headers(headers -> headers.set(InternalJwtProtocol.HEADER, token)).build()).build()));
        });
    }
    @Override public int getOrder() { return 20_000; }
}
