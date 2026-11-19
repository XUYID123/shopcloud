package org.spring.shopgateway.protection;

import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * 策略解析接口：根据请求返回应当依次检查的策略。
 * 过滤器依赖此接口，配置绑定、路径匹配等细节由实现类承担。
 */
public interface AbusePolicyResolver {
    /** 返回已经按执行顺序组织好的不可变策略列表，匹配细节不泄露给过滤器。 */
    List<AbusePolicy> resolve(ServerWebExchange exchange);
}
