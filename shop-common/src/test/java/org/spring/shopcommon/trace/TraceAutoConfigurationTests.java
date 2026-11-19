package org.spring.shopcommon.trace;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** 自动配置是接入点：不能要求业务模块额外扫描 common，也不能把 Servlet 配置装进网关。 */
class TraceAutoConfigurationTests {
    @Test void servletAppGetsOneHighestPriorityFilterAndDecoratedExecutor() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(TraceAutoConfiguration.class))
                // 覆盖默认装饰器时不强制特定 Bean 名，验证扩展点不会因 Qualifier 耦合而失效。
                .withBean("customMdcDecorator", MdcTaskDecorator.class, MdcTaskDecorator::new).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FilterRegistrationBean.class);
            assertThat(context.getBean(FilterRegistrationBean.class).getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
            var executor = context.getBean("traceTaskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(context.getBean("applicationTaskExecutor")).isSameAs(executor);
            try {
                MDC.put("traceId", "auto-configured");
                assertThat(CompletableFuture.supplyAsync(() -> MDC.get("traceId"), executor).get(3, TimeUnit.SECONDS)).isEqualTo("auto-configured");
            } finally { MDC.clear(); }
        });
    }

    @Test void noFeignOrServletRuntimeDoesNotLoadOptionalComponents() {
        new ApplicationContextRunner().withClassLoader(new FilteredClassLoader("feign", "jakarta.servlet"))
                .withConfiguration(AutoConfigurations.of(TraceAutoConfiguration.class)).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("traceFeignRequestInterceptor");
                    assertThat(context).doesNotHaveBean("traceServletFilterRegistration");
                    assertThat(context).doesNotHaveBean("traceTaskExecutor");
                });
    }

    @Test void asyncAnnotationUsesDecoratedExecutorWithoutLosingCallerState() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(TraceAutoConfiguration.class))
                .withUserConfiguration(AsyncProbeConfiguration.class).run(context -> {
                    try {
                        MDC.put("traceId", "annotated-async");
                        assertThat(context.getBean(AsyncProbe.class).read().get(3, TimeUnit.SECONDS)).isEqualTo("annotated-async");
                        assertThat(MDC.get("traceId")).isEqualTo("annotated-async");
                    } finally { MDC.clear(); }
                });
    }
    @Configuration(proxyBeanMethods = false)
    static class AsyncProbeConfiguration {
        @Bean AsyncProbe asyncProbe() { return new AsyncProbe(); }
    }
    public static class AsyncProbe {
        @Async("traceTaskExecutor")
        public CompletableFuture<String> read() { return CompletableFuture.completedFuture(MDC.get("traceId")); }
    }
}
