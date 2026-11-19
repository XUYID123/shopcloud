package org.spring.shopgateway.auth;

import org.junit.jupiter.api.Test;
import org.spring.shopcommon.internalauth.*;
import org.springframework.core.io.DefaultResourceLoader;
import java.time.*;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

/** 签名私钥仅在网关测试进程；下游用同一夹具的公钥验证每请求唯一令牌。 */
class InternalTokenSignerTests {
    @Test void generatesDifferentJtiAndBindsAudienceAndActualPermissionsDigest() throws Exception {
        var signer=new InternalTokenSigner(new DefaultResourceLoader(),InternalTestTokens.PRIVATE,Duration.ofSeconds(45));
        var verifier=new InternalJwtVerifier((java.security.interfaces.RSAPublicKey)InternalTestTokens.key(false),"shop-product",Clock.systemUTC());
        String digest=InternalJwtProtocol.permissionsDigest(Set.of("product:write"));
        String first=signer.sign("42","user",digest,"shop-product","POST","/api/v1/admin/categories",null);
        String second=signer.sign("42","user",digest,"shop-product","POST","/api/v1/admin/categories",null);
        var a=verifier.verify(first,"POST","/api/v1/admin/categories",null);var b=verifier.verify(second,"POST","/api/v1/admin/categories",null);
        assertThat(a.jti()).isNotEqualTo(b.jti());assertThat(a.permissionsDigest()).isEqualTo(digest);
        assertThat(a.userId()).isEqualTo("42");
        assertThatThrownBy(()->new InternalTokenSigner(new DefaultResourceLoader(),InternalTestTokens.PRIVATE,Duration.ofSeconds(61))).isInstanceOf(IllegalArgumentException.class);
    }
}
