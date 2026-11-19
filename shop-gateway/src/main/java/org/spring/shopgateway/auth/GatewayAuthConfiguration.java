package org.spring.shopgateway.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.redisson.Redisson;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.config.Config;
import org.spring.shopgateway.config.BusinessRedisProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;

/**
 * 认证鉴权组件的装配入口：Spring 启动时创建公钥验签器、两个专用线程池、权限执行器和 Feign 客户端。
 * 只在 gateway.auth.enabled=true（默认）时生效，不在这里处理每个 HTTP 请求。
 * 不启用 SecurityWebFilterChain，认证仍由 order=-50 的 GlobalFilter 在防刷之后执行。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(GatewayAuthProperties.class)
@EnableFeignClients(clients = UserPermissionClient.class)
public class GatewayAuthConfiguration {
    /**
     * Redisson 只负责权限冷加载锁，明确复用业务 Redis 的地址、DB、凭据和 TLS 设置。
     * 不使用 Redisson Starter，避免自动配置覆盖 6380 防刷和 6381 业务模板的职责。
     */
    @Bean(name = "businessRedissonClient", destroyMethod = "shutdown")
    RedissonReactiveClient businessRedissonClient(BusinessRedisProperties properties) {
        // 创建客户端发生在启动阶段；请求中使用 RLockReactive，不执行同步加锁/解锁。
        Config config = new Config();
        config.setNettyThreads(2);
        config.setThreads(2);
        // 锁不存储业务 JSON，字符串编码足够；权限文档仍由现有 Lettuce 模板处理。
        config.setCodec(org.redisson.client.codec.StringCodec.INSTANCE);
        var server = config.useSingleServer()
                .setAddress((properties.ssl() ? "rediss://" : "redis://") + properties.host() + ":" + properties.port())
                .setDatabase(properties.database())
                .setConnectTimeout(Math.toIntExact(properties.timeout().toMillis()))
                .setTimeout(Math.toIntExact(properties.timeout().toMillis()))
                // 禁止不确定结果的自动重试；失败由过滤器统一转换成 503。
                .setRetryAttempts(0)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        // 空凭据保持未配置，兼容默认 requirepass 和显式 ACL 用户两种部署。
        if (properties.username() != null && !properties.username().isBlank()) server.setUsername(properties.username());
        if (properties.password() != null && !properties.password().isEmpty()) server.setPassword(properties.password());
        return Redisson.createReactive(config);
    }

    /** 创建验签工作线程池；Bean 销毁时调用 dispose，避免应用停止后遗留线程。 */
    @Bean(destroyMethod = "dispose")
    Scheduler jwtVerificationScheduler() {
        // 至少两个线程；线程数参考 CPU 数，有界排队，名称用于日志/调试辨认 RSA 验签线程。
        return Schedulers.newBoundedElastic(Math.max(2, Runtime.getRuntime().availableProcessors()), 1000, "gateway-jwt");
    }

    /** 启动时读取固定 RSA 公钥并组合 JWT 校验规则，请求期间只复用创建好的验签器。 */
    @Bean
    JwtIdentityVerifier jwtIdentityVerifier(GatewayAuthProperties properties, ResourceLoader resources,
                                             @Qualifier("jwtVerificationScheduler") Scheduler jwtVerificationScheduler) throws Exception {
        RSAPublicKey key;
        // 仅启动时读取公钥文件；请求阶段没有文件 I/O，也不从 token 的 jku 等字段加载公钥。
        try (InputStream input = resources.getResource(properties.publicKeyLocation()).getInputStream()) {
            key = RsaKeyConverters.x509().convert(input);
        }
        // 缺少有效公钥或密钥过短即启动失败，不能退化成仅解析 Token 文本。
        if (key == null || key.getModulus().bitLength() < 2048) throw new IllegalArgumentException("RSA key must be at least 2048 bits");
        // 固定 RS256，避免按不可信 Token 中的算法或远程密钥地址决定信任方式。
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(key)
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            // sub 是唯一用户身份；要求正数 Long、有效期和正确受众，禁止把角色/权限带进 JWT 契约。
            String userId = jwt.getSubject();
            boolean valid = userId != null && userId.matches("[1-9][0-9]{0,18}")
                    && jwt.getExpiresAt() != null && jwt.getAudience() != null && jwt.getAudience().contains(properties.audience())
                    && !jwt.hasClaim("roles") && !jwt.hasClaim("permissions") && !jwt.hasClaim("authorities");
            if (valid) {
                // 正则只限制字符和位数，仍须检查 19 位数字是否超出 Long 上限。
                try { valid = Long.parseLong(userId) > 0; } catch (NumberFormatException exception) { valid = false; }
            }
            // 校验失败交由标准 JWT 异常链，过滤器最终统一返回 401。
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Required identity claims are invalid", null));
        };
        // 标准时间校验处理 exp/nbf 和时钟容差；issuer 校验与上面的必需声明校验共同生效。
        // 在签名验证后的声明校验阶段标记过期，避免依赖 Spring 异常 message 文本来识别过期。
        OAuth2TokenValidator<Jwt> expiryMarker = jwt -> jwt.getExpiresAt() != null
                && java.time.Instant.now().minus(properties.clockSkew()).isAfter(jwt.getExpiresAt())
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("access_token_expired", "Access token expired", null))
                : OAuth2TokenValidatorResult.success();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(properties.clockSkew()),
                new JwtIssuerValidator(properties.issuer()), requiredClaims, expiryMarker));
        return new JwtIdentityVerifier(decoder, jwtVerificationScheduler);
    }

    /** 共享文档校验器，供 Redis 读取、冷加载回填及受控规则写入共同使用。 */
    @Bean RbacCodec rbacCodec(ObjectMapper mapper, Validator validator) { return new RbacCodec(mapper, validator); }

    /** 创建阻塞 Feign 的隔离线程池；它与 JWT 的计算线程池分开，互不挤占等待队列。 */
    @Bean(destroyMethod = "dispose")
    Scheduler permissionFeignScheduler(GatewayAuthProperties properties) {
        // OpenFeign 为阻塞客户端：限制并行度和排队量，不占 Netty I/O 线程，也不无限扩容。
        return Schedulers.newBoundedElastic(properties.feignThreads(), 100, "permission-feign");
    }

    /**
     * 装配权限执行器，明确选用 6381 模板；@Lazy 延后 Feign 子容器初始化，避免启动时触发网关循环依赖。
     * Qualifier 也用于区分两个 Scheduler，防止把阻塞 HTTP 错放到验签线程池。
     */
    @Bean RbacPermissionCache rbacPermissionCache(@Qualifier("businessRedisTemplate") ReactiveStringRedisTemplate redis,
                                                  GatewayAuthProperties properties, RbacCodec codec,
                                                  @Lazy UserPermissionClient client,
                                                  @Qualifier("permissionFeignScheduler") Scheduler scheduler,
                                                  @Qualifier("businessRedissonClient") RedissonReactiveClient redisson,
                                                  InternalTokenSigner signer) {
        var cache = new RbacPermissionCache(redis, properties, codec, client, scheduler, redisson);
        cache.setInternalSigner(signer);
        return cache;
    }

    /** 受控规则/开发数据发布器；没有对应的匿名 HTTP 管理接口，也不再发送 Pub/Sub 消息。 */
    @Bean RbacRedisPublisher rbacRedisPublisher(@Qualifier("businessRedisTemplate") ReactiveStringRedisTemplate redis,
                                                GatewayAuthProperties properties, RbacCodec codec) {
        return new RbacRedisPublisher(redis, properties, codec);
    }

}
