package org.spring.shopuser.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.time.DurationMin;
import org.hibernate.validator.constraints.time.DurationMax;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

/** 登录签发与安全 Redis 的独立配置；业务权限 Redis 仍使用 spring.data.redis 的 6381。 */
@Validated
@ConfigurationProperties(prefix = "shop.auth", ignoreUnknownFields = false)
public record AuthProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("file:../infra/local-secrets/jwt-private.pem") @NotBlank String privateKeyLocation,
        @DefaultValue("file:../infra/local-secrets/jwt-public.pem") @NotBlank String publicKeyLocation,
        @DefaultValue("flour-shop-user") @NotBlank String issuer,
        @DefaultValue("flour-shop-api") @NotBlank String audience,
        @DefaultValue("30m") @DurationMin(seconds = 1) @DurationMax(hours = 1) Duration accessTtl,
        @DefaultValue("7d") @DurationMin(seconds = 1) @DurationMax(days = 30) Duration refreshTtl,
        @DefaultValue("0s") @DurationMin(seconds = 0) @DurationMax(minutes = 2) Duration clockSkew,
        @DefaultValue("8192") @Min(256) @Max(32768) int maxTokenLength,
        @DefaultValue("auth") @Pattern(regexp = "[A-Za-z0-9:_-]{1,100}") String keyPrefix,
        @DefaultValue @Valid SecurityRedis redis) {

    /** 会话凭据只进 6380 noeviction/AOF 安全实例；不能使用会被业务缓存淘汰的 6381。 */
    public record SecurityRedis(
            @DefaultValue("127.0.0.1") @NotBlank String host,
            @DefaultValue("6380") @Min(1) @Max(65535) int port,
            @DefaultValue("") String username,
            @DefaultValue("shop_gateway_local_dev") String password,
            @DefaultValue("0") @Min(0) int database,
            @DefaultValue("1s") @DurationMin(millis = 1) @DurationMax(seconds = 10) Duration timeout,
            @DefaultValue("false") boolean ssl) { }
}
