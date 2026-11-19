package org.spring.shopuser;

import org.junit.jupiter.api.*;
import org.spring.shopcommon.auth.PermissionCacheKeys;
import org.spring.shopuser.config.UserPermissionProperties;
import org.spring.shopuser.service.PermissionCacheInvalidator;
import org.spring.shopuser.service.UserPermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 MySQL/Flyway 与 6381 Redis；所有数据和缓存均按本测试命名空间清理。 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.docker.compose.skip.in-tests=false", "spring.cloud.nacos.discovery.enabled=false"
})
class UserPermissionIntegrationTests extends AuthIntegrationSupport {
    private static final String PREFIX = "test:permission:" + UUID.randomUUID().toString().replace("-", "");
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserPermissionService service;
    @Autowired private PermissionCacheInvalidator invalidator;
    @Autowired private UserPermissionProperties properties;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private TestRestTemplate http;
    @MockitoSpyBean private StringRedisTemplate redis;
    private final List<Long> users = new ArrayList<>();
    private final List<Long> roles = new ArrayList<>();
    private final List<Long> permissions = new ArrayList<>();

    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        registry.add("shop.permissions.key-prefix", () -> PREFIX);
    }

    @AfterEach void cleanup() {
        for (long user : users) {
            jdbc.update("DELETE FROM permission_cache_invalidation WHERE user_id=? AND key_prefix=?", user, PREFIX);
            jdbc.update("DELETE FROM user_role WHERE user_id=?", user);
            jdbc.update("DELETE FROM user_account WHERE id=?", user);
            redis.delete(List.of(PermissionCacheKeys.user(PREFIX, Long.toString(user)),
                    PermissionCacheKeys.epoch(PREFIX, Long.toString(user)), PermissionCacheKeys.lock(PREFIX, Long.toString(user))));
        }
        for (long role : roles) { jdbc.update("DELETE FROM role_permission WHERE role_id=?", role); jdbc.update("DELETE FROM `role` WHERE id=?", role); }
        for (long permission : permissions) jdbc.update("DELETE FROM permission WHERE id=?", permission);
    }

    @Test void joinsEnabledRolesAndPermissionsDeduplicatesAndNeverLoadsPasswords() {
        long user = user();
        long first = role("ENABLED"), second = role("ENABLED"), disabled = role("DISABLED");
        long allowed = permission("order:create", "ENABLED"), denied = permission("order:ship", "DISABLED");
        long disabledRoleOnly = permission("product:write", "ENABLED");
        assign(user, first, allowed); assign(user, second, allowed); assign(user, first, denied); assign(user, disabled, disabledRoleOnly);
        var snapshot = service.load(user);
        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.permissions()).containsExactly(code(allowed));
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", user);
        assertThat(service.load(user).enabled()).isFalse();
        assertThat(service.load(user).permissions()).isEmpty();
        assertThat(service.load(Long.MAX_VALUE).enabled()).isFalse();
    }

    @Test void internalHttpRequiresCredentialAndReturnsOnlySafePermissionContract() {
        long user = user();
        assertThat(http.getForEntity("/internal/v1/users/" + user + "/permissions", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders headers = new HttpHeaders();
        headers.set(PermissionCacheKeys.INTERNAL_TOKEN_HEADER, org.spring.shopcommon.internalauth.InternalTestTokens.token("shop-user", Long.toString(user), "permission-query", "GET", "/internal/v1/users/" + user + "/permissions"));
        headers.set("X-Request-Id", "permission-internal-test");
        var response = http.exchange("/internal/v1/users/" + user + "/permissions", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("permission-internal-test");
        assertThat(response.getBody()).contains("\"permissions\":[]", "\"userId\":\"" + user + "\"")
                .doesNotContain("password", "passwordHash", "roleId");
        assertThat(http.exchange("/internal/v1/users/0/permissions", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test void committedChangeDeletesRedisWhileRollbackKeepsCacheAndDoesNotEnqueue() {
        long user = user();
        String key = PermissionCacheKeys.user(PREFIX, Long.toString(user));
        redis.opsForValue().set(key, "cached-before-change");
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", user);
            invalidator.enqueueUser(user);
            status.setRollbackOnly();
        });
        assertThat(redis.opsForValue().get(key)).isEqualTo("cached-before-change");
        assertThat(service.load(user).enabled()).isTrue();
        assertThat(pending(user)).isZero();
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", user);
            invalidator.enqueueUser(user);
        });
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(service.load(user).enabled()).isFalse();
        invalidator.retryPending();
        assertThat(pending(user)).isZero();
    }

    @Test @SuppressWarnings("unchecked")
    void redisFailureDoesNotUndoCommittedDataAndOutboxRetriesAfterRecovery() {
        long user = user();
        String key = PermissionCacheKeys.user(PREFIX, Long.toString(user));
        redis.opsForValue().set(key, "cached-before-change");
        doThrow(new RedisConnectionFailureException("test unavailable")).when(redis)
                .execute(any(RedisScript.class), anyList(), anyString());
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", user);
                invalidator.enqueueUser(user);
            });
            assertThat(service.load(user).enabled()).isFalse();
            assertThat(pending(user)).isEqualTo(1);
            invalidator.retryPending();
            assertThat(pending(user)).isEqualTo(1);
            assertThat(redis.hasKey(key)).isTrue();
        } finally {
            doCallRealMethod().when(redis).execute(any(RedisScript.class), anyList(), anyString());
        }
        jdbc.update("UPDATE permission_cache_invalidation SET next_attempt_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND key_prefix=?", user, PREFIX);
        invalidator.retryPending();
        assertThat(pending(user)).isZero();
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test void roleChangeInvalidatesAllAffectedUsersAndCoalescesRepeatedEvents() {
        long first = user(), second = user(), role = role("ENABLED"), permission = permission("order:create", "ENABLED");
        assign(first, role, permission); assign(second, role, permission);
        for (long id : List.of(first, second)) redis.opsForValue().set(PermissionCacheKeys.user(PREFIX, Long.toString(id)), "old");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("UPDATE `role` SET status='DISABLED' WHERE id=?", role);
            invalidator.enqueueRole(role);
            invalidator.enqueueUser(first);
        });
        for (long id : List.of(first, second)) {
            assertThat(redis.hasKey(PermissionCacheKeys.user(PREFIX, Long.toString(id)))).isFalse();
            assertThat(service.load(id).permissions()).isEmpty();
        }
        invalidator.retryPending();
        assertThat(pending(first) + pending(second)).isZero();
    }

    private int pending(long user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM permission_cache_invalidation WHERE user_id=? AND key_prefix=?", Integer.class, user, PREFIX);
    }
    private long user() {
        String name = "perm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.update("INSERT INTO user_account(username,password_hash,status) VALUES (?,?,'ENABLED')", name, "test-hash");
        long id = jdbc.queryForObject("SELECT id FROM user_account WHERE username=?", Long.class, name);
        users.add(id); return id;
    }
    private long role(String status) {
        String code = "TEST_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO `role`(role_code,role_name,status) VALUES (?,?,?)", code, code, status);
        long id = jdbc.queryForObject("SELECT id FROM `role` WHERE role_code=?", Long.class, code);
        roles.add(id); return id;
    }
    private long permission(String code, String status) {
        String unique = code + ":" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO permission(permission_code,permission_name,status) VALUES (?,?,?)", unique, unique, status);
        long id = jdbc.queryForObject("SELECT id FROM permission WHERE permission_code=?", Long.class, unique);
        permissions.add(id); return id;
    }
    private String code(long permission) { return jdbc.queryForObject("SELECT permission_code FROM permission WHERE id=?", String.class, permission); }
    private void assign(long user, long role, long permission) {
        jdbc.update("INSERT IGNORE INTO user_role(user_id,role_id) VALUES (?,?)", user, role);
        jdbc.update("INSERT IGNORE INTO role_permission(role_id,permission_id) VALUES (?,?)", role, permission);
    }
}
