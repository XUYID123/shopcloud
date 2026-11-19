package org.spring.shopcache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 同步 cache-aside 模板：短 TTL 空值防穿透、Redisson 双重检查防击穿、随机 TTL 缓解雪崩。
 * Redis/数据库通过独立 Resilience4j 保护；故障不等同于缓存未命中，不会无锁直冲数据库。
 * 仅用于普通业务读取；不接管网关的响应式 JWT/RBAC，也不缓存有密码等敏感字段的数据库实体。
 * 此对象无请求级可变状态，可作为单例。loader 必须为只读、幂等且在线程内自行建立事务的查询。
 */
public class SafeCacheTemplate {
    private static final Logger log = LoggerFactory.getLogger(SafeCacheTemplate.class);
    private static final String NULL_VAL = "NULL_VAL"; // 明确不存在的负缓存，不代表依赖异常。
    private static final String JSON_PREFIX = "JSON_V1:"; // 与真实字符串 "NULL_VAL" 隔离，避免标记碰撞。
    // 加锁后建立有限期加载代号；删除缓存会同时删除代号，阻止在途旧查询再次填入。
    private static final RedisScript<Long> BEGIN = RedisScript.of("""
            if redis.call('HEXISTS', KEYS[1], ARGV[1]) ~= 1 then return 0 end
            redis.call('PSETEX', KEYS[2], ARGV[3], ARGV[2])
            return 1
            """, Long.class);
    // 固定 Redisson 3.52.0 普通 RLock 的 Hash 格式；检查与回填必须位于同一个原子脚本。
    private static final RedisScript<Long> FILL = RedisScript.of("""
            if redis.call('HEXISTS', KEYS[1], ARGV[1]) ~= 1
                or redis.call('GET', KEYS[2]) ~= ARGV[2] then return 0 end
            redis.call('PSETEX', KEYS[3], ARGV[4], ARGV[3])
            return 1
            """, Long.class);
    // 失效时只删数据和加载代号；绝不删除其他请求持有的 Redisson 锁。
    private static final RedisScript<Long> EVICT = RedisScript.of(
            "return redis.call('DEL', KEYS[1], KEYS[2])", Long.class);
    private final StringRedisTemplate redis; // RedisTemplate<String,String> 的标准字符串子类。
    private final RedissonClient redisson; // 与上述模板连接相同的业务 Redis 端点和逻辑 DB。
    private final ObjectMapper mapper;
    private final SafeCacheProperties properties;
    private final CacheDependencyExecutor dependencies;

    /** 依赖由自动配置显式装配，不查找全局静态 Bean，也不推测两个 Redis 中哪个是业务实例。 */
    public SafeCacheTemplate(StringRedisTemplate redis, RedissonClient redisson, ObjectMapper mapper,
                             SafeCacheProperties properties, CacheDependencyExecutor dependencies) {
        this.redis = redis;
        this.redisson = redisson;
        this.mapper = mapper;
        this.properties = properties;
        this.dependencies = dependencies;
    }

    /** 最简调用：未找到返回 null；基础设施失败则抛 CacheUnavailableException，由服务统一映射 503。 */
    public <T> T get(String key, Class<T> type, CacheLoader<T> loader) {
        return get(key, type, loader, error -> { throw error; });
    }

    /** 业务可显式提供只读降级函数；降级结果从不回填 Redis，避免把临时默认值当成真实业务数据。 */
    public <T> T get(String key, Class<T> type, CacheLoader<T> loader,
                     Function<CacheUnavailableException, T> fallback) {
        return get(key, mapper.constructType(type), loader, fallback);
    }

    /** 泛型集合也可保留完整类型，例如 new TypeReference<List<ProductVO>>() {}。 */
    public <T> T get(String key, TypeReference<T> type, CacheLoader<T> loader,
                     Function<CacheUnavailableException, T> fallback) {
        return get(key, mapper.getTypeFactory().constructType(type), loader, fallback);
    }

    /** 统一类型入口：只捕获已归类的依赖/锁故障，业务异常不会被吞掉或写成空缓存。 */
    private <T> T get(String key, JavaType type, CacheLoader<T> loader,
                      Function<CacheUnavailableException, T> fallback) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(fallback, "fallback");
        Keys keys = keys(key);
        try {
            Entry<T> first = read(keys, type);
            if (first.hit()) return first.value();
            return load(keys, type, loader);
        } catch (CacheUnavailableException error) {
            // 仅记录哈希和故障类型；不在日志中输出业务 Key、JSON、SQL 或 Redis 密码。
            log.warn("SafeCache fallback; keyHash={}, type={}", keys.hash(), rootType(error));
            return fallback.apply(error);
        }
    }

    /** 先解析命中/负缓存，格式损坏属于故障，不能误当 miss 触发所有请求同时查库。 */
    private <T> Entry<T> read(Keys keys, JavaType type) {
        String encoded = dependencies.execute(CacheDependencyExecutor.REDIS_READ,
                () -> redis.opsForValue().get(keys.data()));
        if (encoded == null) return new Entry<>(false, null);
        if (NULL_VAL.equals(encoded)) return new Entry<>(true, null);
        try {
            if (!encoded.startsWith(JSON_PREFIX) || encoded.getBytes(StandardCharsets.UTF_8).length > properties.maxValueBytes()) {
                throw new CacheUnavailableException("Invalid cache document");
            }
            T value = mapper.readValue(encoded.substring(JSON_PREFIX.length()), type);
            // JSON null 不允许冒充负缓存标记，否则无法区分格式错误与明确不存在。
            if (value == null) throw new CacheUnavailableException("Unexpected JSON null in cache");
            return new Entry<>(true, value);
        } catch (JsonProcessingException error) {
            throw new CacheUnavailableException("Cache document cannot be decoded", error);
        }
    }

    /** 临界区运行在 MVC/任务调用线程，锁归属始终一致；数据库任务另在线程池中执行。 */
    private <T> T load(Keys keys, JavaType type, CacheLoader<T> loader) {
        RLock lock = redisson.getLock(keys.lock());
        boolean acquired = false;
        // 同步 API 的调用线程不会在方法内部迁移；归属字段与 Redisson 的 Hash 实现一致。
        long ownerId = Thread.currentThread().getId();
        String ownerField = redisson.getId() + ":" + ownerId;
        try {
            try {
                acquired = lock.tryLock(properties.lockWait().toMillis(), properties.lockLease().toMillis(), TimeUnit.MILLISECONDS);
            } catch (org.redisson.client.RedisException error) {
                throw new CacheUnavailableException("Cache load lock unavailable", error);
            }
            if (!acquired) {
                // 持锁者可能刚回填，最后再读一次；仍未命中就降级，不越过互斥锁直接查询数据库。
                Entry<T> latest = read(keys, type);
                if (latest.hit()) return latest.value();
                throw new CacheUnavailableException("Cache load lock busy");
            }
            // Double-Check：先前读到 miss 不代表拿锁时仍然没有缓存。
            Entry<T> second = read(keys, type);
            if (second.hit()) return second.value();
            String generation = UUID.randomUUID().toString();
            Long started = dependencies.execute(CacheDependencyExecutor.REDIS_WRITE, () -> redis.execute(BEGIN,
                    List.of(keys.lock(), keys.generation()), ownerField, generation, Long.toString(properties.lockLease().toMillis())));
            if (!Long.valueOf(1).equals(started)) throw new CacheUnavailableException("Cache load lock expired before loading");
            // 只读 loader 的异常不会被当成 null；TimeLimiter 超时后这里不会继续执行回填。
            T value = dependencies.execute(CacheDependencyExecutor.DATABASE, loader::load);
            String encoded = encode(value);
            Duration ttl = value == null ? properties.nullTtl() : jitteredTtl();
            Long written;
            try {
                written = dependencies.execute(CacheDependencyExecutor.REDIS_WRITE, () -> redis.execute(FILL,
                        List.of(keys.lock(), keys.generation(), keys.data()), ownerField, generation, encoded, Long.toString(ttl.toMillis())));
            } catch (CacheUnavailableException error) {
                // 数据库已经成功时，单纯缓存写失败不抹掉真实结果；不再发起额外数据库查询。
                log.warn("SafeCache refill unavailable; keyHash={}, type={}", keys.hash(), rootType(error));
                return value;
            }
            // 明确失去锁/发生主动失效，拒绝使用这份旧加载结果；不能误判成普通写失败。
            if (!Long.valueOf(1).equals(written)) throw new CacheUnavailableException("Cache changed during load");
            return value;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CacheUnavailableException("Cache load interrupted", error);
        } finally {
            // 只在成功加锁后解锁；失败不覆盖数据库/业务原始异常，有限租期负责进程退出后的兜底。
            if (acquired) unlock(lock, ownerId, keys.hash());
        }
    }

    /** 业务事务成功提交后调用；删除缓存同时撤销在途加载的回填资格。 */
    public void evict(String key) {
        Keys keys = keys(key);
        dependencies.execute(CacheDependencyExecutor.REDIS_WRITE,
                () -> redis.execute(EVICT, List.of(keys.data(), keys.generation())));
    }

    /** 空值使用独立短 TTL；正常数据在基础 TTL 上增加 [0,jitter] 毫秒扰动。 */
    Duration jitteredTtl() {
        long offset = properties.jitter().toMillis();
        return properties.ttl().plusMillis(offset == 0 ? 0 : ThreadLocalRandom.current().nextLong(offset + 1));
    }

    /** 缓存只接受显式 DTO，大小限制在调用 Redis 前执行，序列化失败视为程序/契约错误。 */
    private <T> String encode(T value) {
        if (value == null) return NULL_VAL;
        try {
            String encoded = JSON_PREFIX + mapper.writeValueAsString(value);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > properties.maxValueBytes()) {
                throw new IllegalArgumentException("Cache value exceeds configured maximum bytes");
            }
            return encoded;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Cache value cannot be encoded", error);
        }
    }

    /** 解锁本身也有有限等待；异步命令即使晚到，Redisson 仍按 owner 校验，不会删除别人的锁。 */
    private void unlock(RLock lock, long ownerId, String hash) {
        try {
            lock.unlockAsync(ownerId).get(properties.redis().timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            log.warn("SafeCache unlock interrupted; keyHash={}", hash);
        } catch (Exception error) {
            log.warn("SafeCache unlock unavailable; keyHash={}, type={}", hash, rootType(error));
        }
    }

    /** SHA-256 固定物理 Key 长度，统一 Hash Tag，让锁/代号/数据的 Lua Key 处于同一 Redis slot。 */
    Keys keys(String logicalKey) {
        if (logicalKey == null || logicalKey.isBlank() || logicalKey.length() > 512) {
            throw new IllegalArgumentException("Cache key must contain 1..512 nonblank characters");
        }
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(logicalKey.getBytes(StandardCharsets.UTF_8)));
            String prefix = properties.namespace() + ":{" + hash + "}";
            return new Keys(hash, prefix + ":data", prefix + ":lock", prefix + ":generation");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    /** 日志只包含类型，避免 JDBC/Redis 异常 message 携带 SQL、地址或敏感参数。 */
    private static String rootType(Throwable error) {
        while (error.getCause() != null && error.getCause() != error) error = error.getCause();
        return error.getClass().getSimpleName();
    }

    /** hit=true,value=null 表示命中负缓存，与 hit=false 的真正 miss 区分。 */
    private record Entry<T>(boolean hit, T value) { }
    /** 物理 Key 仅用于本组件读写，业务代码只负责稳定的逻辑 Key 及版本。 */
    record Keys(String hash, String data, String lock, String generation) { }
}
