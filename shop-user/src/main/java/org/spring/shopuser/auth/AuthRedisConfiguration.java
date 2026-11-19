package org.spring.shopuser.auth;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.Locale;

/**
 * 安全 Redis 独立连接。内部持有模板/工厂，不增加按类型候选，避免 Boot 的 6381 业务模板被替换。
 * Refresh Token 多 Key Lua 当前针对单节点；不能直接改成 Cluster 后继续使用跨槽脚本。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "shop.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuthRedisConfiguration {
    /** 由安全配置显式连接 6380，启动时校验与业务缓存端点隔离。 */
    @Bean(name = "authRedisResources", initMethod = "initialize", destroyMethod = "close")
    Resources authRedisResources(AuthProperties properties, DataRedisProperties business) {
        var p = properties.redis();
        if (p.port() == business.getPort() && host(p.host()).equals(host(business.getHost()))) {
            throw new IllegalArgumentException("Auth Redis must be physically isolated from business cache Redis");
        }
        var server = new RedisStandaloneConfiguration(p.host(), p.port());
        server.setDatabase(p.database());
        if (p.username() != null && !p.username().isBlank()) server.setUsername(p.username());
        if (p.password() != null && !p.password().isEmpty()) server.setPassword(p.password());
        var client = LettuceClientConfiguration.builder().commandTimeout(p.timeout()).clientOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(p.timeout()).build()).build());
        if (p.ssl()) client.useSsl();
        return new Resources(new LettuceConnectionFactory(server, client.build()));
    }
    /** 仅规范常见本地端点别名；不发 DNS 查询，不把不同逻辑 DB 当作物理隔离。 */
    private static String host(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) { case "localhost", "127.0.0.1", "::1" -> "loopback"; default -> value.toLowerCase(Locale.ROOT); };
    }
    /** 精确管理本模块连接生命周期，不向 Spring 暴露第二个默认 StringRedisTemplate。 */
    public static final class Resources implements AutoCloseable {
        private final LettuceConnectionFactory factory;
        private StringRedisTemplate template;
        Resources(LettuceConnectionFactory factory) { this.factory = factory; }
        public void initialize() { factory.afterPropertiesSet(); template = new StringRedisTemplate(factory); }
        public StringRedisTemplate template() { return template; }
        public LettuceConnectionFactory factory() { return factory; }
        @Override public void close() { factory.destroy(); }
    }
}
