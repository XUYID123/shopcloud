package org.spring.shopgateway;

import org.junit.jupiter.api.Test;
import org.spring.shopgateway.config.ProtectionProperties;
import org.spring.shopgateway.web.ClientIpResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientIpResolverTests {
    @Test
    void directClientsCannotOverrideTheirAddressWithHeaders() {
        var resolver = resolver(List.of());
        assertThat(resolver.resolve(request("198.51.100.1", "203.0.113.5"))).isEqualTo("198.51.100.1");
    }

    @Test
    void proxyChainsAreWalkedFromTheTrustedPeerToTheFirstUntrustedHop() {
        var resolver = resolver(List.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request("10.0.0.1", "198.51.100.99, 192.0.2.8, 10.0.0.2")))
                .isEqualTo("192.0.2.8");
    }

    @Test
    void malformedProxyHeadersFallBackToTheConnectionPeer() {
        var resolver = resolver(List.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request("10.0.0.1", "untrusted-host.invalid"))).isEqualTo("10.0.0.1");
    }

    @Test
    void ipv6ClientsAreCanonicalized() {
        var resolver = resolver(List.of("::1/128"));
        assertThat(resolver.resolve(request("::1", "2001:db8::12"))).isEqualTo("2001:db8:0:0:0:0:0:12");
    }

    @Test
    void proxyConfigurationRejectsHostnames() {
        assertThatThrownBy(() -> resolver(List.of("proxy.invalid/24"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void excessiveLengthOrHopCountFallsBackToTheTrustedConnectionPeer() {
        var resolver = resolver(List.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request("10.0.0.1", "1".repeat(2049)))).isEqualTo("10.0.0.1");
        assertThat(resolver.resolve(request("10.0.0.1", String.join(",", Collections.nCopies(21, "10.0.0.2")))))
                .isEqualTo("10.0.0.1");
        assertThat(resolver.resolve(request("10.0.0.1", "192.0.2.8,"))).isEqualTo("10.0.0.1");
    }

    @Test
    void multipleHeaderValuesAreTreatedAsOneProxyChain() {
        var request = MockServerHttpRequest.get("/").remoteAddress(new InetSocketAddress("10.0.0.1", 1234))
                .header("X-Forwarded-For", "203.0.113.99, 192.0.2.8", "10.0.0.2").build();
        assertThat(resolver(List.of("10.0.0.0/8")).resolve(request)).isEqualTo("192.0.2.8");
    }

    @Test
    void missingConnectionAddressDoesNotFallBackToSpoofableHeaders() {
        var request = MockServerHttpRequest.get("/").header("X-Forwarded-For", "203.0.113.99")
                .header("X-Real-IP", "203.0.113.98").header("Forwarded", "for=203.0.113.97").build();
        assertThat(resolver(List.of("10.0.0.0/8")).resolve(request)).isEqualTo("unknown");
    }

    private ClientIpResolver resolver(List<String> trusted) {
        return new ClientIpResolver(new ProtectionProperties(200, null, trusted));
    }

    private MockServerHttpRequest request(String peer, String forwarded) {
        return MockServerHttpRequest.get("/").remoteAddress(new InetSocketAddress(peer, 1234))
                .header("X-Forwarded-For", forwarded).build();
    }
}
