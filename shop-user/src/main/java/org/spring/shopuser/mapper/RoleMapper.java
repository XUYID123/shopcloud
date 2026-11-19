package org.spring.shopuser.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 注册期间的角色关系访问接口；权限展开查询由独立 PermissionMapper 负责。 */
@Mapper
public interface RoleMapper {
    /** 读取启用角色并持有行共享锁，调用方必须在注册事务内使用。 */
    Long findEnabledRoleIdForShare(@Param("roleCode") String roleCode);

    /** 写入 user_role 关联；返回行数供 Service 确认账号与默认角色关系已完整建立。 */
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
