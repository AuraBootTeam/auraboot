package com.auraboot.framework.integration.security.rbac;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 2 of the RBAC golden suite ({@code rbac-golden-and-cross-cutting-regression.md} §6):
 * per-code enforcement of the platform-baseline access matrix, against a REAL bootstrapped tenant.
 *
 * <p>Where Slice 1's drift gate ({@link RbacAccessMatrixConsistencyTest}) statically checks the SOT
 * matrix against {@code default-bootstrap.json}, this IT proves the running system actually enforces
 * it. A bare integration-test DB does NOT have the permission codes registered (they are seeded per
 * tenant at bootstrap — Slice 1 pitfall), so we bootstrap a fresh tenant via
 * {@link TenantBootstrapService} (which registers the codes + {@code tenant_admin} / {@code tenant_member}
 * roles + their bindings from {@code default-bootstrap.json}), then check every {@code (role, code)} cell
 * of the matrix through the SAME resolution API the {@code PermissionInterceptor} uses:
 * {@link UserPermissionService#hasPermission}.
 *
 * <ul>
 *   <li>{@code tenant_member} — a role-LESS member (no {@code ab_user_role} row) must resolve every
 *       {@code allow} code via the L1 implicit baseline, and must NOT resolve any {@code deny} code.</li>
 *   <li>{@code tenant_admin} — the bootstrapped admin (bound to the {@code "*"} wildcard) must resolve
 *       every matrix code (allow ∪ deny): everything a member is denied, an admin still has.</li>
 * </ul>
 *
 * <p>Runs on a fresh unique tenant with {@code @Commit / propagation = NEVER} (permission resolution
 * reads committed data, mirroring
 * {@code com.auraboot.framework.integration.security.TenantMemberBaselineResolutionIT}) and tears the
 * tenant down completely in {@link #cleanup()} — the shared test tenant is never mutated.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("RBAC per-code enforcement matches the SOT matrix (platform-baseline, Slice 2)")
class RbacEnforcementMatrixIT extends BaseIntegrationTest {

    private static final String DEPLOYMENT = "platform-baseline";

    @Autowired private TenantBootstrapService tenantBootstrapService;
    @Autowired private UserPermissionService userPermissionService;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private Long adminUserId;    // bootstrap creator -> bound to tenant_admin
    private Long adminMemberId;
    private Long memberUserId;   // role-less member -> tenant_member baseline (implicit L1)
    private Long memberMemberId;

    @BeforeEach
    void setup() {
        tenantId = TestIdGenerator.uniqueTenantId();
        adminUserId = testUser.getId();           // a real ab_user row (FK-safe bootstrap creator)
        memberUserId = TestIdGenerator.uniqueUserId();

        // ab_tenant + the creator's ab_tenant_member must exist before bootstrap:
        // assignUserRole(creator -> tenant_admin) fails closed if the member row is missing.
        jdbc.update("INSERT INTO ab_tenant (id, pid, name, status, deleted_flag) "
                        + "VALUES (?, ?, ?, 'active', FALSE) ON CONFLICT (id) DO NOTHING",
                tenantId, "tn_" + tenantId, "rbac_enf_" + tenantId);
        adminMemberId = insertMember(adminUserId);
        // A real, role-LESS member (has a tenant_member row, but NO ab_user_role) to exercise the
        // L1 implicit baseline exactly as production does.
        memberMemberId = insertMember(memberUserId);

        // Bootstrap the tenant from default-bootstrap.json: registers permission codes, the
        // tenant_admin + tenant_member roles, their bindings, and binds the creator to tenant_admin.
        TenantBootstrapService.BootstrapResult result =
                tenantBootstrapService.bootstrapTenant(tenantId, adminUserId);
        assertThat(result.isSuccess())
                .as("tenant bootstrap must succeed: %s", result.getMessage())
                .isTrue();

        userPermissionService.evictUserPermissions(adminUserId);
        userPermissionService.evictUserPermissions(memberUserId);
    }

    private Long insertMember(Long userId) {
        long memberId = System.nanoTime() & 0x7fffffffffffffffL;
        jdbc.update("INSERT INTO ab_tenant_member (id, pid, tenant_id, user_id, status, deleted_flag) "
                        + "VALUES (?, ?, ?, ?, 'active', FALSE)",
                memberId, UniqueIdGenerator.generate(), tenantId, userId);
        return memberId;
    }

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            jdbc.update("DELETE FROM ab_user_role WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_permission WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_role WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_agent_definition WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_menu WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_tenant_member WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_tenant WHERE id = ?", tenantId);
        }
        if (adminUserId != null) userPermissionService.evictUserPermissions(adminUserId);
        if (memberUserId != null) userPermissionService.evictUserPermissions(memberUserId);
        MetaContext.clear();
    }

    @Test
    @DisplayName("tenant_member resolves every allow code and no deny code; tenant_admin resolves all")
    void platformBaseline_perCodeEnforcement_matchesMatrix() {
        RbacAccessMatrix matrix = RbacAccessMatrix.load();
        RbacAccessMatrix.RoleEntry member = matrix.role(DEPLOYMENT, "tenant_member");
        RbacAccessMatrix.RoleEntry admin = matrix.role(DEPLOYMENT, "tenant_admin");

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(admin.isWildcardAllow())
                .as("matrix declares tenant_admin as wildcard allow (drives the admin-resolves-all check)")
                .isTrue();

        // --- tenant_member: role-less member, implicit L1 baseline ---
        MetaContext.setContext(tenantId, memberUserId, "u-mem-" + memberUserId, "rbac-member");
        MetaContext.setMemberId(memberMemberId);
        userPermissionService.evictUserPermissions(memberUserId);
        for (String code : member.allow()) {
            softly.assertThat(userPermissionService.hasPermission(memberUserId, code))
                    .as("tenant_member (L1 baseline) MUST resolve allow code '%s'", code)
                    .isTrue();
        }
        for (String code : member.deny()) {
            softly.assertThat(userPermissionService.hasPermission(memberUserId, code))
                    .as("tenant_member MUST NOT resolve deny code '%s'", code)
                    .isFalse();
        }
        MetaContext.clear();

        // --- tenant_admin: wildcard — must have everything a member has PLUS the denied assignment codes ---
        MetaContext.setContext(tenantId, adminUserId, testUser.getPid(), testUser.getUserName());
        MetaContext.setMemberId(adminMemberId);
        userPermissionService.evictUserPermissions(adminUserId);
        List<String> allCodes = new ArrayList<>(member.allow());
        allCodes.addAll(member.deny());
        for (String code : allCodes) {
            softly.assertThat(userPermissionService.hasPermission(adminUserId, code))
                    .as("tenant_admin (wildcard) MUST resolve code '%s'", code)
                    .isTrue();
        }
        MetaContext.clear();

        softly.assertAll();
    }
}
