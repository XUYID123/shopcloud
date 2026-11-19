package org.spring.shopuser.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spring.shopcommon.auth.PermissionCacheKeys;
import org.spring.shopuser.config.UserPermissionProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.UUID;

/**
 * 数据库权限变更与 Redis 缓存失效之间的可靠衔接组件，由注册/未来的 RBAC 写服务调用。
 * <p>先在业务事务中写 outbox，事务成功后尝试删 Redis；失败由后台重试，不能回滚已提交的业务。
 * outbox 是 MySQL 中的失效任务，不是消息订阅，不需要 Caffeine 或 Pub/Sub。
 * <p>本类不修改用户角色权限，也不公开管理接口；调用方必须在同一事务内完成实际业务写入。
 * DependsOnDatabaseInitialization 保证新表先由 Flyway 创建，定时任务才会使用它。
 */
@Service
@DependsOnDatabaseInitialization
public class PermissionCacheInvalidator {
    private static final Logger log = LoggerFactory.getLogger(PermissionCacheInvalidator.class);
    // 与网关共用的失效脚本：原子更换 epoch 并删除权限，防止旧的 Feign 查询在删除后回填。
    private static final RedisScript<Long> INVALIDATE = RedisScript.of(
            new ClassPathResource("redis/rbac-user-invalidate.lua"), Long.class);
    private final JdbcTemplate jdbc; // 只操作失效任务及受影响用户查询，与业务共享 MySQL 事务。
    private final StringRedisTemplate redis; // MVC/后台线程使用同步客户端，仅连接 6381 业务实例。
    private final UserPermissionProperties properties; // Key 命名空间必须与网关一致。

    /** Spring 注入数据库、业务 Redis 和命名配置；不在构造函数中发起失效操作。 */
    public PermissionCacheInvalidator(JdbcTemplate jdbc, StringRedisTemplate redis, UserPermissionProperties properties) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 登记单个用户的失效任务，必须在外部已经开启的数据库事务中调用。
     * MANDATORY 防止调用者忘记事务，出现“业务改了、任务却没有一起提交”的断裂。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueUser(long userId) {
        // 同一用户/命名空间只保留一条最新任务。event_id 每次重建，供 worker 防止误确认新任务。
        // AS incoming 是 MySQL 新行别名；合并后重置重试次数和时间，让新变更尽快处理。
        jdbc.update("""
                INSERT INTO permission_cache_invalidation(user_id,key_prefix,event_id)
                VALUES (?,?,?) AS incoming
                ON DUPLICATE KEY UPDATE event_id=incoming.event_id,attempts=0,
                    next_attempt_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3)
                """, userId, properties.keyPrefix(), UUID.randomUUID().toString());
        // 只在真正提交后触发 Redis 操作；数据库回滚时 afterCommit 不执行，缓存不会被提前删除。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { flushUser(userId); }
        });
    }

    /**
     * 角色权限变化会影响所有关联用户，因此逐个登记用户任务，不能只删除所谓“角色缓存”。
     * 如果调用方要删除 user_role 关系，应先记录受影响用户，或直接调用 enqueueUser。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueRole(long roleId) {
        jdbc.queryForList("SELECT user_id FROM user_role WHERE role_id=?", Long.class, roleId).forEach(this::enqueueUser);
    }

    /**
     * 提交后先尝试删缓存；此处不再次申请数据库连接，避免并发提交时耗尽连接池。
     * 暂不确认 outbox，后续 worker 会幂等地再次失效并确认，代价是可能多一次冷加载。
     */
    private void flushUser(long userId) {
        try {
            evict(userId);
        } catch (RuntimeException error) {
            // 原业务已提交，不能返回虚假的注册失败；outbox 仍可由 worker 重试。
            log.warn("Permission invalidation deferred; type={}", error.getClass().getSimpleName());
        }
    }

    /**
     * 后台补偿入口：一次执行结束后再等待 1000ms，并非严格按自然秒触发。
     * 请求进程在提交后退出、Redis 临时故障等情况，由已持久化的任务继续补偿。
     */
    @Scheduled(fixedDelay = 1000)
    public void retryPending() {
        try {
            // 只读取本 key_prefix 的到期任务，每批最多 50 条，避免一次把整个任务表载入内存。
            var pending = jdbc.query("""
                    SELECT user_id,event_id,attempts FROM permission_cache_invalidation
                    WHERE key_prefix=? AND next_attempt_at <= CURRENT_TIMESTAMP(3) ORDER BY next_attempt_at LIMIT 50
                    """, (rs, row) -> new Event(rs.getLong(1), rs.getString(2), rs.getInt(3)), properties.keyPrefix());
            // 单条失败保留任务并退避；查询等外层失败则交给下次调度，不把任务默认为完成。
            pending.forEach(this::invalidate);
        } catch (RuntimeException error) {
            log.warn("Permission invalidation worker unavailable; type={}", error.getClass().getSimpleName());
        }
    }

    /** 完成一条持久化任务：Redis 失效成功后才尝试删除对应数据库任务。 */
    private void invalidate(Event event) {
        try {
            // worker 幂等地再执行一次失效，再按 event_id 确认；不能误删并发产生的新事件。
            evict(event.userId());
            jdbc.update("DELETE FROM permission_cache_invalidation WHERE user_id=? AND key_prefix=? AND event_id=?",
                    event.userId(), properties.keyPrefix(), event.id());
        } catch (RuntimeException error) {
            // 退避大致为 1、2、4、8、16、32、60 秒，之后保持 60 秒；不在任务线程里 sleep。
            int delay = Math.min(60, 1 << Math.min(6, event.attempts()));
            // 只更新当前 event_id；若期间有更新版本任务写入，不能推迟那条新任务的执行。
            jdbc.update("""
                    UPDATE permission_cache_invalidation SET attempts=attempts+1,
                        next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3))
                    WHERE user_id=? AND key_prefix=? AND event_id=?
                    """, delay, event.userId(), properties.keyPrefix(), event.id());
            log.warn("Permission invalidation retained for retry; type={}", error.getClass().getSimpleName());
        }
    }

    /** Redis 失效的最小执行单元；同步调用只发生在用户服务 MVC/调度线程，不能照搬到网关 I/O 线程。 */
    private void evict(long userId) {
        String id = Long.toString(userId);
        // KEYS：epoch、用户文档；ARGV：新的随机 epoch。即使原用户 Key 已不存在，也要更换代号。
        Long deleted = redis.execute(INVALIDATE, List.of(PermissionCacheKeys.epoch(properties.keyPrefix(), id),
                PermissionCacheKeys.user(properties.keyPrefix(), id)), UUID.randomUUID().toString());
        // 返回 0 表示 Key 原本不存在，也是成功失效；null 没有明确执行结果，不能确认任务完成。
        if (deleted == null) throw new IllegalStateException("Missing permission invalidation result");
    }

    /** 一次查询得到的任务快照：id 是 event_id，attempts 用于计算退避，不是用户权限版本。 */
    private record Event(long userId, String id, int attempts) { }
}
