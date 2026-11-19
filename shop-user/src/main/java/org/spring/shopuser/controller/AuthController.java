package org.spring.shopuser.controller;

import jakarta.validation.Valid;
import org.spring.shopcommon.result.Result;
import org.spring.shopuser.dto.RefreshTokenRequest;
import org.spring.shopuser.dto.UserLoginRequest;
import org.spring.shopuser.service.AuthService;
import org.spring.shopuser.vo.AuthTokensVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 双 Token HTTP 入口，按照本次约定使用 /api/auth/login、refresh、logout；不在控制器查库或验密码。 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(prefix = "shop.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    /** 成功必须同时完成 JWT 签发与 Refresh Token 保存，凭据响应禁止中间代理缓存。 */
    @PostMapping("/login")
    public ResponseEntity<Result<AuthTokensVO>> login(@Valid @RequestBody UserLoginRequest request) { return noCache(auth.login(request)); }
    /** 不要求有效 Access Token；只凭有效且未消费的 Refresh Token 完成原子轮换。 */
    @PostMapping("/refresh")
    public ResponseEntity<Result<AuthTokensVO>> refresh(@Valid @RequestBody RefreshTokenRequest request) { return noCache(auth.refresh(request.getRefreshToken())); }
    /** 幂等登出；旧刷新凭据不能删除另一份新登录会话。 */
    @PostMapping("/logout")
    public ResponseEntity<Result<Void>> logout(@Valid @RequestBody RefreshTokenRequest request) { auth.logout(request.getRefreshToken()); return noCache(null); }
    /** 避免双 Token、用户信息被浏览器/反向代理存储或共享。 */
    private static <T> ResponseEntity<Result<T>> noCache(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(Result.success(value));
    }
}
