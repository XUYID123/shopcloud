package org.spring.shopuser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.spring.shopuser.entity.UserEntity;
import org.spring.shopuser.mapper.RoleMapper;
import org.spring.shopuser.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.docker.compose.skip.in-tests=false", "shop.permissions.key-prefix=test:registration:permissions"})
@ExtendWith(OutputCaptureExtension.class)
class UserRegistrationIntegrationTests extends AuthIntegrationSupport {
    private static final String PASSWORD = "FlourTest123!";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @MockitoSpyBean
    private RoleMapper roleMapper;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate permissionRedis;

    private final Set<String> testUsernames = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setRequestTimeouts() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(20000);
        restTemplate.getRestTemplate().setRequestFactory(factory);
    }

    @AfterEach
    void removeOnlyAccountsCreatedByThisTest() {
        for (String username : testUsernames) {
            for (long id : jdbcTemplate.queryForList("SELECT id FROM user_account WHERE username=?", Long.class, username)) {
                String prefix = "test:registration:permissions";
                String userId = Long.toString(id);
                permissionRedis.delete(List.of(org.spring.shopcommon.auth.PermissionCacheKeys.user(prefix, userId),
                        org.spring.shopcommon.auth.PermissionCacheKeys.epoch(prefix, userId),
                        org.spring.shopcommon.auth.PermissionCacheKeys.lock(prefix, userId)));
                jdbcTemplate.update("DELETE FROM permission_cache_invalidation WHERE user_id=? AND key_prefix=?", id, prefix);
            }
            jdbcTemplate.update("""
                    DELETE FROM user_role
                    WHERE user_id IN (SELECT id FROM user_account WHERE username = ?)
                    """, username);
            jdbcTemplate.update("DELETE FROM user_account WHERE username = ?", username);
        }
    }

    @Test
    void registersWithHashedPasswordDefaultRoleAndSafeResponse() throws Exception {
        String username = newUsername();
        String requestId = "register-" + UUID.randomUUID();
        var response = post(validRequest(username), requestId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("code", "message", "data", "traceId");
        assertThat(body.path("code").asText()).isEqualTo("OK");
        assertThat(body.path("traceId").asText()).isEqualTo(requestId);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo(requestId);
        JsonNode data = body.path("data");
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("userId", "username", "roles");
        assertThat(data.path("userId").isTextual()).isTrue();
        assertThat(data.path("username").asText()).isEqualTo(username);
        assertThat(data.path("roles").size()).isEqualTo(1);
        assertThat(data.path("roles").get(0).asText()).isEqualTo("USER");
        assertThat(response.getHeaders().getLocation()).hasToString("/api/v1/users/" + data.path("userId").asText());

        UserEntity user = userMapper.findById(Long.valueOf(data.path("userId").asText()));
        assertThat(user.getUsername()).isEqualTo(username);
        assertThat(user.getPasswordHash()).startsWith("$2a$").isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(user.getStatus()).isEqualTo("ENABLED");
        assertThat(user.getTokenVersion()).isZero();
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getUpdatedAt()).isNotNull();
        assertThat(userMapper.findByUsername(username).getId()).isEqualTo(user.getId());
        assertThat(roleCodes(user.getId())).containsExactly("USER");
        assertThat(response.getBody()).doesNotContain(PASSWORD, user.getPasswordHash(),
                "passwordHash", "password_hash", "tokenVersion", "nickname", "status");
        assertThat(objectMapper.writeValueAsString(user)).doesNotContain(user.getPasswordHash(), "passwordHash");
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 72})
    void acceptsPasswordLengthBoundariesWithMaximumUsernameLength(int passwordLength) throws Exception {
        String username = newUsername() + "boundary";
        testUsernames.add(username);
        String password = "P".repeat(passwordLength);
        ObjectNode payload = validRequest(username).put("password", password);
        var response = post(payload, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UserEntity user = userMapper.findByUsername(username);
        assertThat(passwordEncoder.matches(password, user.getPasswordHash())).isTrue();
        assertThat(roleCodes(user.getId())).containsExactly("USER");
    }

    @ParameterizedTest
    @MethodSource("invalidValues")
    void rejectsInvalidFieldValues(String field, String jsonValue) throws Exception {
        String username = newUsername();
        ObjectNode payload = validRequest(username);
        payload.set(field, objectMapper.readTree(jsonValue));
        var response = post(payload, null);
        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(userMapper.findByUsername(username)).isNull();
    }

    static Stream<Arguments> invalidValues() {
        return Stream.of(
                Arguments.of("username", "null"),
                Arguments.of("username", "\"\""),
                Arguments.of("username", "\"ab\""),
                Arguments.of("username", "\"" + "a".repeat(33) + "\""),
                Arguments.of("username", "\"bad name\""),
                Arguments.of("username", "\"面粉用户\""),
                Arguments.of("username", "12345"),
                Arguments.of("username", "true"),
                Arguments.of("username", "{}"),
                Arguments.of("password", "null"),
                Arguments.of("password", "\"\""),
                Arguments.of("password", "\"short7\""),
                Arguments.of("password", "\"" + "p".repeat(73) + "\""),
                Arguments.of("password", "\"has space123\""),
                Arguments.of("password", "\"密码Password123\""),
                Arguments.of("password", "12345678"),
                Arguments.of("password", "true")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "password"})
    void rejectsMissingRequiredFields(String field) throws Exception {
        ObjectNode payload = validRequest(newUsername());
        payload.remove(field);
        assertError(post(payload, null), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "roleId", "roleIds", "roles", "permissions", "permissionIds",
            "status", "userId", "passwordHash", "tokenVersion"})
    void rejectsRoleInjectionAndOtherUnrecognizedFields(String field) throws Exception {
        String username = newUsername();
        ObjectNode payload = validRequest(username);
        payload.set(field, objectMapper.valueToTree(List.of("ADMIN")));
        assertError(post(payload, null), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(userMapper.findByUsername(username)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "[]"})
    void rejectsMalformedOrMissingJson(String json) throws Exception {
        assertError(postJson(json, null), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }

    @Test
    void rejectsTrailingJson() throws Exception {
        String json = objectMapper.writeValueAsString(validRequest(newUsername())) + " {}";
        assertError(postJson(json, null), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }

    @Test
    void doesNotEchoOrLogRejectedPassword(CapturedOutput output) throws Exception {
        String secret = "RejectedSecret987! ";
        ObjectNode payload = validRequest(newUsername());
        payload.put("password", secret);
        var response = post(payload, null);
        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(response.getBody()).doesNotContain(secret);
        assertThat(output.getAll()).doesNotContain(secret);
    }

    @Test
    void generatesRequestIdWhenClientSuppliesAnInvalidValue() throws Exception {
        var response = post(validRequest(newUsername()), "invalid request id");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String traceId = objectMapper.readTree(response.getBody()).path("traceId").asText();
        assertThat(traceId).matches("[A-Za-z0-9._-]{1,64}").isNotEqualTo("invalid request id");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo(traceId);
    }

    @Test
    void treatsUsernamesAsCaseInsensitive() throws Exception {
        String username = newUsername();
        assertThat(post(validRequest(username), null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertError(post(validRequest(username.toUpperCase(Locale.ROOT)), null),
                HttpStatus.CONFLICT, "USERNAME_EXISTS");
        assertThat(accountCount(username)).isEqualTo(1);
        assertThat(roleCodes(userMapper.findByUsername(username).getId())).containsExactly("USER");
    }

    @Test
    void concurrentDuplicateRegistrationsCreateOneUserAndOneRoleLink() throws Exception {
        int requests = 12;
        String username = newUsername();
        var ready = new CountDownLatch(requests);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(requests);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < requests; index++) {
                String requestedUsername = index % 2 == 0 ? username : username.toUpperCase(Locale.ROOT);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent registration start timed out");
                    }
                    return post(validRequest(requestedUsername), null);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int created = 0;
            int conflicts = 0;
            Set<String> traceIds = new HashSet<>();
            for (var future : futures) {
                var response = future.get(30, TimeUnit.SECONDS);
                traceIds.add(response.getHeaders().getFirst("X-Request-Id"));
                if (response.getStatusCode() == HttpStatus.CREATED) {
                    created++;
                } else {
                    assertError(response, HttpStatus.CONFLICT, "USERNAME_EXISTS");
                    conflicts++;
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(conflicts).isEqualTo(requests - 1);
            assertThat(traceIds).hasSize(requests);
            assertThat(accountCount(username)).isEqualTo(1);
            assertThat(roleCodes(userMapper.findByUsername(username).getId())).containsExactly("USER");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void refusesRegistrationWhenDefaultRoleIsUnavailable() throws Exception {
        String username = newUsername();
        doReturn(null).when(roleMapper).findEnabledRoleIdForShare("USER");
        assertError(post(validRequest(username), null), HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
        assertThat(accountCount(username)).isZero();
    }

    @Test
    void rollsBackUserWhenRoleAssignmentFails() throws Exception {
        String username = newUsername();
        AtomicLong insertedId = new AtomicLong();
        Long roleId = jdbcTemplate.queryForObject("SELECT id FROM `role` WHERE role_code = 'USER'", Long.class);
        doAnswer(invocation -> {
            insertedId.set(invocation.getArgument(0, Long.class));
            throw new DataAccessResourceFailureException("simulated role assignment failure");
        }).when(roleMapper).insertUserRole(anyLong(), eq(roleId));

        var response = post(validRequest(username), null);
        assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        assertThat(response.getBody()).doesNotContain("simulated role assignment failure");
        assertThat(insertedId.get()).isPositive();
        assertThat(userMapper.findById(insertedId.get())).isNull();
        assertThat(accountCount(username)).isZero();
    }

    private String newUsername() {
        String username = "reg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        testUsernames.add(username);
        return username;
    }

    private ObjectNode validRequest(String username) {
        return objectMapper.createObjectNode().put("username", username).put("password", PASSWORD);
    }

    private ResponseEntity<String> post(ObjectNode payload, String requestId) throws Exception {
        return postJson(objectMapper.writeValueAsString(payload), requestId);
    }

    private ResponseEntity<String> postJson(String json, String requestId) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", org.spring.shopcommon.internalauth.InternalTestTokens.token("shop-user", null, "anonymous", "POST", "/api/v1/users"));
        if (requestId != null) {
            headers.set("X-Request-Id", requestId);
        }
        return restTemplate.exchange("/api/v1/users", HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private void assertError(ResponseEntity<String> response, HttpStatus status, String code) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(status);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("code", "message", "data", "traceId");
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(body.path("traceId").asText()).isNotBlank()
                .isEqualTo(response.getHeaders().getFirst("X-Request-Id"));
        assertThat(response.getBody()).doesNotContain(PASSWORD, "passwordHash", "password_hash");
    }

    private int accountCount(String username) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_account WHERE username = ?", Integer.class, username);
    }

    private List<String> roleCodes(Long userId) {
        return jdbcTemplate.queryForList("""
                SELECT r.role_code FROM user_role ur JOIN `role` r ON r.id = ur.role_id
                WHERE ur.user_id = ? ORDER BY r.role_code
                """, String.class, userId);
    }
}
