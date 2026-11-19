package org.spring.shopuser.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * user_account 表的内部数据库映射，由 UserMapper 使用，不直接作为接口响应。
 * 只保存账号字段，用户角色和权限通过关联表查询；@JsonIgnore 对密码哈希再做一层防泄露。
 */
@Getter
@Setter
@TableName("user_account")
public class UserEntity {
    @TableId(type = IdType.AUTO)
    private Long id; // MySQL 生成的用户主键，插入后由 MyBatis 回写。
    private String username; // 登录标识，受数据库唯一约束保护。
    // BCrypt 哈希，不是明文；即使实体被意外序列化，也不输出此字段。
    @JsonIgnore
    private String passwordHash;
    private String nickname; // 可选显示名称，与登录标识分离。
    private String status; // ENABLED / DISABLED，有效权限计算会检查此状态。
    private Long tokenVersion; // 预留的令牌版本字段；当前网关没有实现基于它的 Token 撤销校验。
    private LocalDateTime createdAt; // 数据库写入的创建时间。
    private LocalDateTime updatedAt; // 数据库维护的最后修改时间。
}
