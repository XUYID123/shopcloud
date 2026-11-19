package org.spring.shopgateway.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 绑定 gateway.business-redis，由 GatewayRedisConfiguration 创建独立的 6381 连接工厂。
 * @param host 业务 Redis 主机，不与防刷实例共用同一端点
 * @param port Redis 对外端口，默认 6381
 * @param username 可选 ACL 用户名，空字符串表示使用默认用户
 * @param password Redis 连接密码；默认值仅用于本地开发
 * @param database 实例内部逻辑 DB 编号，不能替代物理隔离
 * @param timeout 业务连接和命令的超时配置
 * @param ssl 是否使用 TLS 连接 Redis，需服务器端同时支持
 */
@Validated
@ConfigurationProperties("gateway.business-redis")
public record BusinessRedisProperties(
        @DefaultValue("127.0.0.1") @NotBlank String host,
        @DefaultValue("6381") @Min(1) @Max(65535) int port,
        @DefaultValue("") String username,
        @DefaultValue("shop_cache_local_dev") String password,
        @DefaultValue("0") @Min(0) int database,
        @DefaultValue("1s") @DurationMin(millis = 1) Duration timeout,
        @DefaultValue("false") boolean ssl) {
}
