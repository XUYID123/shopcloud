package org.spring.shopcommon.internalauth;

import org.junit.jupiter.api.Test;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ScopedServiceJwtTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC);
    private static final String PATH = "/internal/v1/products/skus/123/stock-metadata";
    private static final ScopedServiceJwt.Scope SCOPE = new ScopedServiceJwt.Scope(
            "shop-inventory", "shop-product", "product:stock-metadata:read");

    @Test void queryBindingIsExplicitAndUsesTheRawQuery() throws Exception {
        var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var signer = generator.generateKeyPair();
        var key = (RSAPrivateKey) signer.getPrivate();
        var publicKey = (RSAPublicKey) signer.getPublic();
        var bound = ScopedServiceJwt.Verifier.forQueryBoundEndpoint(publicKey, SCOPE, CLOCK);
        var queryless = new ScopedServiceJwt.Verifier(publicKey, SCOPE, CLOCK);
        String query = "userId=12&reference=a%20b";
        String queryToken = ScopedServiceJwt.signWithQuery(key, SCOPE, "GET", PATH, query, CLOCK);
        String querylessToken = ScopedServiceJwt.sign(key, SCOPE, "GET", PATH, CLOCK);

        assertThat(bound.verify(queryToken, "GET", PATH, query).kind()).isEqualTo("service");
        for (String changed : List.of("userId=13&reference=a%20b", "reference=a%20b&userId=12", "userId=12&reference=a+b")) {
            assertThatIllegalArgumentException().isThrownBy(() -> bound.verify(queryToken, "GET", PATH, changed));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> bound.verify(queryToken, "GET", PATH, null));
        assertThatIllegalArgumentException().isThrownBy(() -> bound.verify(querylessToken, "GET", PATH, null));
        assertThatIllegalArgumentException().isThrownBy(() -> queryless.verify(queryToken, "GET", PATH, query));
        String emptyQueryToken = ScopedServiceJwt.signWithQuery(key, SCOPE, "GET", PATH, null, CLOCK);
        assertThat(bound.verify(emptyQueryToken, "GET", PATH, null).kind()).isEqualTo("service");
        assertThatIllegalArgumentException().isThrownBy(() -> queryless.verify(emptyQueryToken, "GET", PATH, null));
    }

    @Test void isolatesServiceCredentialsByKeyPurposeAudiencePathMethodAndTime() throws Exception {
        var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var signer = generator.generateKeyPair();
        var other = generator.generateKeyPair();
        var verifier = new ScopedServiceJwt.Verifier((RSAPublicKey) signer.getPublic(), SCOPE, CLOCK);
        var key = (RSAPrivateKey) signer.getPrivate();
        String valid = ScopedServiceJwt.sign(key, SCOPE, "GET", PATH, CLOCK);
        var identity = verifier.verify(valid, "GET", PATH, null);
        assertThat(identity.kind()).isEqualTo("service");
        assertThat(identity.userId()).isNull();

        var invalid = List.of(
                ScopedServiceJwt.sign((RSAPrivateKey) other.getPrivate(), SCOPE, "GET", PATH, CLOCK),
                ScopedServiceJwt.sign(key, new ScopedServiceJwt.Scope("shop-order", SCOPE.audience(), SCOPE.permission()), "GET", PATH, CLOCK),
                ScopedServiceJwt.sign(key, new ScopedServiceJwt.Scope(SCOPE.caller(), "shop-pay", SCOPE.permission()), "GET", PATH, CLOCK),
                ScopedServiceJwt.sign(key, new ScopedServiceJwt.Scope(SCOPE.caller(), SCOPE.audience(), "product:write"), "GET", PATH, CLOCK),
                ScopedServiceJwt.sign(key, SCOPE, "GET", PATH, Clock.offset(CLOCK, Duration.ofSeconds(-46))),
                ScopedServiceJwt.sign(key, SCOPE, "GET", PATH, Clock.offset(CLOCK, Duration.ofSeconds(1))));
        for (String token : invalid) {
            assertThatIllegalArgumentException().isThrownBy(() -> verifier.verify(token, "GET", PATH, null));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> verifier.verify(valid, "POST", PATH, null));
        assertThatIllegalArgumentException().isThrownBy(() -> verifier.verify(valid, "GET", PATH.replace("123", "124"), null));
        assertThatIllegalArgumentException().isThrownBy(() -> verifier.verify(valid, "GET", PATH, "version=1"));
        var gatewayVerifier = new InternalJwtVerifier((RSAPublicKey) signer.getPublic(), SCOPE.audience(), CLOCK);
        assertThatIllegalArgumentException().isThrownBy(() -> gatewayVerifier.verify(valid, "GET", PATH, null));
    }
}
