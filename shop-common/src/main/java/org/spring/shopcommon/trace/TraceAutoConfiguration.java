package org.spring.shopcommon.trace;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 引入 common 即可获得追踪能力，无需扩大每个服务的组件扫描包。
 * MVC Filter 与异步池仅在 Servlet 应用注册；Feign 类缺失时不会装配对应拦截器。
 */
@AutoConfiguration(before = TaskExecutionAutoConfiguration.class)
public class TraceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    MdcTaskDecorator traceMdcTaskDecorator() { return new MdcTaskDecorator(); }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "feign.RequestInterceptor")
    static class FeignTracing {
        @Bean
        @ConditionalOnMissingBean(TraceFeignRequestInterceptor.class)
        TraceFeignRequestInterceptor traceFeignRequestInterceptor() { return new TraceFeignRequestInterceptor(); }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ServletTracing {
        @Bean
        @ConditionalOnMissingBean(name = "traceServletFilterRegistration")
        FilterRegistrationBean<TraceServletFilter> traceServletFilterRegistration() {
            var registration = new FilterRegistrationBean<>(new TraceServletFilter());
            registration.setName("traceServletFilter");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.setAsyncSupported(true);
            registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR));
            registration.addUrlPatterns("/*");
            return registration;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "shop.trace.async", name = "enabled", havingValue = "true", matchIfMissing = true)
    @EnableConfigurationProperties(TraceAsyncProperties.class)
    @EnableAsync
    static class AsyncTracing {
        /**
         * applicationTaskExecutor 同时供 MVC Callable 和默认 @Async 使用；traceTaskExecutor 是业务显式引用别名。
         * 已有自定义 Executor 时退让，防止覆盖原架构；那种情况应在原池 setTaskDecorator。
         */
        @Bean(name = {"applicationTaskExecutor", "traceTaskExecutor"})
        @ConditionalOnMissingBean(Executor.class)
        ThreadPoolTaskExecutor traceTaskExecutor(TraceAsyncProperties properties, MdcTaskDecorator decorator) {
            if (properties.getCorePoolSize() < 1 || properties.getMaxPoolSize() < properties.getCorePoolSize()
                    || properties.getQueueCapacity() < 0 || properties.getAwaitTerminationSeconds() < 0) {
                throw new IllegalArgumentException("Invalid shop.trace.async pool configuration");
            }
            var executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(properties.getCorePoolSize());
            executor.setMaxPoolSize(properties.getMaxPoolSize());
            executor.setQueueCapacity(properties.getQueueCapacity());
            executor.setThreadNamePrefix("trace-async-");
            executor.setTaskDecorator(decorator);
            // 拒绝时显式抛异常，避免静默丢任务或在请求线程执行阻塞任务。
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
            executor.setWaitForTasksToCompleteOnShutdown(true);
            executor.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());
            return executor;
        }
    }
}
