package org.spring.shopgateway.auth;

import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * JWT 验证适配器：由认证应用用例调用，复用配置类创建的 ReactiveJwtDecoder。
 * 只输出通过签名及声明校验的 sub 用户 ID，不从 JWT 获取角色/权限，不访问 Redis/MySQL。
 * 本类的线程切换用于隔离 RSA 验签计算；阻塞 Feign 使用的是另一套线程池。
 */
public class JwtIdentityVerifier {
    private final ReactiveJwtDecoder decoder; // 已绑定固定公钥、RS256 和时间/签发方/受众校验。
    private final Scheduler scheduler; // jwtVerificationScheduler，专用于验签任务。

    /** 构造只保留可复用组件，启动时的公钥文件读取已由配置类完成。 */
    public JwtIdentityVerifier(ReactiveJwtDecoder decoder, Scheduler scheduler) {
        this.decoder = decoder;
        this.scheduler = scheduler;
    }

    /** Token 无效时保留 JwtException，让调用方转换为 401；验证成功才允许提取用户身份。 */
    public Mono<String> userId(String token) {
        // defer 延迟验证；subscribeOn 切换执行线程；map 把验证后的 Jwt 转成 sub 字符串。
        return Mono.defer(() -> decoder.decode(token)).subscribeOn(scheduler).map(jwt -> jwt.getSubject());
    }
}
