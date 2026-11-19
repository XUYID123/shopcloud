package org.spring.shopuser.vo;

import java.util.List;

/**
 * 注册接口的安全输出契约，与数据库 UserEntity 分离。
 * @param userId 字符串形式主键，避免前端大整数精度丢失
 * @param username 用户名，不包含密码或密码哈希
 * @param roles 当前注册分配的角色名称；这不是让前端自行声明网关权限的凭据
 */
public record UserVO(String userId, String username, List<String> roles) {
}
