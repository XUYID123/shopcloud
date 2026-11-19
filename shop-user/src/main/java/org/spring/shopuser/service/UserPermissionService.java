package org.spring.shopuser.service;

import org.spring.shopcommon.auth.UserPermissionSnapshot;
import org.spring.shopuser.mapper.PermissionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;

/**
 * 用户服务的有效权限查询用例，由 InternalPermissionController 响应网关 Feign 调用时使用。
 * 只查 MySQL 并返回安全快照；用户缓存的加锁和回填由网关处理，本类不引入第二层缓存。
 * 缺失/禁用用户返回明确的无权状态，数据库故障保持异常，不能假装成合法空权限。
 */
@Service
public class UserPermissionService {
    private final PermissionMapper mapper; // 只投影账号状态与权限编码，不读取密码或完整实体。
    /** 由 Spring 注入 MyBatis 代理，SQL 定义在 mapper/PermissionMapper.xml。 */
    public UserPermissionService(PermissionMapper mapper) { this.mapper = mapper; }

    /**
     * 两次查询处于同一个可重复读事务，避免用户状态和权限来自不同的已提交快照。
     * readOnly 表达只读意图，不表示所有 MySQL 查询都完全不需要数据库资源或没有并发窗口。
     */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public UserPermissionSnapshot load(long userId) {
        // 只有查询结果明确为 ENABLED 才启用；不存在时返回 null，也会产生 enabled=false。
        boolean enabled = "ENABLED".equals(mapper.findUserStatus(userId));
        // 禁用/不存在账号不再联表查询；启用账号查询有效角色和有效权限，SQL DISTINCT 去重。
        return new UserPermissionSnapshot(Long.toString(userId), enabled,
                enabled ? mapper.findEffectivePermissions(userId) : Set.of());
    }
}
