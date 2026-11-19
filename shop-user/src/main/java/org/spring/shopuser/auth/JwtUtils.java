package org.spring.shopuser.auth;

import io.jsonwebtoken.*;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JJWT 访问令牌工具：只做本地 RS256 签发/验签，不访问 Redis 或数据库。
 * 公共 Claims 保留 sub=userId 以兼容现有网关，并包含明确的 userId/username；不携带角色权限。
 */
public class JwtUtils {
    private final RSAPrivateKey privateKey;
    private final AuthProperties properties;
    private final Clock clock;
    private final JwtParser parser;

    /** 固定 RSA2048+ 密钥与算法，不信任 Token 里的算法或外部远程密钥位置。 */
    public JwtUtils(RSAPrivateKey privateKey, RSAPublicKey publicKey, AuthProperties properties, Clock clock) {
        if (publicKey.getModulus().bitLength() < 2048 || !privateKey.getModulus().equals(publicKey.getModulus())) {
            throw new IllegalArgumentException("Matching RSA keys of at least 2048 bits are required");
        }
        this.privateKey = privateKey;
        this.properties = properties;
        this.clock = clock;
        parser = Jwts.parser().verifyWith(publicKey).requireIssuer(properties.issuer())
                .clock(() -> Date.from(clock.instant())).clockSkewSeconds(properties.clockSkew().toSeconds())
                .sig().clear().add(Jwts.SIG.RS256).and().build();
    }

    /** 仅使用已查库验证的账号签发；用户名采用数据库规范值，不接受客户端指定身份。 */
    public String generateAccessToken(long userId, String username) {
        if (userId <= 0 || username == null || !username.matches("[A-Za-z0-9_]{3,32}")) {
            throw new IllegalArgumentException("Invalid verified identity");
        }
        Instant now = clock.instant();
        return Jwts.builder().issuer(properties.issuer()).subject(Long.toString(userId))
                .audience().add(properties.audience()).and().id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(properties.accessTtl())))
                .claim("userId", Long.toString(userId)).claim("username", username)
                .signWith(privateKey, Jwts.SIG.RS256).compact();
    }

    /** 验证签名/时间后再读取身份；过期有独立 401 业务码，其他坏 Token 使用通用 401。 */
    public Identity parseAccessToken(String token) {
        try {
            if (token == null || token.isBlank() || token.length() > properties.maxTokenLength()) throw invalid();
            Claims claims = parser.parseSignedClaims(token).getPayload();
            String subject = claims.getSubject();
            String userId = claims.get("userId", String.class);
            String username = claims.get("username", String.class);
            if (subject == null || !subject.matches("[1-9][0-9]{0,18}") || !subject.equals(userId)
                    || username == null || !username.matches("[A-Za-z0-9_]{3,32}") || claims.getExpiration() == null
                    || claims.getIssuedAt() == null || claims.getAudience() == null || !claims.getAudience().contains(properties.audience())
                    || claims.containsKey("roles") || claims.containsKey("permissions") || claims.containsKey("authorities")) throw invalid();
            return new Identity(Long.parseLong(subject), username);
        } catch (ExpiredJwtException error) {
            // 不向客户端暴露异常携带的 Claims 或令牌原文。
            throw new BusinessException(ErrorCode.ACCESS_TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException error) {
            throw invalid();
        }
    }

    /** 固定错误消息，禁止把底层签名/解析异常中的敏感细节输出到响应。 */
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_ACCESS_TOKEN); }
    /** Servlet 属性中的可信身份，只由本工具验证成功后创建。 */
    public record Identity(long userId, String username) { }
}
