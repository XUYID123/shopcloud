package org.spring.shopgateway.protection;

import org.spring.shopgateway.config.ProtectionProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 使用 Redis Lua 脚本完成单个策略的滑动窗口检查、违规累计和临时封禁。
 * 一次脚本执行内的读写是原子的，多实例网关可共享计数，避免并发检查时超发额度。
 */
@Component
public class RedisAbuseLimiter {
    // KEYS、ARGV 和返回数组的位置约定见脚本头部，修改时须两端同步。
    private static final RedisScript<List> SCRIPT = RedisScript.of(
            new ClassPathResource("redis/anti-abuse.lua"), List.class);
    private final ReactiveStringRedisTemplate redis; // Qualifier 固定 6380，不与 6381 用户权限文档混用。
    private final ProtectionProperties.AntiAbuse properties; // 全部策略共用的窗口、封禁和超时参数。

    /** Spring 注入防刷连接和配置；构造器不执行 Lua 或消耗任何额度。 */
    public RedisAbuseLimiter(@Qualifier("reactiveStringRedisTemplate") ReactiveStringRedisTemplate redis,
                             ProtectionProperties properties) {
        this.redis = redis;
        this.properties = properties.antiAbuse();
    }

    /**
     * 检查某个 IP 在指定策略下是否可访问。每个策略独立执行一次脚本，
     * 普通额度和注册/登录额度之间不构成一个 Redis 事务。
     */
    public Mono<Decision> check(String policy, String clientIp, int limit) {
        return Mono.defer(() -> {
            // 摘要避免键名直接包含 IP；策略名参与摘要，使 all/register/login 分开计数。
            String slot = digest(policy + ":" + clientIp);
            // Redis Cluster 以大括号中的内容计算槽位，保证本次脚本的三个键处于同一槽。
            String base = properties.keyPrefix() + ":{" + slot + "}";
            // 三个键依次为：已放行事件 ZSET、违规次数、临时封禁标记。
            // 参数依次为：窗口毫秒、额度、事件 UUID、封禁阈值、封禁毫秒。
            // UUID 保证同一毫秒的多个请求仍是不同的 ZSET 成员，不会相互覆盖。
            return redis.execute(SCRIPT, List.of(base + ":events", base + ":violations", base + ":ban"),
                            List.of(Long.toString(properties.window().toMillis()), Integer.toString(limit),
                                    UUID.randomUUID().toString(), Integer.toString(properties.violationsBeforeBan()),
                                    Long.toString(properties.banDuration().toMillis())))
                    // 空结果属于检查失败，交给过滤器按依赖故障处理，不能默认放行。
                    .next().switchIfEmpty(Mono.error(new IllegalStateException("Empty Redis limiter response")))
                    // Lua 返回 [是否放行, 等待毫秒, 是否封禁]；毫秒向上取整为 Retry-After 秒数。
                    .map(values -> new Decision(((Number) values.get(0)).longValue() == 1,
                            (((Number) values.get(1)).longValue() + 999) / 1000,
                            ((Number) values.get(2)).longValue() == 1, false))
                    // 超时以响应式异常交给调用方；此处不阻塞线程，也不自行订阅。
                    .timeout(properties.redisTimeout());
        });
    }

    /** 生成固定长度的键片段；避免明文 IP 出现在键名，不代表完全匿名，也不是身份认证凭据。 */
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * 防刷检查结果。
     *
     * @param allowed 是否通过当前检查
     * @param retryAfterSeconds 拒绝时建议等待的秒数，放行时为 0
     * @param banned 是否命中临时封禁，而非仅达到窗口额度
     * @param unavailable 是否检查失败；由过滤器在异常兜底时构造，不由 Lua 返回
     */
    public record Decision(boolean allowed, long retryAfterSeconds, boolean banned, boolean unavailable) {
        /** 所有适用策略均通过时使用的汇总结果。 */
        public static Decision permitted() {
            return new Decision(true, 0, false, false);
        }
    }
}
