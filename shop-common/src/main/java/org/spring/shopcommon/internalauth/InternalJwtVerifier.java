package org.spring.shopcommon.internalauth;

import io.jsonwebtoken.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Set;

/** 固定公钥/RS256/issuer/type 的本地验证器；不查 Redis，不接受令牌提供的远程密钥 URL。 */
public final class InternalJwtVerifier {
    private final JwtParser parser;
    private final String audience;
    private final Clock clock;
    public InternalJwtVerifier(RSAPublicKey key, String audience, Clock clock) {
        if (key.getModulus().bitLength() < 2048 || audience == null || audience.isBlank()) throw new IllegalArgumentException("Invalid internal JWT configuration");
        this.audience = audience; this.clock = clock;
        // 信任的算法和公钥由服务配置固定，禁止根据 Token 声明改用 HMAC 或远程下载密钥。
        parser = Jwts.parser().verifyWith(key).requireIssuer(InternalJwtProtocol.ISSUER)
                .clock(() -> Date.from(clock.instant())).sig().clear().add(Jwts.SIG.RS256).and().build();
    }
    public InternalIdentity verify(String token, String method, String rawPath, String rawQuery) {
        // 先限制输入体积；parseSignedClaims 同时执行密码学验签及标准时间声明校验。
        if (token == null || token.length() > 8192) throw invalid();
        try {
            var signed = parser.parseSignedClaims(token);
            var claims = signed.getPayload();
            Instant now = clock.instant();
            String userId = claims.get("userId", String.class), kind = claims.get("kind", String.class);
            String digest = claims.get("permissionsDigest", String.class), jti = claims.getId();
            Date issued = claims.getIssuedAt(), expires = claims.getExpiration();
            // 固定用途、唯一受众、短有效期、jti 和权限摘要；再校验签发时绑定的 HTTP 请求。
            // 这里的 audience 是自己的 spring.application.name，其他服务的有效 JWT 也不能通用。
            if (!InternalJwtProtocol.TYPE.equals(signed.getHeader().getType())
                    || !Set.of(audience).equals(claims.getAudience())
                    || issued == null || expires == null || issued.toInstant().isAfter(now)
                    || !expires.after(issued) || expires.getTime() - issued.getTime() > 60_000
                    || !expires.toInstant().isAfter(now) || jti == null || !jti.matches("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
                    || digest == null || !digest.matches("[a-f0-9]{64}")
                    || !method.equals(claims.get("method", String.class)) || !rawPath.equals(claims.get("path", String.class))
                    || !InternalJwtProtocol.digest(rawQuery == null ? "" : rawQuery).equals(claims.get("queryHash", String.class))
                    || userId == null || !userId.equals(claims.getSubject())
                    || !Set.of("user", "anonymous", "permission-query").contains(kind)) throw invalid();
            // 匿名 JWT 的签名主体仍必填，但不把字符串 anonymous 当作下游用户 ID。
            if ("anonymous".equals(kind)) {
                if (!"anonymous".equals(userId)) throw invalid();
                userId = null;
            } else {
                if (!userId.matches("[1-9][0-9]{0,18}") || Long.parseLong(userId) <= 0) throw invalid();
            }
            // 只有全部验证完成后，才构造可供过滤器信任的身份对象。
            return new InternalIdentity(userId, kind, digest, jti);
        // 对外只报告无效凭据，不回显 JWT、签名内容和底层解析细节。
        } catch (JwtException | IllegalArgumentException | NullPointerException error) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid internal JWT"); }
}
