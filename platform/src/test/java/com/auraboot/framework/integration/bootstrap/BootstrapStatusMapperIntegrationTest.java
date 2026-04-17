package com.auraboot.framework.integration.bootstrap;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rbac.constant.RoleConstants;
import com.auraboot.framework.saas.bootstrap.mapper.BootstrapStatusMapper;
import com.auraboot.framework.saas.executor.SystemTenantContextExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB integration test for BootstrapStatusMapper.
 * Verifies that the SQL queries are valid against the actual schema
 * (column names, JOINs, deleted_flag semantics) — guards against schema drift.
 */
@DisplayName("BootstrapStatusMapper - SQL Integration Tests")
class BootstrapStatusMapperIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BootstrapStatusMapper bootstrapStatusMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void countPlatformAdminAssignments_runs_against_real_schema() {
        // Smoke: SQL parses, columns exist, JOIN works.
        long count = bootstrapStatusMapper.countPlatformAdminAssignments(RoleConstants.PLATFORM_ADMIN);
        assertThat(count).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void countPlatformAdminAssignments_returns_zero_for_unknown_role() {
        long count = bootstrapStatusMapper.countPlatformAdminAssignments("__nonexistent_role_code__");
        assertThat(count).isZero();
    }

    @Test
    void countPlatformAdminAssignments_matches_jdbc_count() {
        // Cross-check against a hand-rolled JDBC query — proves the JOIN /
        // deleted_flag filter is what we think it is.
        Long expected = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_user_role ur "
                + "JOIN ab_role r ON r.id = ur.role_id "
                + "WHERE r.code = ? "
                + "AND (r.deleted_flag = FALSE OR r.deleted_flag IS NULL) "
                + "AND (ur.deleted_flag = FALSE OR ur.deleted_flag IS NULL)",
                Long.class,
                RoleConstants.PLATFORM_ADMIN);
        long actual = bootstrapStatusMapper.countPlatformAdminAssignments(RoleConstants.PLATFORM_ADMIN);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void countTenantById_returns_zero_for_nonexistent_tenant() {
        long count = bootstrapStatusMapper.countTenantById(-9999L);
        assertThat(count).isZero();
    }

    @Test
    void countTenantById_for_system_tenant_id_runs_against_real_schema() {
        // Smoke: SQL parses, table/columns exist. Returns 0 or 1 depending on
        // whether the System Tenant has been seeded in the test environment.
        long count = bootstrapStatusMapper.countTenantById(SystemTenantContextExecutor.SYSTEM_TENANT_ID);
        assertThat(count).isBetween(0L, 1L);
    }
}
