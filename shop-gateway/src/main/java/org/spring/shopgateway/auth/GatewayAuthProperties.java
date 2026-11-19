package org.spring.shopgateway.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 将 application.yml 的 gateway.auth 绑定成只读配置，供认证入口、验签器和 Redis 冷加载器使用。
 * 启动时执行 Bean Validation，拼错/遗留字段也会报错，不静默忽略失效的安全配置。
 * @param enabled 是否注册认证鉴权组件，默认开启
 * @param publicKeyLocation 签发方 RSA 公钥位置，支持 file/classpath，仅启动时读取
 * @param issuer 必须匹配的 JWT iss，即受信任签发方
 * @param audience JWT aud 必须包含的接收方标识
 * @param clockSkew 时间验证允许的时钟偏差，不代表额外缓存时间
 * @param maxTokenLength Authorization 请求头允许的最大字符数
 * @param keyPrefix 6381 Redis 权限 Key 前缀，两服务必须配置一致
 * @param cacheTtl 用户权限文档在 Redis 的 TTL，不是本地缓存 TTL
 * @param redisTimeout 每次 Redis 命令或 Lua 的超时
 * @param loadTimeout 整个用户加载过程的时限，包含等待锁和 Feign 回源
 * @param userServiceUrl Feign 显式地址；空字符串表示按 shop-user 服务名发现
 * @param feignThreads 阻塞 Feign 工作线程上限，不是 Netty 线程数
 * @param whitelist 按 HTTP 方法和路径定义的匿名接口，只跳过认证鉴权，不跳过前置防刷
 */
@Validated
@ConfigurationProperties(value = "gateway.auth", ignoreUnknownFields = false)
public record GatewayAuthProperties(
        @DefaultValue("true") boolean enabled,
        @NotBlank String publicKeyLocation,
        @DefaultValue("flour-shop-user") @NotBlank String issuer,
        @DefaultValue("flour-shop-api") @NotBlank String audience,
        @DefaultValue("30s") @DurationMin(seconds = 0) @DurationMax(minutes = 2) Duration clockSkew,
        @DefaultValue("8192") @Min(256) @Max(32768) int maxTokenLength,
        @DefaultValue("shop:rbac:v1") @Pattern(regexp = "[A-Za-z0-9:_-]{1,80}") String keyPrefix,
        @DefaultValue("30s") @DurationMin(seconds = 1) @DurationMax(minutes = 5) Duration cacheTtl,
        @DefaultValue("1s") @DurationMin(millis = 1) @DurationMax(seconds = 10) Duration redisTimeout,
        @DefaultValue("4s") @DurationMin(seconds = 1) @DurationMax(seconds = 10) Duration loadTimeout,
        @DefaultValue("") String userServiceUrl,
        @DefaultValue("16") @Min(1) @Max(64) int feignThreads,
        @Valid List<RbacModels.Endpoint> whitelist) {
    public GatewayAuthProperties {
        // 防止调用方修改已绑定的白名单；未配置时按空白名单处理，默认不公开受保护接口。
        whitelist = whitelist == null ? List.of() : List.copyOf(whitelist);
    }
}
