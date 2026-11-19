package org.spring.shopuser.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定 shop-user 的 shop.permissions 配置，独立于网关模块，不反向依赖网关实现。
 * @param keyPrefix 用户权限失效使用的 Redis 前缀，必须与网关 gateway.auth.key-prefix 一致
 */
@Validated
@ConfigurationProperties(value = "shop.permissions", ignoreUnknownFields = false)
public record UserPermissionProperties(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9:_-]{1,80}") String keyPrefix) { }
