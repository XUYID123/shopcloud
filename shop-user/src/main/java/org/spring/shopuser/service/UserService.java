package org.spring.shopuser.service;

import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopuser.dto.UserRegisterRequest;
import org.spring.shopuser.entity.UserEntity;
import org.spring.shopuser.mapper.RoleMapper;
import org.spring.shopuser.mapper.UserMapper;
import org.spring.shopuser.vo.UserVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/**
 * 用户注册用例，由 UserController 调用：校验用户名、编码密码、创建账号、分配默认角色并登记缓存失效。
 * 数据库写入使用一个事务；成功后只返回 UserVO，不把包含密码哈希的实体返回给前端。
 * 本类不接受客户端指定角色，DEFAULT_ROLE 固定为 USER；管理员角色需要后续受控管理业务赋予。
 */
@Service
public class UserService {
    private static final String DEFAULT_ROLE = "USER"; // 公开注册的固定初始角色。
    private final UserMapper userMapper; // 账号查找和插入，数据库唯一约束兜底并发重名。
    private final RoleMapper roleMapper; // 读取有效默认角色并建立 user_role 关系。
    private final PasswordEncoder passwordEncoder; // 配置类提供 BCrypt，不存明文密码。
    private final TransactionTemplate transactionTemplate; // 精确控制只把数据库部分放入事务。
    private final PermissionCacheInvalidator permissionInvalidator; // 同事务登记 Redis 权限失效任务。

    /** 注入基础组件并创建事务模板；构造时不注册用户，也不访问数据库。 */
    public UserService(UserMapper userMapper, RoleMapper roleMapper, PasswordEncoder passwordEncoder,
                       PlatformTransactionManager transactionManager, PermissionCacheInvalidator permissionInvalidator) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.permissionInvalidator = permissionInvalidator;
    }

    /** 输入格式已由 Controller 的 @Valid 校验，这里继续执行依赖数据库的注册规则。 */
    @org.spring.shopcommon.logging.BizLog(action="USER_REGISTER",bizId="#result.userId")
    public UserVO register(UserRegisterRequest request) {
        // 预查询让普通重名请求尽早返回 409；它不能替代插入时的数据库唯一约束。
        if (userMapper.findByUsername(request.getUsername()) != null) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }

        // BCrypt 在事务外计算，避免占用数据库连接等待密码哈希。
        String passwordHash = passwordEncoder.encode(request.getPassword());
        // 默认角色、账号、关联和 outbox 必须一起提交；回调抛异常会回滚本次数据库写入。
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            // FOR SHARE 持有默认角色行的共享锁，角色必须存在且启用后才能继续注册。
            Long roleId = roleMapper.findEnabledRoleIdForShare(DEFAULT_ROLE);
            if (roleId == null) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            }

            // 内部实体只填服务端允许的初始字段；不从请求复制角色、权限或账号状态。
            UserEntity user = new UserEntity();
            user.setUsername(request.getUsername());
            user.setPasswordHash(passwordHash);
            user.setStatus("ENABLED");
            user.setTokenVersion(0L);
            // MyBatis 使用数据库生成的主键回写 user.id；没有成功插入或没有主键都不能继续建关联。
            try {
                if (userMapper.insert(user) != 1 || user.getId() == null) {
                    throw new IllegalStateException("User insert did not return a generated ID");
                }
            } catch (DuplicateKeyException exception) {
                // 预查询不能防止并发重名；以数据库唯一约束的结果为准。
                throw new BusinessException(ErrorCode.USERNAME_EXISTS);
            }

            // 建立用户与默认角色的关系，失败时账号插入也必须回滚，不能留下半个注册结果。
            if (roleMapper.insertUserRole(user.getId(), roleId) != 1) {
                throw new IllegalStateException("Default role assignment failed");
            }
            // 在同一个事务登记失效任务；真正删除 Redis 发生在提交之后，失败由持久化任务重试。
            permissionInvalidator.enqueueUser(user.getId());
            // ID 转字符串避免前端大整数精度问题；返回安全 VO，不包含 passwordHash。
            return new UserVO(user.getId().toString(), user.getUsername(), List.of(DEFAULT_ROLE));
        }));
    }
}
