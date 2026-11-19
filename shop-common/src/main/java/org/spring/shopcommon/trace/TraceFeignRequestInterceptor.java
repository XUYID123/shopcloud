package org.spring.shopcommon.trace;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.core.Ordered;

/**
 * 公共 Feign 出站拦截器：只传播当前可信调用上下文，不传递 Authorization、角色或内部凭据。
 * Feign 本身是同步调用，必须先在其执行线程绑定 MDC；不能从 Netty 线程变量猜测上下文。
 */
public class TraceFeignRequestInterceptor implements RequestInterceptor, Ordered {
    @Override
    public void apply(RequestTemplate template) {
        String traceId = MDC.get(TraceContext.MDC_KEY);
        // RequestTemplate.header 可能追加值，先按大小写不敏感删除，再设置唯一值。
        template.headers().keySet().stream()
                .filter(name -> name.equalsIgnoreCase(TraceContext.HEADER) || name.equalsIgnoreCase(TraceContext.LEGACY_HEADER))
                .toList().forEach(template::removeHeader);
        if (TraceContext.isValid(traceId)) {
            template.header(TraceContext.HEADER, traceId);
            template.header(TraceContext.LEGACY_HEADER, traceId);
        }
        // 定时任务等没有追踪上下文时不伪造已有链路；业务入口可显式 open(newId()) 创建新关联。
    }

    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
