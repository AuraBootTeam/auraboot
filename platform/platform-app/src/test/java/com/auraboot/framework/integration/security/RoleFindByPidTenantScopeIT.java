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
 * REG-7 regression guard (DDR-2026-06-30-quote-bom-rbac-capability-model-endstate):
 * {@code ab_role} is exempt from the MyBatis-Plus tenant-line interceptor and
 * {@code RoleMapper.findByPid} has no tenant predicate, so a tenant-A admin holding the
 * ordinary ROLE_MANAGE/PERMISSION_MANAGE permission could resolve (then mutate) a tenant-B
 * role by PID via RoleController / PermissionMatrixController / CapabilityController /
 * RoleMemberController. The fix scopes {@code RoleServiceImpl.findByPid} to the current
 * tenant context. This IT locks it: a foreign-tenant role PID resolves to {@code null}
 * under tenant A's context, while a same-tenant PID still resolves.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("RoleService.findByPid tenant scoping (REG-7)")
class RoleFindByPidTenantScopeIT extends BaseIntegrationTest {

    @Autowired private RoleService roleService;
    @Autowired private JdbcTemplate jdbc;

    private Long sameTenantRoleId;
    private Long foreignRoleId;
    private String sameTenantRolePid;
    private String foreignRolePid;

    @BeforeEach
    void setup() {
        MetaContext.setContext(testTenant.getId(), testUser.getId(),
                testUser.getPid(), testUser.getUserName());
        // A real, existing tenant that is NOT the caller's (ab_role.tenant_id has FK fk_role_tenant).
        Long foreignTenantId = jdbc.queryForObject(
                "SELECT id FROM ab_tenant WHERE id <> ? AND deleted_flag = false ORDER BY id LIMIT 1",
                Long.class, testTenant.getId());
        assertThat(foreignTenantId).as("test needs a second tenant to exist").isNotNull();

        // pid is VARCHAR(26) UNIQUE — keep it short.
        sameTenantRolePid = "reg7s" + (System.nanoTime() % 1_000_000_000L);
        sameTenantRoleId = insertRole(testTenant.getId(), sameTenantRolePid);
        foreignRolePid = "reg7f" + (System.nanoTime() % 1_000_000_000L);
        foreignRoleId = insertRole(foreignTenantId, foreignRolePid);
    }

    @AfterEach
    void cleanup() {
        if (sameTenantRoleId != null) jdbc.update("DELETE FROM ab_role WHERE id = ?", sameTenantRoleId);
        if (foreignRoleId != null) jdbc.update("DELETE FROM ab_role WHERE id = ?", foreignRoleId);
        MetaContext.clear();
    }

    private long insertRole(Long tenantId, String pid) {
        long id = (System.currentTimeMillis() << 12) | (System.nanoTime() & 0xFFF);
        jdbc.update(
                "INSERT INTO ab_role (id, pid, name, code, type, scope_type, status, tenant_id, "
                        + " is_default, is_system, deleted_flag, priority, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'system', 'global', 'active', ?, false, true, false, 100, now(), now())",
                id, pid, "reg7_" + id, "reg7_" + id, tenantId);
        return id;
    }

    @Test
    @DisplayName("caller in tenant A: same-tenant PID resolves; foreign-tenant PID is null")
    void findByPid_isTenantScoped() {
        Role same = roleService.findByPid(sameTenantRolePid);
        assertThat(same).as("same-tenant role must resolve").isNotNull();
        assertThat(same.getId()).isEqualTo(sameTenantRoleId);
        assertThat(same.getTenantId()).isEqualTo(testTenant.getId());

        Role foreign = roleService.findByPid(foreignRolePid);
        assertThat(foreign).as("foreign-tenant role PID must NOT resolve under tenant A context").isNull();
    }
}
