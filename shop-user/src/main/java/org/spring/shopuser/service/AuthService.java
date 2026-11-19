package org.spring.shopuser.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopuser.auth.*;
import org.spring.shopuser.dto.UserLoginRequest;
import org.spring.shopuser.entity.UserEntity;
import org.spring.shopuser.mapper.UserMapper;
import org.spring.shopuser.vo.AuthTokensVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 登录/刷新用例：MyBatis-Plus 查询账号，BCrypt 检查凭据，本地 JWT 签发，安全 Redis 管理刷新资格。 */
@Service
@ConditionalOnProperty(prefix = "shop.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuthService {
    private final UserMapper users;
    private final PasswordEncoder passwords;
    private final JwtUtils jwt;
    private final RefreshTokenStore refresh;
    private final AuthProperties properties;
    private final String dummyHash;

    /** 无账号时也执行一次 BCrypt 比较，降低通过耗时直接枚举用户名的差异。 */
    public AuthService(UserMapper users, PasswordEncoder passwords, JwtUtils jwt, RefreshTokenStore refresh, AuthProperties properties) {
        this.users = users; this.passwords = passwords; this.jwt = jwt; this.refresh = refresh; this.properties = properties;
        dummyHash = passwords.encode(RefreshTokenStore.newToken());
    }

    /** 不把 BCrypt/Redis 操作放进数据库长事务；所有身份字段都来自数据库投影。 */
    @org.spring.shopcommon.logging.BizLog(action="AUTH_LOGIN",bizId="#result.userId")
    public AuthTokensVO login(UserLoginRequest request) {
        UserEntity user = query(() -> users.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .select(UserEntity::getId, UserEntity::getUsername, UserEntity::getPasswordHash, UserEntity::getStatus)
                .eq(UserEntity::getUsername, request.getUsername())));
        boolean valid = passwords.matches(request.getPassword(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !valid || !"ENABLED".equals(user.getStatus())) throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        String access = jwt.generateAccessToken(user.getId(), user.getUsername());
        String token = RefreshTokenStore.newToken();
        refresh.save(user.getId(), token);
        return tokens(user, access, token, properties.refreshTtl().toMillis());
    }

    /** 用户禁用/删除后不再允许刷新；并发旧令牌的校验与消费由 Redis 原子比较保证。 */
    @org.spring.shopcommon.logging.BizLog(action="AUTH_REFRESH",bizId="#result.userId")
    public AuthTokensVO refresh(String token) {
        long userId = refresh.resolve(token);
        UserEntity user = query(() -> users.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .select(UserEntity::getId, UserEntity::getUsername, UserEntity::getStatus).eq(UserEntity::getId, userId)));
        if (user == null || !"ENABLED".equals(user.getStatus())) {
            refresh.revoke(token);
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        String access = jwt.generateAccessToken(user.getId(), user.getUsername());
        String replacement = RefreshTokenStore.newToken();
        long remaining = refresh.rotate(userId, token, replacement);
        return tokens(user, access, replacement, remaining);
    }

    /** 撤销刷新资格，不查询/拉黑无状态 Access Token；已签发访问令牌按原 exp 失效。 */
    @org.spring.shopcommon.logging.BizLog(action="AUTH_LOGOUT")
    public void logout(String token) { refresh.revoke(token); }

    /** 不返回密码、实体或角色权限；刷新后保持原会话最长有效期，不无限延长 7 天窗口。 */
    private AuthTokensVO tokens(UserEntity user, String access, String token, long remainingMillis) {
        return new AuthTokensVO(access, token, "Bearer", properties.accessTtl().toSeconds(),
                Math.max(0, remainingMillis / 1000), user.getId().toString(), user.getUsername());
    }
    /** 数据库不可用是 503，不误判成密码不正确；日志不记录 SQL、用户名或输入密码。 */
    private UserEntity query(java.util.function.Supplier<UserEntity> action) {
        try { return action.get(); }
        catch (DataAccessException error) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,error);
        }
    }
}
