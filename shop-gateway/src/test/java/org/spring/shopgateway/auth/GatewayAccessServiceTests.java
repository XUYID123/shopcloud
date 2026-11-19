package org.spring.shopgateway.auth;

import org.junit.jupiter.api.Test;
import org.spring.shopgateway.auth.application.GatewayAccessService;
import org.spring.shopgateway.auth.port.PermissionAuthorizer;
import org.spring.shopgateway.error.GatewayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.security.oauth2.jwt.BadJwtException;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.concurrent.TimeoutException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 不依赖 exchange/Redis 的认证用例测试，保证 HTTP 适配与权限基础设施分离。 */
class GatewayAccessServiceTests {
    private final JwtIdentityVerifier jwt = mock(JwtIdentityVerifier.class);
    private final PermissionAuthorizer permissions = mock(PermissionAuthorizer.class);
    private final GatewayAccessService service = new GatewayAccessService(jwt, permissions, JwtIdentityVerifierTests.properties());

    @Test void anonymousWhitelistNeverInvokesTokenVerificationOrPermissionLookup() {
        var properties = mock(GatewayAuthProperties.class);
        when(properties.whitelist()).thenReturn(List.of(new RbacModels.Endpoint(java.util.Set.of("POST"), List.of("/api/v1/users"))));
        var whitelistService = new GatewayAccessService(jwt, permissions, properties);
        var request = new GatewayAccessService.Request("POST", PathContainer.parsePath("/api/v1/users"), "shop-user-api", "id", null);
        assertThat(whitelistService.authorize(request).block().userId()).isNull();
        verifyNoInteractions(jwt, permissions);
    }

    @Test void missingTokenAndInvalidSignatureAre401BeforePermissions() {
        assertStatus(service.authorize(request(null)), HttpStatus.UNAUTHORIZED);
        when(jwt.userId("aaa.bbb.ccc")).thenReturn(Mono.error(new BadJwtException("bad signature")));
        assertStatus(service.authorize(request(List.of("Bearer aaa.bbb.ccc"))), HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(permissions);
    }

    @Test void deniedAndUnavailableAreDistinctAndProgrammingBugIsNotMisclassified() {
        when(jwt.userId("aaa.bbb.ccc")).thenReturn(Mono.just("42"));
        when(permissions.authorizeWithSnapshot(eq("42"), anyString(), any(), anyString(), eq("request-id"))).thenReturn(Mono.just(new org.spring.shopgateway.auth.port.PermissionAuthorizer.Decision(false, null)));
        assertStatus(service.authorize(request(List.of("Bearer aaa.bbb.ccc"))), HttpStatus.FORBIDDEN);
        when(permissions.authorizeWithSnapshot(anyString(), anyString(), any(), anyString(), anyString())).thenReturn(Mono.error(new TimeoutException()));
        assertStatus(service.authorize(request(List.of("Bearer aaa.bbb.ccc"))), HttpStatus.SERVICE_UNAVAILABLE);
        var bug = new NullPointerException("simulated coding bug");
        when(permissions.authorizeWithSnapshot(anyString(), anyString(), any(), anyString(), anyString())).thenReturn(Mono.error(bug));
        assertThatThrownBy(() -> service.authorize(request(List.of("Bearer aaa.bbb.ccc"))).block()).isSameAs(bug);
    }

    @Test void emptyPermissionDecisionCannotSilentlyReturnSuccessfulEmptyResponse() {
        when(jwt.userId("aaa.bbb.ccc")).thenReturn(Mono.just("42"));
        when(permissions.authorizeWithSnapshot(anyString(), anyString(), any(), anyString(), anyString())).thenReturn(Mono.empty());
        assertStatus(service.authorize(request(List.of("Bearer aaa.bbb.ccc"))), HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test void signedButExpiredJwtHasSpecificRefreshSignal() {
        when(jwt.userId("aaa.bbb.ccc")).thenReturn(Mono.error(new org.springframework.security.oauth2.jwt.JwtValidationException("expired",
                List.of(new org.springframework.security.oauth2.core.OAuth2Error("access_token_expired")))));
        assertThatThrownBy(() -> service.authorize(request(List.of("Bearer aaa.bbb.ccc"))).block())
                .isInstanceOfSatisfying(GatewayException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(error.code()).isEqualTo("ACCESS_TOKEN_EXPIRED");
                });
        verifyNoInteractions(permissions);
    }

    private GatewayAccessService.Request request(List<String> authorization) {
        return new GatewayAccessService.Request("GET", PathContainer.parsePath("/api/v1/orders"), "shop-order-api", "request-id", authorization);
    }
    private void assertStatus(Mono<?> result, HttpStatus status) {
        assertThatThrownBy(result::block).isInstanceOfSatisfying(GatewayException.class, error -> assertThat(error.status()).isEqualTo(status));
    }
}
