package org.spring.shopcommon.internalauth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

/** 服务身份与网关用户身份使用不同的密钥及 JWT 类型。凭据绑定到单一 HTTP 请求。 */
public final class ScopedServiceJwt {
    private static final String TYPE = "shop-service+jwt";

    private ScopedServiceJwt() { }

    public record Scope(String caller, String audience, String permission) { }

    public static RSAPrivateKey privateKey(Resource resource) throws Exception {
        var key = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(pem(resource, "PRIVATE KEY")));
        if (key.getModulus().bitLength() < 2048) throw new IllegalArgumentException("RSA2048+ required");
        return key;
    }

    public static RSAPublicKey publicKey(Resource resource) throws Exception {
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(pem(resource, "PUBLIC KEY")));
        if (key.getModulus().bitLength() < 2048) throw new IllegalArgumentException("RSA2048+ required");
        return key;
    }

    private static byte[] pem(Resource resource, String label) throws Exception {
        try (var input = resource.getInputStream()) {
            byte[] bytes = input.readNBytes(16_385);
            if (bytes.length > 16_384) throw new IllegalArgumentException("Service JWT key too large");
            String body = new String(bytes, StandardCharsets.US_ASCII)
                    .replace("-----BEGIN " + label + "-----", "").replace("-----END " + label + "-----", "")
                    .replaceAll("\\s", "");
            return Base64.getDecoder().decode(body);
        }
    }

    public static String sign(RSAPrivateKey key, Scope scope, String method, String path, Clock clock) {
        var now = clock.instant();
        return Jwts.builder().header().type(TYPE).and().issuer(scope.caller()).subject(scope.caller())
                .audience().add(scope.audience()).and().claim("scope", scope.permission())
                .claim("method", method).claim("path", path).id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(45)))
                .signWith(key, Jwts.SIG.RS256).compact();
    }

    /** 注册到接收服务的白名单时，只为一个方法和路径模式使用此验签器。 */
    public static final class Verifier {
        private final JwtParser parser;
        private final Scope scope;
        private final Clock clock;

        public Verifier(RSAPublicKey key, Scope scope, Clock clock) {
            this.scope = scope;
            this.clock = clock;
            parser = Jwts.parser().verifyWith(key).requireIssuer(scope.caller()).requireSubject(scope.caller())
                    .require("scope", scope.permission()).clock(() -> Date.from(clock.instant()))
                    .sig().clear().add(Jwts.SIG.RS256).and().build();
        }

        public InternalIdentity verify(String token, String method, String path, String query) {
            if (token == null || token.length() > 8192 || query != null) throw invalid();
            try {
                var signed = parser.parseSignedClaims(token);
                var claims = signed.getPayload();
                var issued = claims.getIssuedAt();
                var expires = claims.getExpiration();
                String jti = claims.getId();
                var now = clock.instant();
                if (!TYPE.equals(signed.getHeader().getType()) || !Set.of(scope.audience()).equals(claims.getAudience())
                        || issued == null || expires == null || issued.toInstant().isAfter(now)
                        || !expires.after(issued) || expires.toInstant().isAfter(issued.toInstant().plusSeconds(60))
                        || !expires.toInstant().isAfter(now) || jti == null
                        || !method.equals(claims.get("method", String.class)) || !path.equals(claims.get("path", String.class))) {
                    throw invalid();
                }
                return new InternalIdentity(null, "service", InternalJwtProtocol.digest(scope.permission()), jti);
            } catch (JwtException | IllegalArgumentException malformed) {
                throw invalid();
            }
        }

        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException("Invalid scoped service JWT");
        }
    }
}
