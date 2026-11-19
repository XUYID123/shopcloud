package org.spring.shopgateway.web;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import org.spring.shopcommon.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 外部请求入口：复用合法 X-Trace-Id 或生成 16 位关联 ID，解析可信 IP，删除外部身份与代理头。
 * WebFilter 先于路由匹配；匹配后的 Sentinel → Redis 防刷 → JWT/RBAC 属于另一层 GlobalFilter 链。
 */
@Component
public class GatewayRequestFilter implements WebFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(GatewayRequestFilter.class);
    /** exchange 属性键：在同一次请求内传递 traceId，不依赖响应式线程切换后的线程变量。 */
    public static final String TRACE_ID = GatewayRequestFilter.class.getName() + ".traceId";
    /** exchange 属性键：保存经过可信代理规则解析的 IP，供 Redis 防刷使用。 */
    public static final String CLIENT_IP = GatewayRequestFilter.class.getName() + ".clientIp";
    private final ClientIpResolver resolver; // 依据真实连接及受控代理链判断来源，不能直接取最左侧请求头。

    /** Spring 注入客户端 IP 解析器；本类只整理元信息，不读取请求体或在此认证用户。 */
    public GatewayRequestFilter(ClientIpResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * 外部请求进入 WebFlux 时执行，早于网关路由匹配；未匹配路由的请求也会得到请求 ID。
     * exchange 保存本次上下文，chain 是后续 Web 处理链，不是按类名手工调用下一个过滤器。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 仅关联日志：最新约定允许客户端延续合法 X-Trace-Id；旧 X-Request-Id 仍不决定入口 ID。
        // 多值、非法字符或超长时重新生成；同一 exchange 重入时复用，避免两套 ID。
        String existing = exchange.getAttribute(TRACE_ID);
        String supplied = TraceContext.singleValid(exchange.getRequest().getHeaders().get(TraceContext.HEADER));
        String traceId = existing != null ? existing : supplied != null ? supplied : TraceContext.newId();
        // 在后续整理前保存，异常出口也复用同一标识，不在 IP 解析失败时另起第二个编号。
        exchange.getAttributes().put(TRACE_ID, traceId);
        var access=new GatewayAccessLog(exchange,traceId);
        // 必须先解析原始请求中的代理链，再清理并重建下游收到的请求头。
        String clientIp = resolver.resolve(exchange.getRequest());
        exchange.getAttributes().put(CLIENT_IP, clientIp);
        // 即使请求稍后被限流，也能从响应头拿到关联标识。
        exchange.getResponse().getHeaders().set(GatewayRequestHeaders.REQUEST_ID, traceId);
        exchange.getResponse().getHeaders().set(TraceContext.HEADER, traceId);
        exchange.getResponse().beforeCommit(() -> {
            access.beforeCommit(exchange);
            // 下游响应也可能带同名头；提交前恢复入口 ID，保持响应头与上下文一致。
            exchange.getResponse().getHeaders().set(GatewayRequestHeaders.REQUEST_ID, traceId);
            exchange.getResponse().getHeaders().set(TraceContext.HEADER, traceId);
            return Mono.empty();
        });
        var request = exchange.getRequest().mutate().headers(headers -> {
            GatewayRequestHeaders.removeIdentity(headers);
            // 内部凭据由网关在授权通过、确定目标后逐请求签发，外部提交的值必须先删除。
            headers.remove(org.spring.shopcommon.auth.PermissionCacheKeys.INTERNAL_TOKEN_HEADER);
            // 去掉客户端任意传入的代理头，避免下游再次信任伪造的来源信息。
            // 遍历键名快照，避免遍历时删除同一集合中的元素。
            for (String header : new ArrayList<>(headers.headerNames())) {
                String name = header.toLowerCase(Locale.ROOT);
                if (name.equals("forwarded") || name.equals("x-real-ip") || name.startsWith("x-forwarded-")) {
                    headers.remove(header);
                }
            }
            // 只向下游传递本层确定的 IP 与请求标识。
            headers.set(GatewayRequestHeaders.FORWARDED_FOR, clientIp);
            headers.set(GatewayRequestHeaders.REQUEST_ID, traceId);
            headers.set(TraceContext.HEADER, traceId);
        }).build();
        // 响应式请求通过 mutate 构建副本，将更新后的请求交给后续处理链。
        var next = exchange.mutate().request(request).build();
        // defer 将后续处理器的组装/执行也放进订阅范围；contextWrite 影响其上游的整个处理链。
        // 禁止在 Netty 入口简单 MDC.put 后等待异步完成再 clear，那会串到共享该线程的其他请求。
        return Mono.defer(() -> {
            log.debug("Gateway request entered; method={}, path={}", request.getMethod(), request.getPath().value());
            return chain.filter(next);
        }).doOnError(error -> access.failed())
                .doFinally(signal -> access.terminated(next,signal))
                .contextWrite(context -> context.put(TraceContext.REACTOR_KEY, traceId));
    }

    /** 只参与 WebFilter 层排序；路由匹配后的 Sentinel/防刷/认证在另一层 GlobalFilter 链排序。 */
    @Override
    public int getOrder() {
        // 尽早准备请求上下文；此值只参与 WebFilter 链内的排序。
        return -2000;
    }
}
