package org.spring.shopcommon.internalauth;
import io.jsonwebtoken.Jwts;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.time.*;
import java.util.*;
/** 仅测试源码，读取明确的公开测试夹具，不进入任何生产 JAR。 */
public final class InternalTestTokens {
    public static final String PUBLIC = "file:../infra/security/test-fixtures/internal-test-public.pem";
    public static final String PRIVATE = "file:../infra/security/test-fixtures/internal-test-private.pem";
    public static Key key(boolean privateKey) {
        try {
            String label=privateKey?"PRIVATE":"PUBLIC";
            String pem=Files.readString(Path.of("../infra/security/test-fixtures/internal-test-"+(privateKey?"private":"public")+".pem"))
                .replace("-----BEGIN "+label+" KEY-----", "").replace("-----END "+label+" KEY-----", "").replaceAll("\\s", "");
            var bytes=Base64.getDecoder().decode(pem);
            return privateKey?KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes)):
                KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
        } catch(Exception e) {throw new IllegalStateException(e);}
    }
    public static String token(String aud,String uid,String kind,String method,String path) {return token(aud,uid,kind,method,path,Instant.now());}
    public static String token(String aud,String uid,String kind,String method,String path,Instant now) {
        return tokenForQuery(aud,uid,kind,method,path,"",now);
    }
    public static String tokenForQuery(String aud,String uid,String kind,String method,String path,String query,Instant now) {
        String subject=uid==null?"anonymous":uid;
        return Jwts.builder().header().type(InternalJwtProtocol.TYPE).and().issuer(InternalJwtProtocol.ISSUER)
            .subject(subject).claim("userId",subject).claim("kind",kind).claim("permissionsDigest",InternalJwtProtocol.permissionsDigest(Set.of("test")))
            .audience().add(aud).and().issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(45))).id(UUID.randomUUID().toString())
            .claim("method",method).claim("path",path).claim("queryHash",InternalJwtProtocol.digest(query))
            .signWith((java.security.PrivateKey)key(true),Jwts.SIG.RS256).compact();
    }
}
