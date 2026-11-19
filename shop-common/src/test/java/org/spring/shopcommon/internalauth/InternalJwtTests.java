package org.spring.shopcommon.internalauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.spring.shopcommon.trace.TraceServletFilter;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** 内部 JWT 信任边界，验证攻击输入而不是只测试签发后能解析。 */
class InternalJwtTests {
    private final InternalJwtVerifier verifier = new InternalJwtVerifier((RSAPublicKey)InternalTestTokens.key(false), "shop-product", Clock.systemUTC());
    @Test void verifiesIdentityAndRejectsWrongAudiencePathMethodAndQuery() {
        String token = InternalTestTokens.token("shop-product","42","user","POST","/api/v1/admin/categories");
        assertThat(verifier.verify(token,"POST","/api/v1/admin/categories",null).userId()).isEqualTo("42");
        assertThatThrownBy(() -> verifier.verify(token,"GET","/api/v1/admin/categories",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verifier.verify(token,"POST","/api/v1/admin/products",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verifier.verify(token,"POST","/api/v1/admin/categories","aud=shop-pay")).isInstanceOf(IllegalArgumentException.class);
        String wrong = InternalTestTokens.token("shop-pay","42","user","POST","/api/v1/admin/categories");
        assertThatThrownBy(() -> verifier.verify(wrong,"POST","/api/v1/admin/categories",null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsExpiredFutureAndExcessiveLifetime() {
        String expired = InternalTestTokens.token("shop-product","42","user","POST","/api/v1/admin/categories",Instant.now().minusSeconds(90));
        String future = InternalTestTokens.token("shop-product","42","user","POST","/api/v1/admin/categories",Instant.now().plusSeconds(90));
        for(String token:List.of(expired,future,changed(Map.of("exp",Date.from(Instant.now().plusSeconds(120)))))) {
            assertThatThrownBy(() -> verifier.verify(token,"POST","/api/v1/admin/categories",null)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void rejectsStaticTokenMissingClaimsAccessJwtAndInvalidSignature() throws Exception {
        String wrongType = Jwts.builder().subject("42").expiration(Date.from(Instant.now().plusSeconds(45)))
                .signWith((PrivateKey)InternalTestTokens.key(true),Jwts.SIG.RS256).compact();
        String valid = InternalTestTokens.token("shop-product","42","user","POST","/api/v1/admin/categories");
        String[] pieces=valid.split("\\."); pieces[2]=(pieces[2].startsWith("a")?"b":"a")+pieces[2].substring(1);
        for(String token:List.of("shop_internal_local_dev_change_me",wrongType,String.join(".",pieces),changed(Map.of("permissionsDigest","bad")),changed(Map.of("userId","99")))) {
            assertThatThrownBy(() -> verifier.verify(token,"POST","/api/v1/admin/categories",null)).isInstanceOf(IllegalArgumentException.class);
        }
        var hmac=Jwts.SIG.HS256.key().build();
        String hs=Jwts.builder().subject("42").signWith(hmac).compact();
        assertThatThrownBy(() -> verifier.verify(hs,"POST","/api/v1/admin/categories",null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void filterRejectsMissingAndMultipleTokensWithTraceAndNeverInvokesBusiness() throws Exception {
        var called=new AtomicInteger();
        for(int scenario=0;scenario<2;scenario++) {
            var req=request("POST","/api/v1/admin/categories");
            if(scenario==1) {req.addHeader("X-Internal-Token","a");req.addHeader("X-Internal-Token","b");}
            var response=new MockHttpServletResponse();
            new TraceServletFilter().doFilter(req,response,(r,s)->filter().doFilter(r,s,(a,b)->called.incrementAndGet()));
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("INVALID_INTERNAL_TOKEN",response.getHeader("X-Trace-Id"));
        }
        assertThat(called.get()).isZero();
    }
    @Test void filterRebuildsIdentityFromJwtAndRemovesForgedRolesAndPermissions() throws Exception {
        var req=request("POST","/api/v1/admin/categories");
        req.addHeader("X-Internal-Token",InternalTestTokens.token("shop-product","42","user","POST",req.getRequestURI()));
        req.addHeader("X-User-Id","999");req.addHeader("X-User-Roles","ADMIN");req.addHeader("X-User-Permissions","*");
        filter().doFilter(req,new MockHttpServletResponse(),(r,s)->{
            var received=(jakarta.servlet.http.HttpServletRequest)r;
            assertThat(received.getHeader("X-User-Id")).isEqualTo("42");
            assertThat(received.getUserPrincipal().getName()).isEqualTo("42");
            assertThat(received.getHeader("X-User-Roles")).isNull();assertThat(received.getHeader("X-User-Permissions")).isNull();
        });
    }
    @Test void anonymousCredentialsOnlyAccessExplicitPublicEndpointWithoutUserId() throws Exception {
        var props=new InternalAuthProperties("unused",List.of(new InternalAuthProperties.Endpoint(Set.of("POST"),List.of("/login"))));
        var filter=new InternalAuthenticationFilter(verifier,new ObjectMapper(),props,"shop-product");
        for(String path:List.of("/login","/admin")) {
            var req=request("POST",path);req.addHeader("X-Internal-Token",InternalTestTokens.token("shop-product",null,"anonymous","POST",path));req.addHeader("X-User-Id","999");
            var response=new MockHttpServletResponse();var count=new AtomicInteger();
            filter.doFilter(req,response,(r,s)->{count.incrementAndGet();assertThat(((jakarta.servlet.http.HttpServletRequest)r).getHeader("X-User-Id")).isNull();});
            assertThat(count.get()).isEqualTo(path.equals("/login")?1:0);
            if(!path.equals("/login"))assertThat(response.getStatus()).isEqualTo(403);
        }
    }
    @Test void permissionQueryCannotAccessBusinessOrOtherUserInternalEndpoint() throws Exception {
        var verifyUser=new InternalJwtVerifier((RSAPublicKey)InternalTestTokens.key(false),"shop-user",Clock.systemUTC());
        var filter=new InternalAuthenticationFilter(verifyUser,new ObjectMapper(),new InternalAuthProperties("unused",List.of()),"shop-user");
        for(String path:List.of("/api/v1/users","/internal/v1/users/99/permissions","/internal/v1/users/42/permissions")) {
            var req=request("GET",path);req.addHeader("X-Internal-Token",InternalTestTokens.token("shop-user","42","permission-query","GET",path));
            var response=new MockHttpServletResponse();var count=new AtomicInteger();filter.doFilter(req,response,(r,s)->count.incrementAndGet());
            assertThat(count.get()).isEqualTo(path.endsWith("/42/permissions")?1:0);
            if(count.get()==0)assertThat(response.getStatus()).isEqualTo(403);
        }
    }
    @Test void forwardCannotChangeAuthorizedPathAndErrorDispatchRetainsVerifiedIdentity() throws Exception {
        var req=request("POST","/api/v1/admin/categories");
        req.addHeader("X-Internal-Token",InternalTestTokens.token("shop-product","42","user","POST",req.getRequestURI()));
        req.addHeader("X-User-Id","999");
        filter().doFilter(req,new MockHttpServletResponse(),(r,s)->{});
        req.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);req.setRequestURI("/error");
        filter().doFilter(req,new MockHttpServletResponse(),(r,s)->assertThat(((jakarta.servlet.http.HttpServletRequest)r).getHeader("X-User-Id")).isEqualTo("42"));
        req.setDispatcherType(jakarta.servlet.DispatcherType.FORWARD);req.setRequestURI("/api/v1/admin/other");
        var rejected=new MockHttpServletResponse();filter().doFilter(req,rejected,(r,s)->fail("forward must not bypass binding"));
        assertThat(rejected.getStatus()).isEqualTo(401);
    }
    private InternalAuthenticationFilter filter(){return new InternalAuthenticationFilter(verifier,new ObjectMapper(),new InternalAuthProperties("unused",List.of()),"shop-product");}
    private MockHttpServletRequest request(String method,String path){var r=new MockHttpServletRequest(method,path);r.setServletPath(path);return r;}
    private String changed(Map<String,Object> overrides) {
        String original=InternalTestTokens.token("shop-product","42","user","POST","/api/v1/admin/categories");
        var claims=new HashMap<String,Object>(Jwts.parser().verifyWith((PublicKey)InternalTestTokens.key(false)).build().parseSignedClaims(original).getPayload());claims.putAll(overrides);
        return Jwts.builder().header().type(InternalJwtProtocol.TYPE).and().claims(claims).signWith((PrivateKey)InternalTestTokens.key(true),Jwts.SIG.RS256).compact();
    }
}
