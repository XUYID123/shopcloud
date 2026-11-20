import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;

/** 生成内部签名密钥。默认使用网关前缀；独立服务身份可指定不同前缀。 */
class GenerateInternalJwtKeys {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length == 0 ? "infra/local-secrets" : args[0]);
        String prefix = args.length > 1 ? args[1] : "gateway-internal";
        Path privateFile = directory.resolve(prefix + "-private.pem"), publicFile = directory.resolve(prefix + "-public.pem");
        if (Files.exists(privateFile) || Files.exists(publicFile)) {
            if (Files.exists(privateFile) && Files.exists(publicFile)) { System.out.println("Internal key pair preserved"); return; }
            throw new IllegalStateException("Incomplete key pair; refusing overwrite");
        }
        Files.createDirectories(directory);
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(3072); var keys = generator.generateKeyPair();
        write(privateFile, "PRIVATE KEY", keys.getPrivate().getEncoded());
        write(publicFile, "PUBLIC KEY", keys.getPublic().getEncoded());
        System.out.println("Internal key pair generated; deploy private key only to the signing service");
    }
    private static void write(Path file, String label, byte[] bytes) throws Exception {
        Files.writeString(file, "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + label + "-----\n", StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
    }
}
