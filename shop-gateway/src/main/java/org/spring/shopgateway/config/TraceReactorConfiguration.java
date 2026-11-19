package org.spring.shopgateway.config;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;
import org.spring.shopcommon.trace.TraceContext;
import org.springframework.context.annotation.Configuration;

/**
 * 为 Reactor 的自动上下文传播注册 MDC 适配器，不引入 Sleuth/Zipkin/完整链路上报组件。
 * Boot 根据 spring.reactor.context-propagation=auto 开启自动模式：每个回调执行前绑定、结束后恢复。
 */
@Configuration(proxyBeanMethods = false)
public class TraceReactorConfiguration {
    public TraceReactorConfiguration() {
        // 注册表是 JVM 级；适配器不持有请求状态，允许多个测试容器复用，避免覆盖别人的 accessor。
        ContextRegistry registry = ContextRegistry.getInstance();
        synchronized (registry) {
            if (registry.getThreadLocalAccessors().stream().noneMatch(accessor -> TraceContext.REACTOR_KEY.equals(accessor.key()))) {
                registry.registerThreadLocalAccessor(new TraceIdAccessor());
            }
        }
    }

    /** 只处理 traceId 字段；业务 MDC 的其他字段不被这个响应式适配器删除。 */
    private static final class TraceIdAccessor implements ThreadLocalAccessor<String> {
        @Override public Object key() { return TraceContext.REACTOR_KEY; }
        @Override public String getValue() { return MDC.get(TraceContext.MDC_KEY); }
        @Override public void setValue(String value) { MDC.put(TraceContext.MDC_KEY, value); }
        // Context 没有该键时也必须清理，防止复用线程继承另一个请求的 traceId。
        @Override public void setValue() { MDC.remove(TraceContext.MDC_KEY); }
    }
}
