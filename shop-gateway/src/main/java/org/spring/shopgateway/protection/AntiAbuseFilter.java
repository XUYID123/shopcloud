package org.spring.shopgateway.protection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spring.shopgateway.web.GatewayRequestFilter;
import org.spring.shopgateway.web.GatewayResponses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Redis 防刷入口：委托解析器选择策略，再依次执行 Redis 检查并决定是否继续转发。
 * 只处理匹配到网关路由的请求；开关关闭时不注册此过滤器。
 */
@Component
@ConditionalOnProperty(prefix = "gateway.protection.anti-abuse", name = "enabled", matchIfMissing = true)
public class AntiAbuseFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(AntiAbuseFilter.class);
    private final RedisAbuseLimiter limiter; // 6380 Redis 单项原子额度执行器。
    private final AbusePolicyResolver policyResolver; // 根据方法/路径/路由选出有序检查计划。
    private final GatewayResponses responses; // 统一写 429/503，复用入口请求 ID。

    /** 只依赖策略解析接口，新增接口保护规则不需要在本过滤器增加业务 if/else。 */
    public AntiAbuseFilter(RedisAbuseLimiter limiter, AbusePolicyResolver policyResolver, GatewayResponses responses) {
        this.limiter = limiter;
        this.policyResolver = policyResolver;
        this.responses = responses;
    }

    /** 路由匹配后的防刷入口，只有全部适用策略通过，才继续后面的认证鉴权链。 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // defer 将检查延迟到订阅时执行。Sentinel 拒绝请求时不会订阅下游，
        // 因而不会提前访问 Redis 或消耗防刷额度。
        return Mono.defer(() -> {
            String clientIp = exchange.getAttributeOrDefault(GatewayRequestFilter.CLIENT_IP, "unknown");
            // 不知道注册、登录或具体服务名，只使用解析器返回的执行计划。
            List<AbusePolicy> policies = policyResolver.resolve(exchange);
            // concatMap 按顺序检查，首个拒绝结果经 next() 返回并取消后续检查。
            // 已通过的普通额度不会因专属额度拒绝而回滚；统计的是访问尝试，不是业务成功数。
            return Flux.fromIterable(policies)
                    .concatMap(policy -> limiter.check(policy.name(), clientIp, policy.limit()))
                    .filter(decision -> !decision.allowed()).next()
                    // 没有拒绝结果，说明所有适用策略均已放行。
                    .defaultIfEmpty(RedisAbuseLimiter.Decision.permitted())
                    .onErrorResume(exception -> {
                        // 只兜底上游的防刷检查异常；下方业务转发产生的异常不会被误报为 Redis 故障。
                        log.warn("Anti-abuse store unavailable; traceId={}, type={}",
                                exchange.getAttribute(GatewayRequestFilter.TRACE_ID), exception.getClass().getSimpleName());
                        return Mono.just(new RedisAbuseLimiter.Decision(false, 1, false, true));
                    }).flatMap(decision -> {
                        if (decision.unavailable()) {
                            // Redis 故障时拒绝放行并返回 503，避免共享防刷保护失效。
                            return responses.reject(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                                    "DEPENDENCY_UNAVAILABLE", "访问保护暂不可用，请稍后重试", 1);
                        }
                        if (!decision.allowed()) {
                            // 普通超额和临时封禁都返回 429，等待时间取自 Redis 的实际窗口/封禁余量。
                            return responses.reject(exchange, HttpStatus.TOO_MANY_REQUESTS, "ABUSE_LIMITED",
                                    decision.banned() ? "操作过于频繁，已临时限制访问" : "操作过于频繁，请稍后重试",
                                    decision.retryAfterSeconds());
                        }
                        // 防刷放行后先进入 order=-50 的白名单/JWT/RBAC，再由后续过滤器转发下游。
                        return chain.filter(exchange);
                    });
        });
    }

    /** -100 晚于 Sentinel 的 -200，早于认证鉴权的 -50；都是同层 GlobalFilter 排序。 */
    @Override
    public int getOrder() {
        // 在同一网关过滤链中晚于 Sentinel 的 -200 执行。
        return -100;
    }
}
