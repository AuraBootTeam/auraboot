package com.auraboot.framework.integration.security;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REG-TENANT-5 regression guard (DDR-2026-06-30): {@code RoleMapper.findDefaultRole} declared a
 * {@code tenantId} param but omitted it from the WHERE clause, so in a multi-tenant DB it returned
 * an arbitrary tenant's default role (a cross-tenant correctness bug feeding member provisioning /
 * default-scope resolution). This IT locks the fix: {@code findDefaultRole(foreignTenant)} must
 * return the FOREIGN tenant's default (scoped to the requested tenant), not some other tenant's.
 *
 * <p>Direct-mapper coverage was previously only a mock unit test (RoleServiceImplTest), which cannot
 * exercise the SQL predicate — hence this real-DB IT.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("findDefaultRole is tenant-scoped (REG-TENANT-5)")
class RoleFindDefaultRoleTenantScopeIT extends BaseIntegrationTest {

    @Autowired private RoleService roleService;
    @Autowired private JdbcTemplate jdbc;

    private Long foreignTenantId;
    private Long foreignDefaultRoleId;

    @BeforeEach
    void setup() {
        MetaContext.setContext(testTenant.getId(), testUser.getId(),
                testUser.getPid(), testUser.getUserName());
        // A real, existing tenant that is NOT the caller's (ab_role.tenant_id has FK fk_role_tenant).
        foreignTenantId = jdbc.queryForObject(
                "SELECT id FROM ab_tenant WHERE id <> ? AND deleted_flag = false ORDER BY id LIMIT 1",
                Long.class, testTenant.getId());
        assertThat(foreignTenantId).as("test needs a second tenant to exist").isNotNull();

        // Seed a default role owned by the FOREIGN tenant.
        foreignDefaultRoleId = (System.currentTimeMillis() << 12) | (System.nanoTime() & 0xFFF);
        String pid = "reg5d" + (System.nanoTime() % 1_000_000_000L); // pid VARCHAR(26) UNIQUE
        jdbc.update(
                "INSERT INTO ab_role (id, pid, name, code, type, scope_type, status, tenant_id, "
                        + " is_default, is_system, deleted_flag, priority, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'system', 'global', 'active', ?, true, true, false, 1, now(), now())",
                foreignDefaultRoleId, pid, "reg5_foreign_default", "reg5_" + foreignDefaultRoleId, foreignTenantId);
    }

    @AfterEach
    void cleanup() {
        if (foreignDefaultRoleId != null) jdbc.update("DELETE FROM ab_role WHERE id = ?", foreignDefaultRoleId);
        MetaContext.clear();
    }

    @Test
    @DisplayName("findDefaultRole(foreignTenant) returns the foreign tenant's default, scoped by tenant_id")
    void findDefaultRole_isTenantScoped() {
        // Scoped to the requested (foreign) tenant → must return the foreign-tenant default we seeded.
        Role foreignDefault = roleService.findDefaultRole(foreignTenantId);
        assertThat(foreignDefault).as("foreign tenant's default role must resolve").isNotNull();
        assertThat(foreignDefault.getTenantId())
                .as("findDefaultRole must be scoped to the requested tenant (REG-TENANT-5)")
                .isEqualTo(foreignTenantId);

        // The caller tenant's lookup must NOT leak the foreign default (different tenant → different id / null).
        Role callerDefault = roleService.findDefaultRole(testTenant.getId());
        if (callerDefault != null) {
            assertThat(callerDefault.getTenantId())
                    .as("caller tenant's default must belong to the caller tenant, never the foreign one")
                    .isEqualTo(testTenant.getId());
            assertThat(callerDefault.getId()).isNotEqualTo(foreignDefaultRoleId);
        }
    }
}
