package org.spring.shopcommon.idempotent;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证自动注册、宿主 Redis 模板隔离、显式关闭及自定义存储替换。 */
class IdempotentAutoConfigurationTests {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, IdempotentAutoConfiguration.class));

    @Test void automaticallyProxiesBeanWithoutReplacingExistingBusinessRedisTemplate() {
        runner.withUserConfiguration(UserBeans.class).withBean("businessRedisTemplate", StringRedisTemplate.class,
                () -> mock(StringRedisTemplate.class)).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(IdempotentAspect.class).hasSingleBean(RedisIdempotentStore.class);
            assertThat(AopUtils.isAopProxy(context.getBean(AnnotatedService.class))).isTrue();
            assertThat(context).hasSingleBean(StringRedisTemplate.class);
            var resources = context.getBean(IdempotentAutoConfiguration.RedisResources.class);
            int securityPort = Integer.parseInt(System.getenv().getOrDefault("SHOP_GATEWAY_REDIS_PORT", "6380"));
            assertThat(resources.factory().getPort()).isEqualTo(securityPort);
            assertThat(resources.template()).isNotSameAs(context.getBean(StringRedisTemplate.class));
        });
    }

    @Test void supportsExplicitStorageReplacementWithoutOpeningOwnConnection() {
        runner.withUserConfiguration(UserBeans.class).withBean(RedisIdempotentStore.class, () -> {
            var store = mock(RedisIdempotentStore.class); when(store.acquire(anyString(), any())).thenReturn(true); return store;
        }).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(IdempotentAutoConfiguration.RedisResources.class);
            assertThat(context.getBean(AnnotatedService.class).execute("42")).isEqualTo("42");
        });
    }

    @Test void disabledOrNonServletAppDoesNotCreateSynchronousAspect() {
        runner.withPropertyValues("shop.idempotent.enabled=false").run(context -> assertThat(context).doesNotHaveBean(IdempotentAspect.class));
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(IdempotentAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(IdempotentAspect.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class UserBeans { @Bean AnnotatedService annotatedService() { return new AnnotatedService(); } }
    public static class AnnotatedService {
        @Idempotent(key = "#id") public String execute(String id) { return id; }
    }
}
