import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/** 仅用于本地手工联调：java infra/security/IssueDevJwt.java 42。不是登录接口。 */
class IssueDevJwt {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].matches("[1-9][0-9]{0,18}") || Long.parseLong(args[0]) <= 0) {
            throw new IllegalArgumentException("Provide one positive numeric user id");
        }
        String pem = Files.readString(Path.of("infra/local-secrets/jwt-private.pem"));
        byte[] encoded = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", ""));
        var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        long now = Instant.now().getEpochSecond();
        String header = base64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = base64("{\"sub\":\"" + args[0] + "\",\"iss\":\"flour-shop-user\","
                + "\"aud\":\"flour-shop-api\",\"iat\":" + now + ",\"exp\":" + (now + 900) + "}");
        String content = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(content.getBytes(StandardCharsets.US_ASCII));
        System.out.println(content + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
    }
    private static String base64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
