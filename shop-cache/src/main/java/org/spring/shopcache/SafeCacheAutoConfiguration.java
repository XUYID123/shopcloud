package org.spring.shopcache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * opt-in Boot 自动配置：显式启用才创建业务缓存组件，没有 main、不注册 Nacos、不扫描业务包。
 * 只按命名 Bean 装配自身客户端，不替换业务服务原有 RedisTemplate 或网关双 Redis 配置。
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        "io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration",
        "io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration",
        "io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration"})
@ConditionalOnProperty(prefix = "shop.cache", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SafeCacheProperties.class)
public class SafeCacheAutoConfiguration {
    /**
     * 独立连接以资源容器持有，不向 Spring 增加 RedisConnectionFactory 候选。
     * 避免宿主的默认/响应式 Redis 自动配置因出现两个无 Primary 工厂而启动失败。
     */
    @Bean(name = "safeCacheRedisResources", initMethod = "initialize", destroyMethod = "close")
    RedisResources safeCacheRedisResources(SafeCacheProperties properties) {
        var p = properties.redis();
        var server = new RedisStandaloneConfiguration(p.host(), p.port());
        server.setDatabase(p.database());
        if (p.username() != null && !p.username().isBlank()) server.setUsername(p.username());
        if (p.password() != null && !p.password().isEmpty()) server.setPassword(p.password());
        var client = LettuceClientConfiguration.builder().commandTimeout(p.timeout()).clientOptions(
                io.lettuce.core.ClientOptions.builder().socketOptions(io.lettuce.core.SocketOptions.builder()
                        .connectTimeout(p.timeout()).build()).build());
        if (p.ssl()) client.useSsl();
        return new RedisResources(new LettuceConnectionFactory(server, client.build()));
    }

    /** 明确使用字符串序列化，保留可审计的 NULL_VAL/JSON_V1 格式，禁止 JDK 原生对象反序列化。 */
    @Bean("safeCacheRedisTemplate")
    StringRedisTemplate safeCacheRedisTemplate(@Qualifier("safeCacheRedisResources") RedisResources resources) {
        return new StringRedisTemplate(resources.factory());
    }

    /** Redisson core，不使用 Starter；与模板读取同一份连接配置，Bean 销毁时关闭连接/线程。 */
    @Bean(name = "safeCacheRedisson", destroyMethod = "shutdown")
    RedissonClient safeCacheRedisson(SafeCacheProperties properties) {
        var p = properties.redis();
        Config config = new Config();
        config.setNettyThreads(2);
        config.setThreads(2);
        config.setCodec(org.redisson.client.codec.StringCodec.INSTANCE);
        String host = p.host().contains(":") && !p.host().startsWith("[") ? "[" + p.host() + "]" : p.host();
        var server = config.useSingleServer().setAddress((p.ssl() ? "rediss://" : "redis://") + host + ":" + p.port())
                .setDatabase(p.database()).setConnectTimeout(Math.toIntExact(p.timeout().toMillis()))
                .setTimeout(Math.toIntExact(p.timeout().toMillis())).setRetryAttempts(0)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        if (p.username() != null && !p.username().isBlank()) server.setUsername(p.username());
        if (p.password() != null && !p.password().isEmpty()) server.setPassword(p.password());
        return Redisson.create(config);
    }

    /** Redis I/O 工作线程与数据库分别限流，满队列立即拒绝，不使用公共 ForkJoinPool。 */
    @Bean(name = "safeCacheRedisWorkers", destroyMethod = "shutdownNow")
    ThreadPoolExecutor safeCacheRedisWorkers(SafeCacheProperties properties) { return pool("safe-cache-redis", properties.redisPool()); }

    /** 物理并发上限：TimeLimiter 不能强杀忽略中断的 JDBC，队列/线程上限防止资源无限增长。 */
    @Bean(name = "safeCacheDatabaseWorkers", destroyMethod = "shutdownNow")
    ThreadPoolExecutor safeCacheDatabaseWorkers(SafeCacheProperties properties) { return pool("safe-cache-db", properties.databasePool()); }

    /** 使用 Resilience4j 自动配置的 Registry，使 application.yml 中的实例配置真正生效。 */
    @Bean
    CacheDependencyExecutor cacheDependencyExecutor(RetryRegistry retries, CircuitBreakerRegistry breakers, TimeLimiterRegistry timeLimiters,
            @Qualifier("safeCacheRedisWorkers") ThreadPoolExecutor redisWorkers,
            @Qualifier("safeCacheDatabaseWorkers") ThreadPoolExecutor databaseWorkers) {
        return new CacheDependencyExecutor(retries, breakers, timeLimiters, redisWorkers, databaseWorkers);
    }

    /** 单例业务入口，不注册 HTTP 接口；业务 Service 通过构造器注入。 */
    @Bean
    SafeCacheTemplate safeCacheTemplate(@Qualifier("safeCacheRedisTemplate") StringRedisTemplate redis,
            @Qualifier("safeCacheRedisson") RedissonClient redisson, ObjectMapper mapper,
            SafeCacheProperties properties, CacheDependencyExecutor dependencies) {
        return new SafeCacheTemplate(redis, redisson, mapper, properties, dependencies);
    }

    /** 固定线程数、有界队列、明确拒绝策略；线程命名便于定位超时与慢查询。 */
    private static ThreadPoolExecutor pool(String prefix, SafeCacheProperties.Pool properties) {
        AtomicInteger ids = new AtomicInteger();
        return new ThreadPoolExecutor(properties.threads(), properties.threads(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()), task -> {
                    Thread thread = new Thread(task, prefix + "-" + ids.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 显式管理内部连接生命周期，但不让它参与宿主 Redis 工厂按类型自动选择。 */
    public static final class RedisResources implements AutoCloseable {
        private final LettuceConnectionFactory factory;
        RedisResources(LettuceConnectionFactory factory) { this.factory = factory; }
        /** 等价于 Spring 对独立工厂 Bean 的初始化；只在启动阶段调用。 */
        public void initialize() { factory.afterPropertiesSet(); }
        /** 模板与诊断代码可取受控工厂，不应将其再注册成第二个候选 Bean。 */
        public LettuceConnectionFactory factory() { return factory; }
        /** 关闭本模块拥有的连接，不关闭 Redis 服务端或其他模块的连接工厂。 */
        @Override public void close() { factory.destroy(); }
    }
}
