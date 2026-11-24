package org.spring.shopuser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spring.shopcommon.internalauth.InternalJwtProtocol;
import org.spring.shopcommon.internalauth.InternalTestTokens;
import org.spring.shopcommon.internalauth.ScopedServiceJwt;
import org.spring.shopuser.mapper.AddressMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.URI;
import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shop.user.order-address-lookup.enabled=true")
class OrderAddressIntegrationTests extends AuthIntegrationSupport {
    private static final String PATH = "/internal/v1/users/addresses/";
    private static final ScopedServiceJwt.Scope SCOPE =
            new ScopedServiceJwt.Scope("shop-order", "shop-user", "order:address:read");
    private static final String RECEIVER = "订单测试收件人";
    private static final String PHONE = "13800000000";
    private static final String DETAIL = "订单测试路1号";
    private static RSAPrivateKey orderKey;

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private AddressMapper mapper;
    private long owner;
    private long address;
    private String path;
    private String query;

    @DynamicPropertySource
    static void orderSigningKey(DynamicPropertyRegistry registry) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        orderKey = (RSAPrivateKey) keys.getPrivate();
        var publicFile = Files.createTempFile("shop-order-address-test-", ".pem");
        publicFile.toFile().deleteOnExit();
        Files.writeString(publicFile, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keys.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n");
        registry.add("shop.user.order-address-lookup.public-key-location", () -> publicFile.toUri().toString());
    }

    @BeforeEach void prepareAddress() {
        String username = "order_addr_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO user_account (username, password_hash, status) VALUES (?, 'test-fixture', 'ENABLED')", username);
        owner = jdbc.queryForObject("SELECT id FROM user_account WHERE username = ?", Long.class, username);
        jdbc.update("""
                INSERT INTO user_address (user_id, receiver, phone, region_code, detail, is_default)
                VALUES (?, ?, ?, '310115', ?, TRUE)
                """, owner, RECEIVER, PHONE, DETAIL);
        address = jdbc.queryForObject("SELECT id FROM user_address WHERE user_id = ?", Long.class, owner);
        path = PATH + address;
        query = "userId=" + owner;
    }

    @AfterEach void removeAddress() {
        jdbc.update("DELETE FROM user_address WHERE user_id = ?", owner);
        jdbc.update("DELETE FROM user_account WHERE id = ?", owner);
    }

    @Test void returnsOwnedAddressForTheOrderServiceWithoutCaching() throws Exception {
        var response = get(path, query, sign(path, query));
        JsonNode data = check(response, 200, "OK").path("data");
        assertThat(data.path("addressId").textValue()).isEqualTo(Long.toString(address));
        assertThat(data.path("receiver").asText()).isEqualTo(RECEIVER);
        assertThat(data.path("phone").asText()).isEqualTo(PHONE);
        assertThat(data.path("regionCode").asText()).isEqualTo("310115");
        assertThat(data.path("detail").asText()).isEqualTo(DETAIL);
        assertThat(data.path("isDefault").asBoolean()).isTrue();
    }

    @Test void usesOneNotFoundResponseForWrongOwnerMissingAddressAndDisabledAccount() throws Exception {
        String anotherOwner = "userId=" + (owner + 1);
        check(get(path, anotherOwner, sign(path, anotherOwner)), 404, "NOT_FOUND");
        jdbc.update("UPDATE user_account SET status = 'DISABLED' WHERE id = ?", owner);
        check(get(path, query, sign(path, query)), 404, "NOT_FOUND");
        jdbc.update("UPDATE user_account SET status = 'ENABLED' WHERE id = ?", owner);
        jdbc.update("DELETE FROM user_address WHERE id = ?", address);
        check(get(path, query, sign(path, query)), 404, "NOT_FOUND");
    }

    @Test void rejectsUnsignedOrAlteredRequestsAndUntrustedCallers() throws Exception {
        String token = sign(path, query);
        check(get(path, query, null), 401, "INVALID_INTERNAL_TOKEN");
        check(get(path, "userId=" + (owner + 1), token), 401, "INVALID_INTERNAL_TOKEN");
        check(get(path, null, token), 401, "INVALID_INTERNAL_TOKEN");
        check(get(PATH + (address + 1), query, token), 401, "INVALID_INTERNAL_TOKEN");
        String legacy = ScopedServiceJwt.sign(orderKey, SCOPE, "GET", path, Clock.systemUTC());
        check(get(path, query, legacy), 401, "INVALID_INTERNAL_TOKEN");
        String wrongPermission = ScopedServiceJwt.signWithQuery(orderKey,
                new ScopedServiceJwt.Scope("shop-order", "shop-user", "address:write:own"),
                "GET", path, query, Clock.systemUTC());
        check(get(path, query, wrongPermission), 401, "INVALID_INTERNAL_TOKEN");
        String gatewayUser = InternalTestTokens.token("shop-user", Long.toString(owner), "user", "GET", path);
        check(get(path, null, gatewayUser), 401, "INVALID_INTERNAL_TOKEN");
    }

    @Test void rejectsMalformedOrRepeatedIdentifiersEvenWithAValidSignature() throws Exception {
        for (String invalid : List.of("userId=", "userId=0", "userId=9223372036854775808", query + "&" + query)) {
            check(get(path, invalid, sign(path, invalid)), 400, "VALIDATION_ERROR");
        }
        check(get(path, null, sign(path, null)), 400, "VALIDATION_ERROR");
        check(get(PATH + "01", query, sign(PATH + "01", query)), 400, "VALIDATION_ERROR");
    }

    @Test void returnsDependencyUnavailableWhenTheAddressReadFails() throws Exception {
        doThrow(new DataAccessResourceFailureException("simulated read failure"))
                .when(mapper).findByEnabledOwner(owner, address);
        check(get(path, query, sign(path, query)), 503, "DEPENDENCY_UNAVAILABLE");
    }

    private static String sign(String path, String query) {
        return ScopedServiceJwt.signWithQuery(orderKey, SCOPE, "GET", path, query, Clock.systemUTC());
    }

    private ResponseEntity<String> get(String path, String query, String token) {
        var headers = new HttpHeaders();
        headers.set("X-User-Id", Long.toString(owner));
        headers.set("X-Trace-Id", "test-order-address");
        if (token != null) headers.set(InternalJwtProtocol.HEADER, token);
        var uri = URI.create("http://127.0.0.1:" + port + path + (query == null ? "" : "?" + query));
        return http.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode check(ResponseEntity<String> response, int status, String code) throws Exception {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo("test-order-address");
        if (status != 200) {
            assertThat(response.getBody()).doesNotContain(RECEIVER, PHONE, DETAIL);
        }
        return body;
    }
}
