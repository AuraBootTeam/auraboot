package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.crosstenant.CrossTenantAclService;
import com.auraboot.framework.agent.crosstenant.CrossTenantDecision;
import com.auraboot.framework.agent.crosstenant.CrossTenantGrantController;
import com.auraboot.framework.agent.crosstenant.CrossTenantGrantType;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C.2 — verifies the {@link CrossTenantGrantController} REST surface:
 * list / create / revoke / audit. Uses direct controller invocation
 * (matches {@code LearningLoopControllerIntegrationTest} convention; no
 * MockMvc layer).
 *
 * <p>Cases:
 * <ul>
 *   <li>A — non-platform-admin: every endpoint returns 403 envelope.</li>
 *   <li>B — platform admin can create grant + read it back.</li>
 *   <li>C — platform admin can revoke grant; revoked_at + revoked_by are
 *           populated; cache invalidated (verified via service.allows
 *           returning false post-revoke).</li>
 *   <li>D — list endpoint paginates correctly; activeOnly=false includes
 *           revoked rows.</li>
 *   <li>E — audit endpoint returns rows for the grant id.</li>
 * </ul>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("CrossTenantGrantController (C.2)")
class CrossTenantGrantControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private CrossTenantGrantController controller;
    @Autowired private CrossTenantAclService aclService;
    @Autowired private JdbcTemplate jdbc;

    private Long parentTenant;
    private Long childTenant;
    private Long platformAdminRoleId;

    @BeforeEach
    void setup() {
        long base = 9_820_000L + System.nanoTime() % 100_000;
        parentTenant = base;
        childTenant = base + 1L;
        // Caller MetaContext = test user in test tenant.
        MetaContext.setContext(testTenant.getId(), testUser.getId(),
                testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        // Drop platform_admin role grant we inserted. Only drop the role row
        // itself when this test created it; pre-existing rows belong to
        // some prior bootstrap and must not be deleted.
        if (platformAdminRoleId != null) {
            jdbc.update("DELETE FROM ab_user_role WHERE member_id = ? AND role_id = ? AND tenant_id = ?",
                    testTenantMember.getId(), platformAdminRoleId, testTenant.getId());
            if (!roleWasPreexisting) {
                jdbc.update("DELETE FROM ab_role WHERE id = ?", platformAdminRoleId);
            }
            platformAdminRoleId = null;
            roleWasPreexisting = false;
        }
        jdbc.update("DELETE FROM ab_cross_tenant_spawn_audit "
                        + "WHERE parent_tenant_id IN (?, ?) OR child_tenant_id IN (?, ?)",
                parentTenant, childTenant, parentTenant, childTenant);
        jdbc.update("DELETE FROM ab_cross_tenant_grant WHERE parent_tenant_id IN (?, ?)",
                parentTenant, childTenant);
        aclService.invalidate(parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);
        MetaContext.clear();
    }

    private boolean roleWasPreexisting;

    /** Grant testUser the platform_admin role in the test tenant. */
    private void grantPlatformAdmin() {
        // A previous run may have left the role around (BaseIntegrationTest
        // uses a long-lived testTenant). Look it up first; only insert when
        // missing. Track whether we created it so cleanup can decide.
        java.util.List<Long> existing = jdbc.queryForList(
                "SELECT id FROM ab_role WHERE tenant_id = ? AND code = ? "
                        + "AND (deleted_flag = false OR deleted_flag IS NULL) LIMIT 1",
                Long.class, testTenant.getId(), RoleCodes.PLATFORM_ADMIN);
        if (!existing.isEmpty()) {
            platformAdminRoleId = existing.get(0);
            roleWasPreexisting = true;
        } else {
            // ab_role.id is ASSIGN_ID at the MyBatis-Plus layer (no DB
            // default), so a raw JdbcTemplate INSERT must supply an id.
            // Use a millis+nanos blend to stay unique inside the test run.
            long roleId = (System.currentTimeMillis() << 12) | (System.nanoTime() & 0xFFF);
            jdbc.update(
                    "INSERT INTO ab_role (id, pid, name, code, type, scope_type, status, "
                            + " tenant_id, is_default, is_system, deleted_flag, priority, "
                            + " created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'system', 'global', 'active', ?, false, true, false, 1, now(), now())",
                    roleId,
                    "rl_pa_" + System.nanoTime(),
                    "platform_admin",
                    RoleCodes.PLATFORM_ADMIN,
                    testTenant.getId());
            platformAdminRoleId = roleId;
            roleWasPreexisting = false;
        }

        // Bind to testUser via testTenantMember (idempotent: skip if exists).
        Long alreadyBound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_user_role WHERE member_id = ? AND role_id = ? "
                        + "AND tenant_id = ? AND status = 'active' "
                        + "AND (deleted_flag = false OR deleted_flag IS NULL)",
                Long.class, testTenantMember.getId(), platformAdminRoleId, testTenant.getId());
        if (alreadyBound == null || alreadyBound == 0L) {
            long urId = (System.currentTimeMillis() << 12) | ((System.nanoTime() + 1) & 0xFFF);
            jdbc.update("INSERT INTO ab_user_role (id, pid, member_id, role_id, tenant_id, "
                            + " status, deleted_flag, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'active', false, now(), now())",
                    urId,
                    "ur_pa_" + System.nanoTime(),
                    testTenantMember.getId(),
                    platformAdminRoleId,
                    testTenant.getId());
        }
    }

    private CrossTenantGrantController.CreateGrantRequest makeRequest() {
        CrossTenantGrantController.CreateGrantRequest req =
                new CrossTenantGrantController.CreateGrantRequest();
        req.parentTenantId = parentTenant;
        req.childTenantId = childTenant;
        req.grantType = CrossTenantGrantType.SPAWN_SUB_AGENT;
        req.note = "integration test grant";
        return req;
    }

    @Test
    @DisplayName("A: non-platform-admin gets 403 on every endpoint")
    void caseA_non_platform_admin_denied() {
        // testUser does NOT have platform_admin role in the test tenant.
        ApiResponse<Map<String, Object>> listResp = controller.list(1, 20, true);
        assertThat(listResp.getCode()).isEqualTo("403");

        ApiResponse<Map<String, Object>> createResp = controller.create(makeRequest());
        assertThat(createResp.getCode()).isEqualTo("403");

        ApiResponse<Map<String, Object>> revokeResp = controller.revoke(99999L);
        assertThat(revokeResp.getCode()).isEqualTo("403");

        ApiResponse<Map<String, Object>> auditResp = controller.audit(99999L, 1, 50);
        assertThat(auditResp.getCode()).isEqualTo("403");
    }

    @Test
    @DisplayName("B: platform admin can create + list active grants")
    void caseB_create_and_list() {
        grantPlatformAdmin();

        ApiResponse<Map<String, Object>> created = controller.create(makeRequest());
        assertThat(created.getCode()).isEqualTo("0");
        assertThat(created.getData().get("id")).isNotNull();
        Long grantId = ((Number) created.getData().get("id")).longValue();

        // List should include the row.
        ApiResponse<Map<String, Object>> listed = controller.list(1, 50, true);
        assertThat(listed.getCode()).isEqualTo("0");
        java.util.List<Map<String, Object>> records =
                (java.util.List<Map<String, Object>>) listed.getData().get("records");
        boolean found = records.stream()
                .anyMatch(r -> ((Number) r.get("id")).longValue() == grantId);
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("C: revoke flips revoked_at + invalidates cache")
    void caseC_revoke_invalidates_cache() {
        grantPlatformAdmin();

        ApiResponse<Map<String, Object>> created = controller.create(makeRequest());
        Long grantId = ((Number) created.getData().get("id")).longValue();

        // Pre-revoke: ACL allows.
        assertThat(aclService.evaluate(parentTenant, childTenant,
                CrossTenantGrantType.SPAWN_SUB_AGENT).code())
                .isEqualTo(CrossTenantDecision.ALLOWED);

        ApiResponse<Map<String, Object>> revoked = controller.revoke(grantId);
        assertThat(revoked.getCode()).isEqualTo("0");

        // Post-revoke: ACL denies (cache invalidated by controller).
        assertThat(aclService.evaluate(parentTenant, childTenant,
                CrossTenantGrantType.SPAWN_SUB_AGENT).code())
                .isEqualTo(CrossTenantDecision.DENIED_NO_GRANT);

        // DB row carries revoked_at + revoked_by.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT revoked_at, revoked_by FROM ab_cross_tenant_grant WHERE id = ?",
                grantId);
        assertThat(row.get("revoked_at")).isNotNull();
        assertThat(((Number) row.get("revoked_by")).longValue()).isEqualTo(testUser.getId());
    }

    @Test
    @DisplayName("D: list activeOnly=false includes revoked rows")
    void caseD_list_includes_revoked_when_activeOnly_false() {
        grantPlatformAdmin();
        Long grantId = ((Number) controller.create(makeRequest()).getData().get("id")).longValue();
        controller.revoke(grantId);

        ApiResponse<Map<String, Object>> activeOnly = controller.list(1, 50, true);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> activeRows =
                (java.util.List<Map<String, Object>>) activeOnly.getData().get("records");
        boolean foundInActive = activeRows.stream()
                .anyMatch(r -> ((Number) r.get("id")).longValue() == grantId);
        assertThat(foundInActive).isFalse();

        ApiResponse<Map<String, Object>> all = controller.list(1, 50, false);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> allRows =
                (java.util.List<Map<String, Object>>) all.getData().get("records");
        boolean foundInAll = allRows.stream()
                .anyMatch(r -> ((Number) r.get("id")).longValue() == grantId);
        assertThat(foundInAll).isTrue();
    }

    @Test
    @DisplayName("E: audit endpoint returns rows for grant id")
    void caseE_audit_returns_rows() {
        grantPlatformAdmin();
        Long grantId = ((Number) controller.create(makeRequest()).getData().get("id")).longValue();
        // Seed an audit row directly (we don't have a parent run here).
        jdbc.update("INSERT INTO ab_cross_tenant_spawn_audit "
                        + "(grant_id, parent_tenant_id, child_tenant_id, parent_run_pid, "
                        + " child_run_pid, decision, spawn_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, now())",
                grantId, parentTenant, childTenant,
                "pid_parent_" + System.nanoTime(),
                "pid_child_" + System.nanoTime(),
                CrossTenantDecision.ALLOWED);

        ApiResponse<Map<String, Object>> audit = controller.audit(grantId, 1, 50);
        assertThat(audit.getCode()).isEqualTo("0");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> rows =
                (java.util.List<Map<String, Object>>) audit.getData().get("records");
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).get("decision")).isEqualTo(CrossTenantDecision.ALLOWED);
    }
}
