package org.spring.shopuser.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Set;

/**
 * 有效权限的数据库访问接口，由 MyBatis 生成实现，供 UserPermissionService 使用。
 * SQL 位于 PermissionMapper.xml，@Param 名称与 XML 中的占位符必须一致。
 */
@Mapper
public interface PermissionMapper {
    /** 仅查账号状态；没有用户时返回 null，不额外读取密码哈希。 */
    String findUserStatus(@Param("userId") long userId);
    /** 联表过滤有效用户/角色/权限，返回去重编码；没有有效关系时返回空集合。 */
    Set<String> findEffectivePermissions(@Param("userId") long userId);
}
