package org.spring.shopgateway.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.spring.shopgateway.auth.application.GatewayAccessService;
import org.spring.shopgateway.web.GatewayResponses;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthenticationAuthorizationFilterTests {
    @Test
    void downstreamExceptionIsNotConvertedIntoAuthenticationOrRbacFailure() {
        var jwt = mock(JwtIdentityVerifier.class);
        var permissions = mock(RbacPermissionCache.class);
        var filter = new AuthenticationAuthorizationFilter(new GatewayAccessService(jwt, permissions, JwtIdentityVerifierTests.properties()));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders")
                .header("Authorization", "Bearer aaa.bbb.ccc")
                .header("X-User-Id", "999").header("X-User-Roles", "ADMIN")
                .header("X-User-Permissions", "*"));
        when(jwt.userId("aaa.bbb.ccc")).thenReturn(Mono.just("42"));
        when(permissions.authorizeWithSnapshot(eq("42"), eq("GET"), any(PathContainer.class), isNull(), isNull()))
                .thenReturn(Mono.just(new org.spring.shopgateway.auth.port.PermissionAuthorizer.Decision(true, org.spring.shopcommon.internalauth.InternalJwtProtocol.permissionsDigest(java.util.Set.of("test")))));
        var downstreamFailure = new IllegalStateException("simulated downstream failure");

        assertThatThrownBy(() -> filter.filter(exchange, next -> {
            assertThat(next.getRequest().getHeaders().get("X-User-Id")).containsExactly("42");
            assertThat(next.getRequest().getHeaders().containsHeader("X-User-Roles")).isFalse();
            assertThat(next.getRequest().getHeaders().containsHeader("X-User-Permissions")).isFalse();
            return Mono.error(downstreamFailure);
        }).block(Duration.ofSeconds(3))).isSameAs(downstreamFailure);

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        verify(permissions).authorizeWithSnapshot(eq("42"), eq("GET"), any(PathContainer.class), isNull(), isNull());
        verifyNoMoreInteractions(permissions);
    }
}
