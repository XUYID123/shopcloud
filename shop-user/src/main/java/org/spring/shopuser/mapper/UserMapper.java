package org.spring.shopuser.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.spring.shopuser.entity.UserEntity;

/** MyBatis 账号访问接口，SQL 与 UserEntity 字段映射在 mapper/UserMapper.xml；不承担注册规则。 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    /** 查重/后续登录可用的账号查询；返回内部实体，不能直接作为 HTTP 响应。 */
    UserEntity findByUsername(@Param("username") String username);

    /** 按数据库主键查找账号；没有记录时返回 null。 */
    UserEntity findById(@Param("id") Long id);

    /** 插入账号，返回受影响行数；XML 开启生成主键回写，成功后 user.id 应有值。 */
    int insert(UserEntity user);
}
