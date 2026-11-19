package org.spring.shopcommon.idempotent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/** 单 Key 原子占位存储；不通过 GET 后 SET 判断，不把 SETNX 与 EXPIRE 拆成两条命令。 */
public class RedisIdempotentStore {
    private static final Logger log = LoggerFactory.getLogger(RedisIdempotentStore.class);
    private final StringRedisTemplate redis;

    public RedisIdempotentStore(StringRedisTemplate redis) { this.redis = redis; }

    /** true 才能进入业务，false 是已占位，null/异常属于存储不可用而非重复提交。 */
    public boolean acquire(String key, Duration ttl) {
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, "1", ttl);
            // 普通调用不应返回 null；pipeline/事务模式没有即时结果时也必须拒绝，不可无保护执行。
            if (acquired == null) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            return acquired;
        } catch (DataAccessException error) {
            // 日志只记录故障类型，Trace MDC 会自动关联；不输出 Key、参数、密码或原始连接错误。
            log.warn("Idempotent Redis unavailable; type={}", error.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }
}
