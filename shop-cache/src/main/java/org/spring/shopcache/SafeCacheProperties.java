package org.spring.shopcache;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.time.DurationMin;
import org.hibernate.validator.constraints.time.DurationMax;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 同步缓存模板的配置，只有显式 shop.cache.enabled=true 才创建客户端和线程池。
 * 缓存与锁连接同一个业务 Redis；不能将本组件指向 noeviction 的 6380 防刷实例。
 */
@Validated
@ConfigurationProperties(prefix = "shop.cache", ignoreUnknownFields = false)
public record SafeCacheProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("shop:cache:v1") @Pattern(regexp = "[a-zA-Z0-9:_-]{1,100}") String namespace,
        @DefaultValue("5m") @DurationMin(millis = 1) @DurationMax(days = 30) Duration ttl,
        @DefaultValue("60s") @DurationMin(millis = 0) @DurationMax(days = 1) Duration jitter,
        @DefaultValue("15s") @DurationMin(millis = 1) @DurationMax(minutes = 5) Duration nullTtl,
        @DefaultValue("1s") @DurationMin(millis = 0) @DurationMax(seconds = 10) Duration lockWait,
        @DefaultValue("10s") @DurationMin(millis = 1) @DurationMax(minutes = 5) Duration lockLease,
        @DefaultValue("262144") @Min(128) @Max(10485760) int maxValueBytes,
        @DefaultValue @Valid Redis redis,
        @DefaultValue @Valid Pool redisPool,
        @DefaultValue @Valid Pool databasePool) {

    /** 负缓存应比正常数据更短，防止新创建的数据长时间被已有空值遮蔽。 */
    @AssertTrue(message = "null-ttl must not exceed ttl")
    public boolean isNullTtlShortEnough() { return nullTtl != null && ttl != null && nullTtl.compareTo(ttl) <= 0; }

    /** 地址只配置一份，两个客户端均按该端点创建，避免锁和数据落入不同实例。 */
    public record Redis(
            @DefaultValue("127.0.0.1") @NotBlank String host,
            @DefaultValue("6381") @Min(1) @Max(65535) int port,
            @DefaultValue("") String username,
            @DefaultValue("shop_cache_local_dev") String password,
            @DefaultValue("0") @Min(0) int database,
            @DefaultValue("1s") @DurationMin(millis = 1) @DurationMax(seconds = 10) Duration timeout,
            @DefaultValue("false") boolean ssl) { }

    /** 有界线程和队列形成依赖隔离；队列满立即拒绝，不在调用线程执行耗时任务。 */
    public record Pool(@DefaultValue("8") @Min(1) @Max(128) int threads,
                       @DefaultValue("32") @Min(1) @Max(10000) int queueCapacity) { }
}
