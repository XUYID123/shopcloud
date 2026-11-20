package org.spring.shopgateway.auth;

import io.jsonwebtoken.Jwts;
import org.spring.shopcommon.internalauth.InternalJwtProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;

/** 仅网关装配的内部 JWT 签发器，独立于 user 的 Access Token 私钥。 */
@Component
public class InternalTokenSigner {
    private final RSAPrivateKey key;
    private final long ttl;
    public InternalTokenSigner(ResourceLoader loader,
            @Value("${gateway.internal-jwt.private-key-location:file:../infra/local-secrets/gateway-internal-private.pem}") String location,
            @Value("${gateway.internal-jwt.ttl:45s}") Duration ttl) throws Exception {
        // 配置错误在启动阶段暴露；秒精度与 JWT NumericDate 一致，禁止配置成长效凭据。
        if (ttl.toSeconds() < 30 || ttl.toSeconds() > 60 || ttl.toMillis() % 1000 != 0) throw new IllegalArgumentException("Internal JWT TTL must be 30..60 seconds");
        this.ttl = ttl.toSeconds();
        // 私钥只读取一次并限制文件大小，请求阶段不访问磁盘；下游没有这个组件和私钥配置。
        byte[] bytes;
        try (var input = loader.getResource(location).getInputStream()) { bytes = input.readNBytes(16_385); }
        if (bytes.length > 16_384) throw new IllegalArgumentException("Internal private key too large");
        // 只支持 RSA PKCS8 私钥；与用户服务签发 Access Token 的另一对密钥分开管理。
        String pem = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII).replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        key = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        if (key.getModulus().bitLength() < 2048) throw new IllegalArgumentException("RSA2048+ required");
    }
    /** 每次生成独立 jti；method/path/query 绑定实际下游请求，aud 仅来自受控路由。 */
    public String sign(String userId, String kind, String digest, String audience, String method, String path, String query) {
        // 受众只能是受控服务名；摘要来自实际通过的 RBAC 快照，不能取客户端传来的权限头。
        if (digest == null || !digest.matches("[a-f0-9]{64}") || !audience.matches("shop-(user|product|inventory|cart|order|pay)")) throw new IllegalArgumentException("Invalid internal authorization snapshot");
        Instant now = Instant.now(); String subject = userId == null ? "anonymous" : userId;
        // typ/issuer 隔离内部与外部 JWT；UUID jti 每次更新，但没有消费记录时它不提供一次性防重放。
        // 附加最终方法、原始路径、查询摘要，防止凭据被挪用到同一服务的其他接口。
        return Jwts.builder().header().type(InternalJwtProtocol.TYPE).and().issuer(InternalJwtProtocol.ISSUER)
                .subject(subject).claim("userId", subject).claim("kind", kind).claim("permissionsDigest", digest)
                .audience().add(audience).and().issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(ttl)))
                .id(UUID.randomUUID().toString()).claim("method", method).claim("path", path)
                .claim("queryHash", InternalJwtProtocol.digest(query == null ? "" : query)).signWith(key, Jwts.SIG.RS256).compact();
    }
    /** 权限冷加载的最小权限引导凭据，只能查询该用户权限，不表示通过业务 RBAC。 */
    public String permissionQuery(String userId) {
        return sign(userId, "permission-query", InternalJwtProtocol.permissionsDigest(Set.of("rbac:read:self")),
                "shop-user", "GET", "/internal/v1/users/" + userId + "/permissions", null);
    }
}
