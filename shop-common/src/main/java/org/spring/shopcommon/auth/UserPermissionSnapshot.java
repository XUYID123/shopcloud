package org.spring.shopcommon.auth;

import java.util.Set;

/**
 * shop-user 向网关返回的内部权限契约，放在 common 以避免两个服务相互依赖实现模块。
 * @param userId 本次查询的数据库用户 ID，网关必须检查它与 JWT 用户一致
 * @param enabled 用户是否存在且处于启用状态；不存在/禁用返回 false
 * @param permissions 从有效角色/权限关系计算出的去重权限编码，不来自客户端或 JWT
 */
public record UserPermissionSnapshot(String userId, boolean enabled, Set<String> permissions) {
    public UserPermissionSnapshot {
        // 返回不可变集合；这里不携带密码、角色实体，也不让外部修改已创建快照。
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }
}
