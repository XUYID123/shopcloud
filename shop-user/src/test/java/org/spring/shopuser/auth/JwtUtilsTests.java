package org.spring.shopuser.auth;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.Date;
import static org.assertj.core.api.Assertions.*;

/** 验证算法/声明/时间边界与错误码，不访问 Redis，不等待真实时钟。 */
class JwtUtilsTests {
    private static final KeyPair KEYS = keys();
    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
    private final JwtUtils jwt = jwt(KEYS, NOW);

    @Test void signedPayloadHasIdentityAndThirtyMinuteExpiryWithoutPermissions() {
        String token = jwt.generateAccessToken(42, "flour_user");
        var parsed = Jwts.parser().verifyWith(KEYS.getPublic()).clock(() -> Date.from(NOW)).build().parseSignedClaims(token);
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("RS256");
        assertThat(parsed.getPayload().getSubject()).isEqualTo("42");
        assertThat(parsed.getPayload().get("userId")).isEqualTo("42");
        assertThat(parsed.getPayload().get("username")).isEqualTo("flour_user");
        assertThat(parsed.getPayload().getExpiration().toInstant()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(parsed.getPayload()).doesNotContainKeys("roles", "permissions", "authorities");
        assertThat(jwt.parseAccessToken(token)).isEqualTo(new JwtUtils.Identity(42, "flour_user"));
    }
    @Test void expiryHasSpecific401ButWrongSignatureAndAlgorithmAreInvalidToken() {
        String token = jwt.generateAccessToken(42, "flour_user");
        assertCode(() -> jwt(KEYS, NOW.plusSeconds(1801)).parseAccessToken(token), ErrorCode.ACCESS_TOKEN_EXPIRED);
        assertCode(() -> jwt(keys(), NOW).parseAccessToken(token), ErrorCode.INVALID_ACCESS_TOKEN);
        String hmac = Jwts.builder().subject("42").signWith(Jwts.SIG.HS256.key().build()).compact();
        assertCode(() -> jwt.parseAccessToken(hmac), ErrorCode.INVALID_ACCESS_TOKEN);
        assertCode(() -> jwt.parseAccessToken("bad.token"), ErrorCode.INVALID_ACCESS_TOKEN);
    }
    @Test void wrongAudienceMismatchedIdentityAndPrivilegeClaimsAreRejected() {
        for (String variation : new String[]{"audience", "identity", "privileges"}) {
            var builder = Jwts.builder().issuer("flour-shop-user").subject("42").issuedAt(Date.from(NOW))
                    .expiration(Date.from(NOW.plusSeconds(1000))).claim("userId", variation.equals("identity") ? "99" : "42")
                    .claim("username", "flour_user").audience().add(variation.equals("audience") ? "other" : "flour-shop-api").and();
            if (variation.equals("privileges")) builder.claim("roles", java.util.List.of("ADMIN"));
            String token = builder.signWith(KEYS.getPrivate(), Jwts.SIG.RS256).compact();
            assertCode(() -> jwt.parseAccessToken(token), ErrorCode.INVALID_ACCESS_TOKEN);
        }
    }
    private static JwtUtils jwt(KeyPair keys, Instant time) {
        return new JwtUtils((RSAPrivateKey) keys.getPrivate(), (RSAPublicKey) keys.getPublic(), properties(), Clock.fixed(time, ZoneOffset.UTC));
    }
    public static AuthProperties properties() {
        return new AuthProperties(true, "unused", "unused", "flour-shop-user", "flour-shop-api", Duration.ofMinutes(30),
                Duration.ofDays(7), Duration.ZERO, 8192, "test:jwt", new AuthProperties.SecurityRedis("127.0.0.1", 6380, "", "test", 0, Duration.ofSeconds(1), false));
    }
    private static KeyPair keys() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo(401); assertThat(error.getBusinessCode()).isEqualTo(code.name());
        });
    }
}
