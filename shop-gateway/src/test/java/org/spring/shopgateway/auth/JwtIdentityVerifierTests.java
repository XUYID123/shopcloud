package org.spring.shopgateway.auth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.oauth2.jwt.JwtException;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class JwtIdentityVerifierTests {
    private static final Scheduler WORKERS = Schedulers.newBoundedElastic(2, 100, "jwt-test");
    private final JwtIdentityVerifier verifier = verifier();

    @AfterAll static void close() { WORKERS.dispose(); }

    @Test void acceptsSignedIdentityOnlyToken() {
        assertThat(verifier.userId(AuthTestSupport.token("42")).block(Duration.ofSeconds(3))).isEqualTo("42");
    }

    @Test void validlySignedExpiredTokenHasStructuredExpiryMarker() {
        String expired = token("42", AuthTestSupport.ISSUER, AuthTestSupport.AUDIENCE, Instant.now().minusSeconds(60), Map.of());
        assertThatThrownBy(() -> verifier.userId(expired).block(Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(org.springframework.security.oauth2.jwt.JwtValidationException.class, error ->
                        assertThat(error.getErrors()).anyMatch(item -> "access_token_expired".equals(item.getErrorCode())));
    }

    @Test void rejectsExpiredForgedAndWrongIssuerOrAudienceTokens() {
        List<String> invalid = List.of(
                token("42", AuthTestSupport.ISSUER, AuthTestSupport.AUDIENCE, Instant.now().minusSeconds(60), Map.of()),
                token("42", "another-issuer", AuthTestSupport.AUDIENCE, Instant.now().plusSeconds(60), Map.of()),
                token("42", AuthTestSupport.ISSUER, "another-api", Instant.now().plusSeconds(60), Map.of()),
                AuthTestSupport.token("42", AuthTestSupport.ISSUER, AuthTestSupport.AUDIENCE,
                        Instant.now().plusSeconds(60), Map.of(), AuthTestSupport.keys()));
        invalid.forEach(value -> assertThatThrownBy(() -> verifier.userId(value).block(Duration.ofSeconds(3)))
                .isInstanceOf(JwtException.class));
    }

    @Test void requiresExpiryAndValidSubjectAndRejectsEmbeddedPrivileges() {
        List<String> invalid = List.of(
                token("42", AuthTestSupport.ISSUER, AuthTestSupport.AUDIENCE, null, Map.of()),
                token("0", AuthTestSupport.ISSUER, AuthTestSupport.AUDIENCE, Instant.now().plusSeconds(60), Map.of()),
                token("9223372036854775808", AuthTestSupport.ISSUER, AuthTestSupport.AUDIENCE, Instant.now().plusSeconds(60), Map.of()),
                token("42", AuthTestSupport.ISSUER, AuthTestSupport.AUDIENCE, Instant.now().plusSeconds(60), Map.of("roles", List.of("ADMIN"))),
                token("42", AuthTestSupport.ISSUER, AuthTestSupport.AUDIENCE, Instant.now().plusSeconds(60), Map.of("permissions", List.of("product:write"))));
        invalid.forEach(value -> assertThatThrownBy(() -> verifier.userId(value).block(Duration.ofSeconds(3)))
                .isInstanceOf(JwtException.class));
    }

    private static String token(String user, String issuer, String audience, Instant expiry, Map<String, Object> extra) {
        return AuthTestSupport.token(user, issuer, audience, expiry, extra, AuthTestSupport.KEYS);
    }

    private static JwtIdentityVerifier verifier() {
        try {
            return new GatewayAuthConfiguration().jwtIdentityVerifier(properties(), new DefaultResourceLoader(), WORKERS);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    static GatewayAuthProperties properties() {
        return new GatewayAuthProperties(true, AuthTestSupport.PUBLIC_KEY, AuthTestSupport.ISSUER, AuthTestSupport.AUDIENCE,
                Duration.ZERO, 8192, "test:rbac", Duration.ofSeconds(2), Duration.ofSeconds(1),
                Duration.ofSeconds(4), "", 4, List.of());
    }
}
