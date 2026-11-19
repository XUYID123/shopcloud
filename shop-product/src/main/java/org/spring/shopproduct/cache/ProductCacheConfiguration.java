package org.spring.shopproduct.cache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
class ProductCacheConfiguration {
    /** 已有应用级 registry 时复用。当前模块用轻量 registry 收集计数，不额外开启监控端点。 */
    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    SimpleMeterRegistry productCacheMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "shop.product.cache.invalidation.poll-enabled", matchIfMissing = true)
    static class InvalidationPolling { }
}
