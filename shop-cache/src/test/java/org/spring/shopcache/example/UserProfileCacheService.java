package org.spring.shopcache.example;

import org.spring.shopcache.SafeCacheTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 编译验证的业务示例：只缓存公开 DTO；通过适配器绑定真实 Mapper，不新增 HTTP 或角色管理接口。 */
@Service
public class UserProfileCacheService {
    private final SafeCacheTemplate cache;
    private final ProfileQuery query;

    /** 构造器注入，不使用 static 工具类取 Bean。 */
    public UserProfileCacheService(SafeCacheTemplate cache, ProfileQuery query) { this.cache = cache; this.query = query; }

    /** Lambda 在线程池中调用查询代理；默认故障抛异常，调用方可按统一错误约定映射 503。 */
    public UserProfile find(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        return cache.get("user-profile:v1:" + userId, UserProfile.class, () -> query.find(userId));
    }

    /** 业务写事务成功提交后调用；正式写接口应通过 afterCommit/outbox 重试保障删除最终成功。 */
    public void evictAfterCommit(long userId) { cache.evict("user-profile:v1:" + userId); }

    /** 最小安全响应 DTO，禁止直接缓存包含 passwordHash 的 UserEntity。 */
    public record UserProfile(long id, String nickname) { }

    /** 查询单独作为 Spring Bean，在实际数据库工作线程中进入事务，避免调用线程事务失效。 */
    @Service
    public static class ProfileQuery {
        private final ProfileRepository repository;
        /** 业务服务提供 DAO 适配器，例如按主键查 UserMapper 后只投影 id/nickname。 */
        public ProfileQuery(ProfileRepository repository) { this.repository = repository; }
        /** timeout 为秒；数据库查不到返回 null，其余异常正常抛出。 */
        @Transactional(readOnly = true, timeout = 1)
        public UserProfile find(long userId) { return repository.find(userId); }
    }

    /** 示例边界，正式服务用自己的 Mapper/Repository 实现，不在公共缓存模块内依赖业务表。 */
    @FunctionalInterface
    public interface ProfileRepository { UserProfile find(long userId); }
}
