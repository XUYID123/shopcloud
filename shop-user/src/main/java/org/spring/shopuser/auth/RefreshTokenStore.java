package org.spring.shopuser.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Refresh Token 的单用户单会话存储：主键存原始 UUID，SHA256 反向索引允许仅凭 opaque Token 定位用户。
 * 不扫描 Key，不从客户端接收/信任 userId。登录替换、刷新轮换、登出均通过 Lua 原子比较修改。
 */
@Component
@ConditionalOnProperty(prefix = "shop.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RefreshTokenStore {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);
    // 登录 CAS：与读取到的旧值一致才同时替换主键/反向索引，防止并发登录留下多份有效刷新凭据。
    private static final RedisScript<Long> LOGIN = RedisScript.of("""
            if (redis.call('GET', KEYS[1]) or '') ~= ARGV[1] then return 0 end
            redis.call('PSETEX', KEYS[1], ARGV[4], ARGV[2])
            redis.call('PSETEX', KEYS[2], ARGV[4], ARGV[3])
            if ARGV[1] ~= '' then redis.call('DEL', KEYS[3]) end
            return 1
            """, Long.class);
    // 回源查用户前先校验两端都匹配；返回当前 TTL，不因刷新请求延长会话寿命。
    private static final RedisScript<Long> VALIDATE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] or redis.call('GET', KEYS[2]) ~= ARGV[2] then return -1 end
            return redis.call('PTTL', KEYS[1])
            """, Long.class);
    // 一次性轮换：并发刷新只有一个请求能消费旧 Token；新 UUID 继承剩余 TTL，登录后最长 7 天。
    private static final RedisScript<Long> ROTATE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] or redis.call('GET', KEYS[2]) ~= ARGV[3] then return -1 end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl <= 0 then return -1 end
            redis.call('PSETEX', KEYS[1], ttl, ARGV[2])
            redis.call('PSETEX', KEYS[3], ttl, ARGV[3])
            redis.call('DEL', KEYS[2])
            return ttl
            """, Long.class);
    // 旧 Token 的登出不能删掉新登录/新轮换的会话；重复登出无有效记录时仍可成功完成。
    private static final RedisScript<Long> REVOKE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] or redis.call('GET', KEYS[2]) ~= ARGV[2] then return 0 end
            return redis.call('DEL', KEYS[1], KEYS[2])
            """, Long.class);
    private final StringRedisTemplate redis;
    private final AuthProperties properties;

    /** 显式使用 authRedisResources 内部的 6380 模板，不注入默认 6381 权限模板。 */
    public RefreshTokenStore(AuthRedisConfiguration.Resources resources, AuthProperties properties) {
        this.redis = resources.template();
        this.properties = properties;
    }

    /** 登录成功才创建刷新凭据。Redis 保存失败不返回半份成功 Token。 */
    public void save(long userId, String token) {
        validateFormat(token);
        execute("login", () -> {
            for (int attempt = 0; attempt < 3; attempt++) {
                String old = redis.opsForValue().get(userKey(userId));
                if (old != null && !canonicalUuid(old)) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
                Long saved = redis.execute(LOGIN, List.of(userKey(userId), lookupKey(token), old == null ? lookupKey(token) : lookupKey(old)),
                        old == null ? "" : old, token, Long.toString(userId), Long.toString(properties.refreshTtl().toMillis()));
                if (Long.valueOf(1).equals(saved)) return true;
            }
            // 仅因 CAS 竞争有限重试；网络超时不重放写脚本，避免不确定成功后重复替换。
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        });
    }

    /** 接收仅有 UUID 的刷新请求，反向定位并校验原始值，过期/旧 Token 都返回固定 401。 */
    public long resolve(String token) {
        validateFormat(token);
        return execute("resolve", () -> {
            String value = redis.opsForValue().get(lookupKey(token));
            if (value == null) throw invalid();
            long userId = parseUserId(value);
            Long ttl = redis.execute(VALIDATE, List.of(userKey(userId), lookupKey(token)), token, value);
            if (ttl == null || ttl <= 0) throw invalid();
            return userId;
        });
    }

    /** 在用户状态/新 JWT 准备好后消费旧凭据；返回新 Refresh Token 的剩余有效毫秒数。 */
    public long rotate(long userId, String old, String replacement) {
        validateFormat(old); validateFormat(replacement);
        return execute("rotate", () -> {
            Long ttl = redis.execute(ROTATE, List.of(userKey(userId), lookupKey(old), lookupKey(replacement)),
                    old, replacement, Long.toString(userId));
            if (ttl == null || ttl <= 0) throw invalid();
            return ttl;
        });
    }

    /** 不依赖 Access Token，允许访问令牌过期后用 Refresh Token 登出；没有会话时幂等成功。 */
    public void revoke(String token) {
        validateFormat(token);
        execute("logout", () -> {
            String value = redis.opsForValue().get(lookupKey(token));
            if (value != null) redis.execute(REVOKE, List.of(userKey(parseUserId(value)), lookupKey(token)), token, value);
            return true;
        });
    }

    /** 默认严格符合要求 auth:refresh_token:{userId}；命名空间仅用于环境/测试隔离。 */
    public String userKey(long userId) { return properties.keyPrefix() + ":refresh_token:{" + userId + "}"; }
    /** 反向索引不把原始凭据放进 Key；主键 Value 按需求保存 UUID，两者拥有一致 TTL。 */
    public String lookupKey(String token) {
        try { return properties.keyPrefix() + ":refresh_lookup:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }
    /** 随机版本 4 UUID，严格使用完整小写规范形式，不接受短写或携带用户身份的自定义拼接串。 */
    public static String newToken() { return UUID.randomUUID().toString(); }
    private static boolean canonicalUuid(String token) { return token != null && token.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"); }
    private static void validateFormat(String token) { if (!canonicalUuid(token)) throw invalid(); }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN); }
    private static long parseUserId(String value) {
        try {
            if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException error) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
    /** Redis 故障是 503，不伪装成 Token 不存在；日志不输出 Token、Key 或底层错误 message。 */
    private <T> T execute(String operation, Supplier<T> action) {
        try { return action.get(); }
        catch (DataAccessException error) {
            log.warn("Auth token store unavailable; operation={}, type={}", operation, error.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }
}
