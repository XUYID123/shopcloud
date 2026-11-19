package org.spring.shopuser;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class ShopUserApplicationTests extends AuthIntegrationSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired private org.springframework.context.ApplicationContext context;

    @Test
    void initializesRbacSchemaAndSeedPermissions() {
        assertThat(context.containsBean("internalAuthenticationFilterRegistration")).isTrue();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IN ('1', '2')",
                Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('user_account', 'role', 'permission', 'user_role', 'role_permission')
                """, String.class)).containsExactlyInAnyOrder(
                "user_account", "role", "permission", "user_role", "role_permission");
        assertThat(jdbcTemplate.queryForList(
                "SELECT role_code FROM `role` WHERE role_code IN ('USER', 'ADMIN') AND status = 'ENABLED'",
                String.class)).containsExactlyInAnyOrder("USER", "ADMIN");
        assertThat(jdbcTemplate.queryForList("""
                SELECT p.permission_code
                FROM role_permission rp
                JOIN `role` r ON r.id = rp.role_id
                JOIN permission p ON p.id = rp.permission_id
                WHERE r.role_code = 'USER'
                """, String.class)).containsExactlyInAnyOrder(
                "cart:write:own", "order:create", "order:read:own", "address:write:own");
    }

}
