package org.spring.shopuser;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;

/** 集成测试独立生成临时 RSA 密钥，不依赖真实开发私钥；进程退出自动清理临时文件。 */
public abstract class AuthIntegrationSupport {
    public static final String AUTH_PREFIX = "test:auth:" + UUID.randomUUID().toString().replace("-", "");
    private static final Path PRIVATE_KEY;
    private static final Path PUBLIC_KEY;
    public static final java.security.KeyPair KEY_PAIR;
    static {
        try {
            var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            var keys = generator.generateKeyPair(); KEY_PAIR = keys;
            var directory = Files.createTempDirectory("shop-auth-test-"); directory.toFile().deleteOnExit();
            PRIVATE_KEY = write(directory, "private.pem", "PRIVATE KEY", keys.getPrivate().getEncoded());
            PUBLIC_KEY = write(directory, "public.pem", "PUBLIC KEY", keys.getPublic().getEncoded());
        } catch (Exception error) { throw new IllegalStateException("Cannot create isolated test keys", error); }
    }
    @DynamicPropertySource static void testAuth(DynamicPropertyRegistry registry) {
        registry.add("shop.auth.private-key-location", () -> PRIVATE_KEY.toUri().toString());
        registry.add("shop.auth.public-key-location", () -> PUBLIC_KEY.toUri().toString());
        registry.add("shop.auth.key-prefix", () -> AUTH_PREFIX);
        registry.add("shop.auth.clock-skew", () -> "0s");
    }
    private static Path write(Path directory, String name, String label, byte[] bytes) throws Exception {
        Path path = directory.resolve(name);
        Files.writeString(path, "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + label + "-----\n", StandardCharsets.US_ASCII);
        path.toFile().deleteOnExit(); return path;
    }
}
