package org.spring.shopuser.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;

/** 登录模块装配；私钥只在 user 服务启动时读取，网关只持有对应公钥。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "shop.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {
    /** 用可注入 Clock 支持可重复时间边界测试，不在测试中 sleep 等待 Token 过期。 */
    @Bean Clock authClock() { return Clock.systemUTC(); }

    /** 读取 PKCS#8 私钥/X.509 公钥，启动时验证密钥对；不记录密钥文本。 */
    @Bean JwtUtils jwtUtils(AuthProperties properties, ResourceLoader resources, Clock authClock) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("RSA");
        var privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(
                pem(resources, properties.privateKeyLocation(), "PRIVATE KEY")));
        var publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(
                pem(resources, properties.publicKeyLocation(), "PUBLIC KEY")));
        return new JwtUtils(privateKey, publicKey, properties, authClock);
    }

    /** 有限读取，避免把任意大资源当密钥加载；不支持 PKCS#1 RSA PRIVATE KEY 格式。 */
    private static byte[] pem(ResourceLoader resources, String location, String label) throws Exception {
        try (var input = resources.getResource(location).getInputStream()) {
            byte[] bytes = input.readNBytes(16385);
            if (bytes.length > 16384) throw new IllegalArgumentException("RSA key resource too large");
            String pem = new String(bytes, StandardCharsets.US_ASCII);
            if (!pem.contains("-----BEGIN " + label + "-----")) throw new IllegalArgumentException("Unsupported RSA PEM format");
            return Base64.getDecoder().decode(pem.replace("-----BEGIN " + label + "-----", "")
                    .replace("-----END " + label + "-----", "").replaceAll("\\s", ""));
        }
    }
}
