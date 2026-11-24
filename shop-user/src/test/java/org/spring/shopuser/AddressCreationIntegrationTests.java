package org.spring.shopuser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spring.shopcommon.internalauth.InternalTestTokens;
import org.spring.shopuser.mapper.AddressMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AddressCreationIntegrationTests extends AuthIntegrationSupport {
    private static final String PATH = "/api/v1/addresses";
    private static final String VALID = """
            {"receiver":" 演示收件人 ","phone":"13800000000","regionCode":"310115",
             "detail":" 演示路100号 ","isDefault":true}
            """;

    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private AddressMapper mapper;

    private final List<Long> accounts = new ArrayList<>();
    private long owner;

    @BeforeEach void prepareAccount() {
        owner = account("ENABLED");
    }

    @AfterEach void cleanFixtures() {
        for (long id : accounts) {
            jdbc.update("DELETE FROM user_address WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_account WHERE id = ?", id);
        }
    }

    @Test void createsForVerifiedOwnerAndSwitchesOnlyThatOwnersDefault() throws Exception {
        long other = account("ENABLED");
        long otherDefault = createdId(post(VALID, userToken(other), null));
        var response = post(VALID, userToken(owner), Long.toString(other));
        long first = createdId(response);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getLocation()).hasToString(PATH + "/" + first);
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo("test-address");
        JsonNode data = json.readTree(response.getBody()).path("data");
        assertThat(data.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("addressId", "receiver", "phone", "regionCode", "detail", "isDefault");
        assertThat(data.path("receiver").asText()).isEqualTo("演示收件人");
        assertThat(data.path("detail").asText()).isEqualTo("演示路100号");
        assertThat(data.path("addressId").isTextual()).isTrue();
        long ordinary = createdId(post(VALID.replace("true", "false"), userToken(owner), null));
        assertThat(defaultIds(owner)).containsExactly(first);
        long last = createdId(post(VALID, userToken(owner), null));
        assertThat(defaultIds(owner)).containsExactly(last);
        assertThat(defaultIds(other)).containsExactly(otherDefault);
        assertThat(jdbc.queryForList("SELECT id FROM user_address WHERE user_id = ? ORDER BY id", Long.class, owner))
                .containsExactly(first, ordinary, last);
    }

    @Test void rejectsUntrustedIdentityExtraFieldsAndInvalidInput() throws Exception {
        check(post(VALID, null, Long.toString(owner)), 401, "INVALID_INTERNAL_TOKEN");
        check(post(VALID, InternalTestTokens.token("shop-product", Long.toString(owner), "user", "POST", PATH), null),
                401, "INVALID_INTERNAL_TOKEN");
        check(post(VALID, InternalTestTokens.token("shop-user", null, "anonymous", "POST", PATH), null), 403, "FORBIDDEN");
        check(post(VALID, userToken(account("DISABLED")), null), 403, "FORBIDDEN");
        String token = userToken(owner);
        for (String invalid : List.of(VALID.replace("{", "{\"userId\":1,"),
                VALID.replace("true", "\"true\""), VALID.replace("\"310115\"", "310115"),
                VALID.replace("13800000000", "123"), VALID.replace(" 演示收件人 ", "   "))) {
            check(post(invalid, token, null), 400, "VALIDATION_ERROR");
        }
        check(post(VALID.replace("310115", "999999"), token, null), 422, "ADDRESS_REGION_UNSUPPORTED");
        assertThat(count()).isZero();
    }

    @Test void concurrentCreatesCannotExceedLimitOrCreateTwoDefaults() throws Exception {
        createdId(post(VALID, userToken(owner), null));
        for (int i = 0; i < 18; i++) {
            jdbc.update("""
                    INSERT INTO user_address (user_id, receiver, phone, region_code, detail)
                    VALUES (?, '演示收件人', '13800000000', '310115', '演示地址')
                    """, owner);
        }
        var ready = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> {
                ready.await(5, TimeUnit.SECONDS);
                return post(VALID, userToken(owner), null);
            });
            var second = workers.submit(() -> {
                ready.await(5, TimeUnit.SECONDS);
                return post(VALID, userToken(owner), null);
            });
            var responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(responses.stream().map(r -> r.getStatusCode().value()))
                    .containsExactlyInAnyOrder(201, 409);
            var rejected = responses.stream().filter(r -> r.getStatusCode().value() == 409).findFirst().orElseThrow();
            check(rejected, 409, "ADDRESS_LIMIT_EXCEEDED");
            var accepted = responses.stream().filter(r -> r.getStatusCode().value() == 201).findFirst().orElseThrow();
            assertThat(defaultIds(owner)).containsExactly(createdId(accepted));
            assertThat(count()).isEqualTo(20);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test void failedInsertRollsBackDefaultChange() throws Exception {
        long existing = createdId(post(VALID, userToken(owner), null));
        doThrow(new DataAccessResourceFailureException("address database unavailable")).when(mapper).insert(any());
        check(post(VALID, userToken(owner), null), 503, "DEPENDENCY_UNAVAILABLE");
        assertThat(defaultIds(owner)).containsExactly(existing);
        assertThat(count()).isEqualTo(1);
    }

    @Test void listsInStableOrderReplacesAllFieldsAndDeletesTheDefault() throws Exception {
        assertThat(addressIds(list(owner, null))).isEmpty();
        String ordinary = VALID.replace("true", "false");
        long first = createdId(post(ordinary, userToken(owner), null));
        long originalDefault = createdId(post(VALID, userToken(owner), null));
        long latest = createdId(post(ordinary, userToken(owner), null));
        assertThat(addressIds(list(owner, null))).containsExactly(originalDefault, latest, first);

        String replacement = """
                {"receiver":"新收件人","phone":"15900000000","regionCode":"310115",
                 "detail":"新地址200号","isDefault":true}
                """;
        for (int attempt = 0; attempt < 2; attempt++) {
            var response = sendForOwner(HttpMethod.PUT, PATH + "/" + first, replacement, owner, null);
            check(response, 200, "OK");
            JsonNode item = json.readTree(response.getBody()).path("data");
            assertThat(item.path("addressId").asText()).isEqualTo(Long.toString(first));
            assertThat(item.path("receiver").asText()).isEqualTo("新收件人");
            assertThat(item.path("phone").asText()).isEqualTo("15900000000");
            assertThat(item.path("regionCode").asText()).isEqualTo("310115");
            assertThat(item.path("detail").asText()).isEqualTo("新地址200号");
            assertThat(item.path("isDefault").asBoolean()).isTrue();
        }
        assertThat(defaultIds(owner)).containsExactly(first);
        assertThat(addressIds(list(owner, null))).containsExactly(first, latest, originalDefault);

        var cleared = sendForOwner(HttpMethod.PUT, PATH + "/" + first, replacement.replace("true", "false"), owner, null);
        check(cleared, 200, "OK");
        assertThat(defaultIds(owner)).isEmpty();
        check(sendForOwner(HttpMethod.PUT, PATH + "/" + first, replacement, owner, null), 200, "OK");
        var deleted = sendForOwner(HttpMethod.DELETE, PATH + "/" + first, null, owner, null);
        check(deleted, 200, "OK");
        assertThat(json.readTree(deleted.getBody()).path("data").isNull()).isTrue();
        assertThat(defaultIds(owner)).isEmpty();
        assertThat(addressIds(list(owner, null))).containsExactly(latest, originalDefault);
        check(sendForOwner(HttpMethod.DELETE, PATH + "/" + first, null, owner, null), 404, "NOT_FOUND");
    }

    @Test void remainingEndpointsRejectWrongOwnerAndUntrustedIdentity() throws Exception {
        long other = account("ENABLED");
        long otherDefault = createdId(post(VALID, userToken(other), null));
        long mine = createdId(post(VALID, userToken(owner), null));
        assertThat(addressIds(list(owner, Long.toString(other)))).containsExactly(mine);
        String otherPath = PATH + "/" + otherDefault;
        String forgedHeader = Long.toString(other);
        check(sendForOwner(HttpMethod.PUT, otherPath, VALID, owner, forgedHeader), 404, "NOT_FOUND");
        check(sendForOwner(HttpMethod.DELETE, otherPath, null, owner, forgedHeader), 404, "NOT_FOUND");
        assertThat(defaultIds(other)).containsExactly(otherDefault);
        assertThat(defaultIds(owner)).containsExactly(mine);
        check(sendForOwner(HttpMethod.PUT, PATH + "/" + mine, VALID.replace("310115", "999999"), owner, null),
                422, "ADDRESS_REGION_UNSUPPORTED");
        check(sendForOwner(HttpMethod.PUT, PATH + "/01", VALID, owner, null), 400, "VALIDATION_ERROR");
        check(sendForOwner(HttpMethod.DELETE, PATH + "/9223372036854775808", null, owner, null), 400, "VALIDATION_ERROR");
        check(exchange(HttpMethod.GET, PATH, null, null, forgedHeader), 401, "INVALID_INTERNAL_TOKEN");
        String expired = InternalTestTokens.token("shop-user", Long.toString(owner), "user", "GET", PATH,
                Instant.now().minusSeconds(90));
        check(exchange(HttpMethod.GET, PATH, null, expired, null), 401, "INVALID_INTERNAL_TOKEN");
        check(sendForOwner(HttpMethod.GET, PATH, null, account("DISABLED"), null), 403, "FORBIDDEN");
    }

    @Test void failedReplacementRestoresThePreviousDefault() throws Exception {
        long previous = createdId(post(VALID, userToken(owner), null));
        long current = createdId(post(VALID.replace("true", "false"), userToken(owner), null));
        doThrow(new DataAccessResourceFailureException("address database unavailable")).when(mapper).replace(eq(current), any());
        check(sendForOwner(HttpMethod.PUT, PATH + "/" + current, VALID, owner, null), 503, "DEPENDENCY_UNAVAILABLE");
        assertThat(defaultIds(owner)).containsExactly(previous);
        assertThat(addressIds(list(owner, null))).containsExactly(previous, current);
    }

    @Test void racingReplacementAndDeleteNeverRecreateTheDeletedAddress() throws Exception {
        long previous = createdId(post(VALID, userToken(owner), null));
        long current = createdId(post(VALID.replace("true", "false"), userToken(owner), null));
        String path = PATH + "/" + current;
        var ready = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var editing = workers.submit(() -> {
                ready.await(5, TimeUnit.SECONDS);
                return sendForOwner(HttpMethod.PUT, path, VALID, owner, null);
            });
            var deleting = workers.submit(() -> {
                ready.await(5, TimeUnit.SECONDS);
                return sendForOwner(HttpMethod.DELETE, path, null, owner, null);
            });
            var edited = editing.get(20, TimeUnit.SECONDS);
            check(deleting.get(20, TimeUnit.SECONDS), 200, "OK");
            if (edited.getStatusCode().value() == 200) {
                assertThat(defaultIds(owner)).isEmpty();
            } else {
                check(edited, 404, "NOT_FOUND");
                assertThat(defaultIds(owner)).containsExactly(previous);
            }
            assertThat(addressIds(list(owner, null))).containsExactly(previous);
        } finally {
            workers.shutdownNow();
        }
    }

    private long account(String status) {
        String name = "addr_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO user_account (username, password_hash, status) VALUES (?, 'test-fixture', ?)",
                name, status);
        long id = jdbc.queryForObject("SELECT id FROM user_account WHERE username = ?", Long.class, name);
        accounts.add(id);
        return id;
    }

    private static String userToken(long id) {
        return InternalTestTokens.token("shop-user", Long.toString(id), "user", "POST", PATH);
    }

    private ResponseEntity<String> post(String body, String internalToken, String assertedOwner) {
        return exchange(HttpMethod.POST, PATH, body, internalToken, assertedOwner);
    }

    private ResponseEntity<String> sendForOwner(HttpMethod method, String path, String body, long userId,
                                                 String assertedOwner) {
        String token = InternalTestTokens.token("shop-user", Long.toString(userId), "user", method.name(), path);
        return exchange(method, path, body, token, assertedOwner);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String body, String internalToken,
                                            String assertedOwner) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Trace-Id", "test-address");
        if (internalToken != null) headers.set("X-Internal-Token", internalToken);
        if (assertedOwner != null) headers.set("X-User-Id", assertedOwner);
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode list(long userId, String assertedOwner) throws Exception {
        var response = sendForOwner(HttpMethod.GET, PATH, null, userId, assertedOwner);
        check(response, 200, "OK");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        return json.readTree(response.getBody()).path("data");
    }

    private List<Long> addressIds(JsonNode items) {
        assertThat(items.isArray()).isTrue();
        List<Long> ids = new ArrayList<>();
        items.forEach(item -> {
            assertThat(item.path("addressId").isTextual()).isTrue();
            ids.add(item.path("addressId").asLong());
        });
        return ids;
    }

    private long createdId(ResponseEntity<String> response) throws Exception {
        check(response, 201, "OK");
        return json.readTree(response.getBody()).at("/data/addressId").asLong();
    }

    private void check(ResponseEntity<String> response, int status, String code) throws Exception {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(status);
        assertThat(json.readTree(response.getBody()).path("code").asText()).isEqualTo(code);
    }

    private List<Long> defaultIds(long userId) {
        return jdbc.queryForList("SELECT id FROM user_address WHERE user_id = ? AND is_default = TRUE",
                Long.class, userId);
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_address WHERE user_id = ?", Integer.class, owner);
    }
}
