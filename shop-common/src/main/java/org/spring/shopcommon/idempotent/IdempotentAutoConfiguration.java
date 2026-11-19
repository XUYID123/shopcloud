package org.spring.shopcommon.idempotent;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * common 引入即装配的同步防重组件：按 Boot imports SPI 注册，不要求业务包扫描 common。
 * 仅在 Servlet 应用启用，WebFlux 网关不安装阻塞 Redis 切面；不注册/替换宿主的 Redis 工厂或模板。
 */
@AutoConfiguration(after = {AopAutoConfiguration.class, DataRedisAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "shop.idempotent", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IdempotentProperties.class)
public class IdempotentAutoConfiguration {
    /**
     * Resources 内部持有连接/字符串模板，不成为按类型注入候选，防止 user 的 6381 模板被挤掉。
     * noeviction 安全实例的 idempotent: 命名空间与 auth:/gateway:abuse: 数据彼此独立。
     */
    @Bean(name = "idempotentRedisResources", initMethod = "initialize", destroyMethod = "close")
    @ConditionalOnMissingBean({RedisIdempotentStore.class, RedisResources.class})
    RedisResources idempotentRedisResources(IdempotentProperties properties, Environment environment) {
        var p = properties.getRedis();
        String host = p.getHost() != null ? p.getHost() : environment.getProperty("SHOP_GATEWAY_REDIS_HOST", "127.0.0.1");
        int port = p.getPort() != null ? p.getPort() : environment.getProperty("SHOP_GATEWAY_REDIS_PORT", Integer.class, 6380);
        String password = p.getPassword() != null ? p.getPassword()
                : environment.getProperty("SHOP_GATEWAY_REDIS_PASSWORD", "shop_gateway_local_dev");
        Duration timeout = p.getTimeout();
        if (host.isBlank() || port < 1 || port > 65535 || p.getDatabase() < 0 || timeout == null
                || timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Invalid shop.idempotent.redis configuration");
        }
        var server = new RedisStandaloneConfiguration(host, port);
        server.setDatabase(p.getDatabase());
        if (p.getUsername() != null && !p.getUsername().isBlank()) server.setUsername(p.getUsername());
        if (!password.isEmpty()) server.setPassword(password);
        var builder = LettuceClientConfiguration.builder().commandTimeout(timeout).clientOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(timeout).build()).build());
        if (p.isSsl()) builder.useSsl();
        return new RedisResources(new LettuceConnectionFactory(server, builder.build()));
    }

    @Bean
    @ConditionalOnMissingBean
    RedisIdempotentStore redisIdempotentStore(RedisResources resources) { return new RedisIdempotentStore(resources.template()); }

    @Bean
    @ConditionalOnMissingBean
    IdempotentKeyResolver idempotentKeyResolver(Environment environment) {
        return new IdempotentKeyResolver(environment.getProperty("spring.application.name", "shop-service"));
    }

    @Bean
    @ConditionalOnMissingBean
    IdempotentAspect idempotentAspect(RedisIdempotentStore store, IdempotentKeyResolver keys) {
        return new IdempotentAspect(store, keys);
    }

    /** 生命周期仅归本组件所有；创建时不探测 Redis 连通性，首次使用连接受超时限制。 */
    public static final class RedisResources implements AutoCloseable {
        private final LettuceConnectionFactory factory;
        private StringRedisTemplate template;
        RedisResources(LettuceConnectionFactory factory) { this.factory = factory; }
        public void initialize() { factory.afterPropertiesSet(); template = new StringRedisTemplate(factory); }
        public StringRedisTemplate template() { return template; }
        public LettuceConnectionFactory factory() { return factory; }
        @Override public void close() { factory.destroy(); }
    }
}
