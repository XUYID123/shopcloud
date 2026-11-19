package org.spring.shopgateway.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Duration;

/**
 * 网关双 Redis 的唯一连接装配入口，只创建客户端，不负责创建 Docker 容器。
 * 6380 使用 spring.data.redis 的参数并标记 Primary，供 RedisAbuseLimiter 防刷使用。
 * 6381 使用 gateway.business-redis 的独立参数，供 RBAC 规则、用户缓存和加载锁使用。
 * 显式命名两个连接工厂与模板，避免 Boot 条件自动配置或按类型注入把两个职责混在一起。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DataRedisProperties.class, BusinessRedisProperties.class})
public class GatewayRedisConfiguration {
    /** 防刷连接工厂；沿用 Boot 常用 Bean 名称，让默认模板选择仍有明确目标。 */
    @Bean("redisConnectionFactory")
    @Primary
    LettuceConnectionFactory securityRedisConnectionFactory(DataRedisProperties properties) {
        // 复制全部地址/凭据/DB/TLS 配置；未指定超时时采用保守的 1 秒，不无限等待保护存储。
        return factory(properties.getHost(), properties.getPort(), properties.getUsername(),
                properties.getPassword(), properties.getDatabase(),
                properties.getTimeout() == null ? Duration.ofSeconds(1) : properties.getTimeout(),
                properties.getConnectTimeout() == null ? Duration.ofSeconds(1) : properties.getConnectTimeout(),
                properties.getSsl().isEnabled());
    }

    /** 防刷字符串模板：响应式调用命令/Lua，Qualifier 锁定防刷工厂，不依赖“恰好选中哪个”。 */
    @Bean("reactiveStringRedisTemplate")
    @Primary
    ReactiveStringRedisTemplate securityRedisTemplate(
            @Qualifier("redisConnectionFactory") LettuceConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(factory);
    }

    /** RBAC/业务缓存连接工厂，不设 Primary，权限执行器必须显式选择它。 */
    @Bean("businessRedisConnectionFactory")
    LettuceConnectionFactory businessRedisConnectionFactory(BusinessRedisProperties properties, DataRedisProperties security) {
        // 常见误配在启动时失败：同宿主机同端口不能仅靠不同 DB 假装物理实例隔离。
        // 此检查会统一本地回环别名，但不是对所有 DNS 别名进行网络拓扑审计。
        if (properties.port() == security.getPort() && normalizedHost(properties.host()).equals(normalizedHost(security.getHost()))) {
            throw new IllegalArgumentException("Business Redis must not share the anti-abuse Redis endpoint");
        }
        return factory(properties.host(), properties.port(), properties.username(), properties.password(),
                properties.database(), properties.timeout(), properties.timeout(), properties.ssl());
    }

    /** 业务字符串模板：RbacPermissionCache/Publisher 只注入此 Bean，不能复用防刷模板。 */
    @Bean("businessRedisTemplate")
    ReactiveStringRedisTemplate businessRedisTemplate(
            @Qualifier("businessRedisConnectionFactory") LettuceConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(factory);
    }

    /** 共用客户端创建步骤，但每次调用仍产生独立工厂；当前使用单节点连接配置。 */
    private static LettuceConnectionFactory factory(String host, int port, String username, String password,
                                                     int database, Duration timeout, Duration connectTimeout, boolean ssl) {
        // RedisStandaloneConfiguration 描述一个 Redis 实例，并没有自动部署/启用 Sentinel 或 Cluster。
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(host, port);
        server.setDatabase(database);
        // username 用于 Redis ACL；只配置 password 时仍兼容默认用户的 requirepass。
        if (username != null && !username.isBlank()) server.setUsername(username);
        if (password != null && !password.isEmpty()) server.setPassword(password);
        // 命令等待与连接建立是两个超时；网络 I/O 由 Lettuce 异步处理。
        var client = LettuceClientConfiguration.builder().commandTimeout(timeout)
                .clientOptions(ClientOptions.builder().socketOptions(
                        SocketOptions.builder().connectTimeout(connectTimeout).build()).build());
        // 是否启用 Redis TLS 由配置决定，不能把打开密码认证理解成已经加密传输。
        if (ssl) client.useSsl();
        return new LettuceConnectionFactory(server, client.build());
    }

    /** 仅用于配置误配检查，统一常见本地回环写法；不发 DNS 请求。 */
    private static String normalizedHost(String host) {
        return switch (host.toLowerCase(java.util.Locale.ROOT)) {
            case "localhost", "127.0.0.1", "::1" -> "loopback";
            default -> host.toLowerCase(java.util.Locale.ROOT);
        };
    }
}
