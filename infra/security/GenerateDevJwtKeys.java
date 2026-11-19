import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPairGenerator;
import java.util.Base64;

/** 从项目根目录运行：java infra/security/GenerateDevJwtKeys.java。仅生成本地开发密钥。 */
class GenerateDevJwtKeys {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of("infra", "local-secrets").toAbsolutePath().normalize();
        Path publicFile = directory.resolve("jwt-public.pem");
        Path privateFile = directory.resolve("jwt-private.pem");
        if (Files.exists(publicFile) || Files.exists(privateFile)) {
            if (Files.exists(publicFile) && Files.exists(privateFile)) {
                System.out.println("Development key pair already exists; files preserved.");
                return;
            }
            throw new IllegalStateException("Incomplete existing key pair; refusing to overwrite");
        }
        Files.createDirectories(directory);
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        var pair = generator.generateKeyPair();
        Files.writeString(privateFile, pem("PRIVATE KEY", pair.getPrivate().getEncoded()), StandardOpenOption.CREATE_NEW);
        Files.writeString(publicFile, pem("PUBLIC KEY", pair.getPublic().getEncoded()), StandardOpenOption.CREATE_NEW);
        System.out.println("Development RSA-3072 key pair created in ignored infra/local-secrets; gateway loads only public key.");
    }
    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64,
                "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded) + "\n-----END " + type + "-----\n";
    }
}
