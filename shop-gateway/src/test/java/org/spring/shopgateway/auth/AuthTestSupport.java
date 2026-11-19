package org.spring.shopgateway.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.*;

/** 测试专用临时密钥，绝不使用本地开发或生产私钥。 */
public final class AuthTestSupport {
    public static final KeyPair KEYS = keys();
    public static final String PUBLIC_KEY = publicKeyFile();
    public static final String ISSUER = "test-user-service";
    public static final String AUDIENCE = "test-shop-api";

    private AuthTestSupport() { }

    public static KeyPair keys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static String publicKeyFile() {
        try {
            var path = Files.createTempFile("gateway-auth-test-public-", ".pem");
            path.toFile().deleteOnExit();
            Files.writeString(path, "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'})
                    .encodeToString(KEYS.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
            return path.toUri().toString();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    public static String token(String userId) {
        return token(userId, ISSUER, AUDIENCE, Instant.now().plusSeconds(300), Map.of(), KEYS);
    }

    public static String token(String userId, String issuer, String audience, Instant expires,
                               Map<String, Object> extra, KeyPair pair) {
        Instant now = Instant.now();
        // 过期样本仍必须满足 iat < exp；否则 Spring 会先判为坏声明，测试不到“已过期”分类。
        var builder = new JWTClaimsSet.Builder().subject(userId).issuer(issuer).audience(audience)
                .issueTime(Date.from(expires != null && expires.isBefore(now) ? expires.minusSeconds(300) : now));
        if (expires != null) builder.expirationTime(Date.from(expires));
        extra.forEach(builder::claim);
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
        try { jwt.sign(new RSASSASigner((RSAPrivateKey) pair.getPrivate())); }
        catch (JOSEException exception) { throw new IllegalStateException(exception); }
        return jwt.serialize();
    }
}
