package org.spring.shopgateway.auth;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import org.spring.shopcommon.auth.PermissionCacheKeys;

/**
 * Redis 权限文档的受控写入工具：主要用于初始化/修改接口规则，以及测试或开发数据准备。
 * 名称中的 Publisher 表示发布文档，不是 Pub/Sub 广播；没有匿名 HTTP 管理入口。
 * 真实用户权限通常由 RbacPermissionCache 经 Feign 冷加载，此工具不替代数据库中的角色管理。
 */
public class RbacRedisPublisher {
    private static final RedisScript<Long> SCRIPT = RedisScript.of(new ClassPathResource("redis/publish-rbac.lua"), Long.class); // 原子比较版本并写入。
    private final ReactiveStringRedisTemplate redis; // 明确来自 6381 businessRedisTemplate。
    private final GatewayAuthProperties properties; // 提供 Key 前缀、用户 TTL 与 Redis 超时。
    private final RbacCodec codec; // 写入前校验，保证文档能被鉴权器正确读取。

    /** 仅保存依赖，不在构造阶段自动初始化默认规则或给任何用户授予权限。 */
    public RbacRedisPublisher(ReactiveStringRedisTemplate redis, GatewayAuthProperties properties, RbacCodec codec) {
        this.redis = redis;
        this.properties = properties;
        this.codec = codec;
    }

    /** 接口规则首次写入传 null，更新传旧版本；TTL=0 表示规则不主动过期。 */
    public Mono<Long> publishRules(String expectedVersion, RbacModels.RuleSet rules) {
        return publish(List.of(properties.keyPrefix() + ":rules"), expectedVersion, rules, rules.version(), 0);
    }

    /** 开发/测试用户文档写入：同时更新 epoch，让先前在途冷加载不能覆盖本次结果。 */
    public Mono<Long> publishUser(String expectedVersion, RbacModels.UserGrant grant) {
        return publish(List.of(PermissionCacheKeys.user(properties.keyPrefix(), grant.userId()),
                PermissionCacheKeys.epoch(properties.keyPrefix(), grant.userId())),
                expectedVersion, grant, grant.version(), properties.cacheTtl().toMillis());
    }

    /** 复用同一 Lua 协议；返回 1 表示写入/幂等重试成功，负数表示版本冲突或原数据损坏。 */
    private Mono<Long> publish(List<String> keys, String expectedVersion, Object document, String newVersion, long ttlMillis) {
        // ARGV 依次为预期旧版本、新 JSON、新版本、TTL毫秒、新 epoch；空旧版本代表预期没有原文档。
        return Mono.defer(() -> redis.execute(SCRIPT, keys, List.of(
                        expectedVersion == null ? "" : expectedVersion, codec.write(document), newVersion,
                        Long.toString(ttlMillis), UUID.randomUUID().toString())).single()
                // 超时和脚本错误保持失败，不用内存写入假装 Redis 发布成功。
                .timeout(properties.redisTimeout())
                .flatMap(result -> result < 0
                        ? Mono.error(new IllegalStateException("RBAC publish rejected: version conflict or corrupt source (" + result + ")"))
                        : Mono.just(result)));
    }
}
