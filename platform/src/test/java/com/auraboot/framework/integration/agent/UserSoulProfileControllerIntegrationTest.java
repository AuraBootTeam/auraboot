package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.controller.UserSoulProfileAdminController;
import com.auraboot.framework.agent.controller.UserSoulProfileController;
import com.auraboot.framework.agent.service.UserSoulProfileDeriver;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.auraboot.framework.integration.TestIdGenerator;

/**
 * PR-78 — {@link UserSoulProfileController} + {@link UserSoulProfileAdminController}.
 *
 * <p>Controller wires straight onto the tenant/user-scoped DB rows; only the
 * {@link UserSoulProfileDeriver} is mocked so {@code /derive-now} can exercise
 * the rate limiter without the full projection pipeline.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("UserSoulProfileController (PR-78)")
class UserSoulProfileControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private UserSoulProfileController controller;
    @Autowired private UserSoulProfileAdminController adminController;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private UserSoulProfileDeriver deriver;

    private Long tenantId;
    private String userId;

    private Long adminRoleId;
    private Long adminMemberId;

    @BeforeEach
    void setup() {
        tenantId = TestIdGenerator.uniqueTenantId();
        userId = testUser.getId().toString();
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
        reset(deriver);
        // Clear in-process rate limiter between tests.
        Object cache = ReflectionTestUtils.getField(controller, "deriveNowRateLimiter");
        if (cache != null) {
            try {
                cache.getClass().getMethod("invalidateAll").invoke(cache);
            } catch (Exception ignored) {
                // Best-effort; cache TTL is 24h but tests never rely on it expiring.
            }
        }
        // By default every test runs as tenant_admin for this fake tenantId.
        // Tests that need to verify the role-guard denial path call
        // revokeTenantAdmin() explicitly.
        grantTenantAdmin();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_user_soul_profile WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_user_soul_profile_admin_action WHERE tenant_id = ?",
                tenantId);
        revokeTenantAdmin();
    }

    /**
     * Insert a {@code tenant_admin} role + membership + assignment rows so the
     * admin guard (which queries {@code ab_tenant_member → ab_user_role → ab_role})
     * sees the current MetaContext user as admin in {@code tenantId}.
     */
    private void grantTenantAdmin() {
        if (adminRoleId != null) return;
        adminRoleId = System.nanoTime() & 0x7fffffffffffffffL;
        adminMemberId = (System.nanoTime() ^ 0xabcdL) & 0x7fffffffffffffffL;
        // ab_role has FK tenant_id → ab_tenant(id); ab_tenant_member too.
        // The test uses a synthetic tenantId that does not exist in ab_tenant,
        // so insert a throwaway ab_tenant row first. cleanup() cascades.
        jdbc.update("INSERT INTO ab_tenant (id, pid, name, status, deleted_flag) " +
                        "VALUES (?, ?, ?, 'active', FALSE) ON CONFLICT (id) DO NOTHING",
                tenantId, "tn_" + tenantId, "usp_test_" + tenantId);
        jdbc.update("INSERT INTO ab_role (id, pid, tenant_id, name, code, status, deleted_flag) " +
                        "VALUES (?, ?, ?, ?, 'tenant_admin', 'active', FALSE)",
                adminRoleId, "role_admin_" + adminRoleId, tenantId, "Tenant Admin " + adminRoleId);
        jdbc.update("INSERT INTO ab_tenant_member (id, pid, tenant_id, user_id, status, deleted_flag) " +
                        "VALUES (?, ?, ?, ?, 'active', FALSE)",
                adminMemberId, "mem_" + adminMemberId, tenantId, testUser.getId());
        jdbc.update("INSERT INTO ab_user_role (id, pid, member_id, tenant_id, role_id, status, deleted_flag) " +
                        "VALUES (?, ?, ?, ?, ?, 'active', FALSE)",
                System.nanoTime() & 0x7fffffffffffffffL,
                "ur_" + adminRoleId, adminMemberId, tenantId, adminRoleId);
    }

    private void revokeTenantAdmin() {
        if (adminRoleId == null) return;
        jdbc.update("DELETE FROM ab_user_role WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_role WHERE id = ?", adminRoleId);
        jdbc.update("DELETE FROM ab_tenant_member WHERE id = ?", adminMemberId);
        jdbc.update("DELETE FROM ab_tenant WHERE id = ? AND name LIKE 'usp_test_%'", tenantId);
        adminRoleId = null;
        adminMemberId = null;
    }

    // -----------------------------------------------------------------------
    // Seed helpers
    // -----------------------------------------------------------------------

    private String seedActive(Long tenant, String user, int version) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_user_soul_profile "
                        + "(pid, tenant_id, user_id, version, status, profile, profile_hash, "
                        + " derivation_confidence, activated_at, created_at) "
                        + "VALUES (?, ?, ?, ?, 'active', ?::jsonb, ?, 0.85, NOW(), NOW())",
                pid, tenant, user, version,
                "{\"persona\":{\"text\":\"engineer\"}}", "h:" + pid);
        return pid;
    }

    private String seedSuperseded(Long tenant, String user, int version) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_user_soul_profile "
                        + "(pid, tenant_id, user_id, version, status, profile, profile_hash, "
                        + " derivation_confidence, activated_at, superseded_at, created_at) "
                        + "VALUES (?, ?, ?, ?, 'superseded', ?::jsonb, ?, 0.80, NOW(), NOW(), NOW())",
                pid, tenant, user, version,
                "{\"persona\":{\"text\":\"older\"}}", "h:" + pid);
        return pid;
    }

    // =======================================================================
    // GET /
    // =======================================================================

    @Test
    @DisplayName("GET / returns ACTIVE profile for current user")
    void active_returnsProfile() {
        String pid = seedActive(tenantId, userId, 2);
        ApiResponse<Map<String, Object>> r = controller.active();
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData().get("pid")).isEqualTo(pid);
        assertThat(r.getData().get("version")).isEqualTo(2);
        assertThat(r.getData().get("status")).isEqualTo("active");
        // Controller parses profile::text → profile (object) and removes profile_json.
        assertThat(r.getData().get("profile_json")).as("raw JSON-string key should be removed").isNull();
        assertThat(r.getData().get("profile")).asString().contains("engineer");
    }

    @Test
    @DisplayName("GET / 404 when no ACTIVE row exists")
    void active_404WhenNone() {
        ApiResponse<Map<String, Object>> r = controller.active();
        assertThat(r.getCode()).isEqualTo("404");
    }

    @Test
    @DisplayName("GET / ignores rows for other users (no cross-user leak)")
    void active_ignoresOtherUsers() {
        seedActive(tenantId, "other_user_" + System.nanoTime(), 1);
        ApiResponse<Map<String, Object>> r = controller.active();
        assertThat(r.getCode()).isEqualTo("404");
    }

    // =======================================================================
    // GET /history
    // =======================================================================

    @Test
    @DisplayName("GET /history returns SUPERSEDED versions, excludes ACTIVE, metadata only")
    void history_shape() {
        seedActive(tenantId, userId, 3);
        String oldPid = seedSuperseded(tenantId, userId, 2);
        seedSuperseded(tenantId, userId, 1);

        ApiResponse<List<Map<String, Object>>> r = controller.history();
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData()).hasSize(2);
        // version DESC ordering
        assertThat(r.getData().get(0).get("pid")).isEqualTo(oldPid);
        // No content leak
        assertThat(r.getData().get(0)).doesNotContainKeys("profile_json", "edited_fields_json");
    }

    // =======================================================================
    // GET /{pid}
    // =======================================================================

    @Test
    @DisplayName("GET /{pid} returns own profile")
    void byPid_own() {
        String pid = seedActive(tenantId, userId, 1);
        ApiResponse<Map<String, Object>> r = controller.byPid(pid);
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData().get("pid")).isEqualTo(pid);
    }

    @Test
    @DisplayName("GET /{pid} 404 on cross-user access")
    void byPid_crossUser() {
        String otherUserPid = seedActive(tenantId, "other_user_" + System.nanoTime(), 1);
        ApiResponse<Map<String, Object>> r = controller.byPid(otherUserPid);
        assertThat(r.getCode()).isEqualTo("404");
    }

    @Test
    @DisplayName("GET /{pid} 404 on cross-tenant access")
    void byPid_crossTenant() {
        Long otherTenant = tenantId + 9_999L;
        String pid = seedActive(otherTenant, userId, 1);
        ApiResponse<Map<String, Object>> r = controller.byPid(pid);
        assertThat(r.getCode()).isEqualTo("404");
        // cleanup
        jdbc.update("DELETE FROM ab_agent_user_soul_profile WHERE tenant_id = ?", otherTenant);
    }

    @Test
    @DisplayName("GET /{pid} 404 for non-existent pid")
    void byPid_notFound() {
        assertThat(controller.byPid("01DOESNOTEXIST").getCode()).isEqualTo("404");
    }

    // =======================================================================
    // POST /pin /hide /edit /reset
    // =======================================================================

    @Test
    @DisplayName("POST /pin delegates to Editor and returns EditResult")
    void pin_delegatesToEditor() {
        String pid = seedActive(tenantId, userId, 1);
        ApiResponse<Map<String, Object>> r = controller.pin(Map.of("field", "persona"));
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData().get("pid")).isEqualTo(pid);
        @SuppressWarnings("unchecked")
        Map<String, Object> editedFields = (Map<String, Object>) r.getData().get("edited_fields");
        assertThat(editedFields).containsEntry("persona", "locked");
    }

    @Test
    @DisplayName("POST /pin 400 when field missing")
    void pin_missingField() {
        seedActive(tenantId, userId, 1);
        assertThat(controller.pin(Map.of()).getCode()).isEqualTo("400");
    }

    @Test
    @DisplayName("POST /pin 404 when no profile exists")
    void pin_noProfile_404() {
        assertThat(controller.pin(Map.of("field", "persona")).getCode()).isEqualTo("404");
    }

    @Test
    @DisplayName("POST /pin /hide /edit /reset return 409 when only SUPERSEDED rows exist")
    void edits_supersededOnly_409() {
        // Only a SUPERSEDED row — no ACTIVE/DRAFT. All four mutation endpoints
        // must surface IllegalStateException as HTTP 409 via the controller's
        // invokeEditor branch (which maps IllegalStateException → 409).
        seedSuperseded(tenantId, userId, 1);

        ApiResponse<Map<String, Object>> pin = controller.pin(Map.of("field", "persona"));
        assertThat(pin.getCode()).isEqualTo("409");
        assertThat(pin.getMessage()).contains("cannot edit superseded profile");

        ApiResponse<Map<String, Object>> hide = controller.hide(Map.of("field", "persona"));
        assertThat(hide.getCode()).isEqualTo("409");

        ApiResponse<Map<String, Object>> edit = controller.edit(Map.of("field", "persona", "text", "x"));
        assertThat(edit.getCode()).isEqualTo("409");

        ApiResponse<Map<String, Object>> reset = controller.reset(Map.of("field", "persona"));
        assertThat(reset.getCode()).isEqualTo("409");
    }

    @Test
    @DisplayName("POST /hide delegates to Editor")
    void hide_delegates() {
        seedActive(tenantId, userId, 1);
        ApiResponse<Map<String, Object>> r = controller.hide(Map.of("field", "preferences.communication_style"));
        assertThat(r.getCode()).isEqualTo("0");
        @SuppressWarnings("unchecked")
        Map<String, Object> editedFields = (Map<String, Object>) r.getData().get("edited_fields");
        assertThat(editedFields).containsEntry("preferences.communication_style", "hidden");
    }

    @Test
    @DisplayName("POST /edit requires both field and text")
    void edit_validation() {
        seedActive(tenantId, userId, 1);
        assertThat(controller.edit(Map.of("field", "persona")).getCode()).isEqualTo("400");
        assertThat(controller.edit(Map.of("text", "override")).getCode()).isEqualTo("400");
    }

    @Test
    @DisplayName("POST /edit stores override text")
    void edit_ok() {
        seedActive(tenantId, userId, 1);
        ApiResponse<Map<String, Object>> r = controller.edit(Map.of("field", "persona", "text", "product manager"));
        assertThat(r.getCode()).isEqualTo("0");
        @SuppressWarnings("unchecked")
        Map<String, Object> editedFields = (Map<String, Object>) r.getData().get("edited_fields");
        Object persona = editedFields.get("persona");
        assertThat(persona).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) persona;
        assertThat(entry).containsEntry("override_text", "product manager");
    }

    @Test
    @DisplayName("POST /reset with field removes only that key")
    void reset_field() {
        String pid = seedActive(tenantId, userId, 1);
        controller.pin(Map.of("field", "persona"));
        controller.pin(Map.of("field", "boundaries"));
        ApiResponse<Map<String, Object>> r = controller.reset(Map.of("field", "persona"));
        assertThat(r.getCode()).isEqualTo("0");
        @SuppressWarnings("unchecked")
        Map<String, Object> editedFields = (Map<String, Object>) r.getData().get("edited_fields");
        assertThat(editedFields).doesNotContainKey("persona").containsKey("boundaries");
        assertThat(r.getData().get("pid")).isEqualTo(pid);
    }

    @Test
    @DisplayName("POST /reset without body clears all overrides")
    void reset_all() {
        seedActive(tenantId, userId, 1);
        controller.pin(Map.of("field", "persona"));
        controller.pin(Map.of("field", "boundaries"));
        ApiResponse<Map<String, Object>> r = controller.reset(null);
        assertThat(r.getCode()).isEqualTo("0");
        @SuppressWarnings("unchecked")
        Map<String, Object> editedFields = (Map<String, Object>) r.getData().get("edited_fields");
        assertThat(editedFields).isEmpty();
    }

    // =======================================================================
    // POST /hide-profile
    // =======================================================================

    @Test
    @DisplayName("POST /hide-profile sets hidden_at")
    void hideProfile() {
        String pid = seedActive(tenantId, userId, 1);
        ApiResponse<Map<String, Object>> r = controller.hideProfile();
        assertThat(r.getCode()).isEqualTo("0");
        Object hiddenAt = jdbc.queryForMap(
                "SELECT hidden_at FROM ab_agent_user_soul_profile WHERE pid = ?", pid)
                .get("hidden_at");
        assertThat(hiddenAt).isNotNull();
    }

    // =======================================================================
    // POST /forget — idempotent cascade
    // =======================================================================

    @Test
    @DisplayName("POST /forget archives all versions")
    void forget_cascades() {
        seedActive(tenantId, userId, 1);
        ApiResponse<Map<String, Object>> r = controller.forget();
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData().get("noop")).isEqualTo(false);
        Long archivedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile "
                        + "WHERE tenant_id = ? AND user_id = ? AND status = 'archived'",
                Long.class, tenantId, userId);
        assertThat(archivedCount).isGreaterThanOrEqualTo(2L); // original archived + tombstone
    }

    @Test
    @DisplayName("POST /forget is idempotent when no profile exists (noop)")
    void forget_idempotentNoRows() {
        ApiResponse<Map<String, Object>> r = controller.forget();
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData().get("noop")).isEqualTo(true);
    }

    @Test
    @DisplayName("POST /forget twice: both calls succeed")
    void forget_idempotentTwice() {
        seedActive(tenantId, userId, 1);
        assertThat(controller.forget().getCode()).isEqualTo("0");
        // Second call: Editor finds archived rows and inserts a second tombstone, still 200
        assertThat(controller.forget().getCode()).isEqualTo("0");
    }

    // =======================================================================
    // POST /derive-now — rate limited
    // =======================================================================

    @Test
    @DisplayName("POST /derive-now first call triggers deriver; second call within 24h → 429")
    void deriveNow_rateLimited() {
        when(deriver.deriveForUser(eq(tenantId), any()))
                .thenReturn(new UserSoulProfileDeriver.DerivationResult(
                        UserSoulProfileDeriver.Outcome.DRAFTED, "pid_d1", "hash1"));

        ApiResponse<Map<String, Object>> first = controller.deriveNow();
        assertThat(first.getCode()).isEqualTo("0");
        assertThat(first.getData().get("outcome")).isEqualTo("DRAFTED");
        assertThat(first.getData().get("profile_pid")).isEqualTo("pid_d1");

        ApiResponse<Map<String, Object>> second = controller.deriveNow();
        assertThat(second.getCode()).isEqualTo("429");
        assertThat(second.getContext()).isNotNull();

        verify(deriver, times(1)).deriveForUser(eq(tenantId), any());
    }

    // =======================================================================
    // Admin endpoints — GET /list, /stats
    // =======================================================================

    @Test
    @DisplayName("Admin GET / returns metadata only (no content columns)")
    void admin_listMetadataOnly() {
        seedActive(tenantId, userId, 1);
        seedActive(tenantId, "other_" + System.nanoTime(), 1);

        ApiResponse<List<Map<String, Object>>> r = adminController.list(50);
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData()).hasSizeGreaterThanOrEqualTo(2);
        for (Map<String, Object> row : r.getData()) {
            // Explicit no-content assertion
            assertThat(row).doesNotContainKeys(
                    "profile", "profile_json", "edited_fields", "edited_fields_json", "source_memory_pids");
            assertThat(row).containsKeys("pid", "user_id", "version", "status");
        }
    }

    @Test
    @DisplayName("Admin GET / is tenant-scoped")
    void admin_listTenantScoped() {
        seedActive(tenantId, userId, 1);
        Long otherTenant = tenantId + 9_999L;
        seedActive(otherTenant, userId, 1);

        ApiResponse<List<Map<String, Object>>> r = adminController.list(50);
        for (Map<String, Object> row : r.getData()) {
            // All rows returned must belong to the current tenant. We probe
            // by pid existence in the current tenant table.
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ab_agent_user_soul_profile "
                            + "WHERE pid = ? AND tenant_id = ?",
                    Long.class, row.get("pid"), tenantId);
            assertThat(count).isEqualTo(1L);
        }

        jdbc.update("DELETE FROM ab_agent_user_soul_profile WHERE tenant_id = ?", otherTenant);
    }

    // =======================================================================
    // PR-81 Phase 9 — GET /export
    // =======================================================================

    @Test
    @DisplayName("GET /export returns all versions (ACTIVE + SUPERSEDED + ARCHIVED) for current user")
    void export_allVersions() throws Exception {
        seedActive(tenantId, userId, 3);
        seedSuperseded(tenantId, userId, 2);
        seedSuperseded(tenantId, userId, 1);

        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        controller.export(response);

        String body = response.getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = mapper.readValue(body, Map.class);
        assertThat(payload.get("user_id")).isEqualTo(userId);
        assertThat(((Number) payload.get("row_count")).intValue()).isEqualTo(3);
        assertThat(payload).containsKey("profiles");
        assertThat(payload.get("schema_version")).isEqualTo("1.0");
        // Content included for self-export (GDPR).
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) payload.get("profiles");
        assertThat(profiles).hasSize(3);
        assertThat(profiles.get(0)).containsKey("profile");
    }

    @Test
    @DisplayName("GET /export excludes rows for other users (no cross-user leak)")
    void export_isolatedPerUser() throws Exception {
        seedActive(tenantId, userId, 1);
        seedActive(tenantId, "other_user_" + System.nanoTime(), 1);

        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        controller.export(response);

        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = mapper.readValue(response.getContentAsString(), Map.class);
        assertThat(((Number) payload.get("row_count")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /export sets Content-Disposition attachment header")
    void export_contentDispositionAttachment() throws Exception {
        seedActive(tenantId, userId, 1);
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        controller.export(response);
        String disposition = response.getHeader("Content-Disposition");
        assertThat(disposition).isNotNull();
        assertThat(disposition).startsWith("attachment;");
        assertThat(disposition).contains("user-soul-profile-");
        assertThat(disposition).endsWith(".json\"");
        assertThat(response.getContentType()).contains("application/json");
    }

    // =======================================================================
    // PR-81 Phase 9 — admin POST /forget
    // =======================================================================

    @Test
    @DisplayName("Admin POST /forget archives target user's rows + inserts tombstone")
    void adminForget_cascade() {
        String victim = "victim_" + System.nanoTime();
        seedActive(tenantId, victim, 1);

        ApiResponse<Map<String, Object>> r = adminController.forget(
                Map.of("userId", victim, "reason", "gdpr_request"));

        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData().get("noop")).isEqualTo(false);
        assertThat(r.getData().get("target_user_id")).isEqualTo(victim);
        assertThat(r.getData().get("reason")).isEqualTo("gdpr_request");
        assertThat(r.getData().get("status")).isEqualTo("archived");

        Long archivedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile "
                        + "WHERE tenant_id = ? AND user_id = ? AND status = 'archived'",
                Long.class, tenantId, victim);
        assertThat(archivedCount).isGreaterThanOrEqualTo(2L);

        Long tombstoneCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile "
                        + "WHERE tenant_id = ? AND user_id = ? "
                        + "  AND status = 'archived' "
                        + "  AND (edited_fields ->> '_forgotten') = 'true'",
                Long.class, tenantId, victim);
        assertThat(tombstoneCount).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("Admin POST /forget rejects missing userId / reason")
    void adminForget_requiresFields() {
        assertThat(adminController.forget(Map.of("reason", "gdpr_request")).getCode()).isEqualTo("400");
        assertThat(adminController.forget(Map.of("userId", "42")).getCode()).isEqualTo("400");
        assertThat(adminController.forget(Map.of()).getCode()).isEqualTo("400");
    }

    @Test
    @DisplayName("Admin POST /forget is idempotent when target has no rows (noop)")
    void adminForget_noopWhenNoRows() {
        String ghost = "ghost_" + System.nanoTime();
        ApiResponse<Map<String, Object>> r = adminController.forget(
                Map.of("userId", ghost, "reason", "account_closed"));
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData().get("noop")).isEqualTo(true);
        assertThat(r.getData().get("target_user_id")).isEqualTo(ghost);
    }

    @Test
    @DisplayName("Admin POST /forget does not leak profile content in response")
    void adminForget_noContentLeak() {
        String victim = "victim_" + System.nanoTime();
        seedActive(tenantId, victim, 1);
        ApiResponse<Map<String, Object>> r = adminController.forget(
                Map.of("userId", victim, "reason", "policy_violation"));
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData()).doesNotContainKeys(
                "profile", "profile_json", "edited_fields", "edited_fields_json", "source_memory_pids");
    }

    @Test
    @DisplayName("Admin GET /stats shape: active_count, stale_count, avg_confidence, by_status")
    void admin_statsShape() {
        seedActive(tenantId, userId, 1);
        // Flag the profile as stale
        jdbc.update("UPDATE ab_agent_user_soul_profile "
                + "SET stale_flagged_at = NOW() WHERE tenant_id = ? AND user_id = ?",
                tenantId, userId);

        ApiResponse<Map<String, Object>> r = adminController.stats();
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData()).containsKeys("active_count", "stale_count", "avg_confidence", "by_status");
        assertThat(((Number) r.getData().get("active_count")).longValue()).isEqualTo(1L);
        assertThat(((Number) r.getData().get("stale_count")).longValue()).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        Map<String, Long> byStatus = (Map<String, Long>) r.getData().get("by_status");
        assertThat(byStatus.get("active")).isEqualTo(1L);
    }

    // =======================================================================
    // Round-2 #1 — Admin role guard
    //
    // The per-controller guardTenantAdmin() helper was removed on 2026-04-19
    // when the platform-wide AdminRoleInterceptor took over every
    // /api/admin/** URL. Guard behaviour (admin pass / non-admin 409) is now
    // covered by AdminRoleInterceptorIntegrationTest + the per-controller
    // UserSoulProfileAdminGuardIntegrationTest — see design doc
    // docs/plans/2026-04/2026-04-19-platform-admin-guard-design.md.
    //
    // The two tests that used to call adminController.list/stats/forget
    // directly to exercise the in-method guard were removed because direct
    // in-process invocation deliberately bypasses the servlet interceptor,
    // making them unable to verify the new mechanism. The admin happy-path
    // behaviour (content shape, cross-tenant isolation, audit rows, etc.)
    // remains covered by the other admin_* tests above, which still call
    // the controller directly with tenant_admin granted in setup().
    // =======================================================================

    // =======================================================================
    // Round-2 #2 — DB audit row for admin-forget
    // =======================================================================

    @Test
    @DisplayName("Admin POST /forget inserts an audit row with action=admin_forget")
    void adminForget_insertsAuditRow() {
        String victim = "victim_audit_" + System.nanoTime();
        seedActive(tenantId, victim, 1);

        ApiResponse<Map<String, Object>> r = adminController.forget(
                Map.of("userId", victim, "reason", "gdpr_request"));
        assertThat(r.getCode()).isEqualTo("0");

        List<Map<String, Object>> audits = jdbc.queryForList(
                "SELECT acting_admin_id, target_user_id, action, reason " +
                        "FROM ab_agent_user_soul_profile_admin_action " +
                        "WHERE tenant_id = ? AND target_user_id = ?",
                tenantId, victim);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0))
                .containsEntry("target_user_id", victim)
                .containsEntry("action", "admin_forget")
                .containsEntry("reason", "gdpr_request")
                .containsEntry("acting_admin_id", testUser.getId().toString());
    }

    // =======================================================================
    // Round-2 #3 — Cross-tenant isolation: noop, no metric, no audit
    // =======================================================================

    @Test
    @DisplayName("Admin POST /forget on cross-tenant user: noop, no audit row, untouched")
    void adminForget_crossTenantIsolation() {
        // Seed a profile in a completely different tenant.
        Long otherTenant = tenantId + 7_777L;
        String remoteUser = "remote_" + System.nanoTime();
        seedActive(otherTenant, remoteUser, 1);

        // Admin of tenantId attempts forget on remoteUser — which has zero
        // rows in tenantId. Editor throws IllegalArgumentException → noop.
        ApiResponse<Map<String, Object>> r = adminController.forget(
                Map.of("userId", remoteUser, "reason", "gdpr_request"));
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData().get("noop")).isEqualTo(true);

        // No audit row leaked into tenantId.
        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile_admin_action " +
                        "WHERE tenant_id = ? AND target_user_id = ?",
                Long.class, tenantId, remoteUser);
        assertThat(auditCount).isEqualTo(0L);

        // Remote tenant's row is untouched (still active).
        Long remoteActive = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile " +
                        "WHERE tenant_id = ? AND user_id = ? AND status = 'active'",
                Long.class, otherTenant, remoteUser);
        assertThat(remoteActive).isEqualTo(1L);

        // cleanup cross-tenant seed
        jdbc.update("DELETE FROM ab_agent_user_soul_profile WHERE tenant_id = ?", otherTenant);
    }

    // =======================================================================
    // Round-2 #4 — Content-Disposition filename sanitization
    // =======================================================================

    @Test
    @DisplayName("sanitizeFilenameToken replaces CRLF / special chars and clips to 64")
    void sanitizeFilenameToken_injection() {
        // Reflective access to the package-private static helper.
        String out = UserSoulProfileController.sanitizeFilenameToken("foo\r\nX-Evil: y");
        assertThat(out).doesNotContain("\r").doesNotContain("\n").doesNotContain(":");
        // Every non-[A-Za-z0-9_.-] char replaced by underscore.
        assertThat(out).isEqualTo("foo__X-Evil__y");

        String clipped = UserSoulProfileController.sanitizeFilenameToken(
                "a".repeat(200));
        assertThat(clipped).hasSize(64);

        assertThat(UserSoulProfileController.sanitizeFilenameToken(null)).isEqualTo("anon");
    }

    // =======================================================================
    // Round-2 #5 — 409 (SUPERSEDED) vs 410 (ARCHIVED)
    // =======================================================================

    @Test
    @DisplayName("POST /pin on SUPERSEDED-only profile returns 409")
    void edits_supersededOnly_409_distinct() {
        seedSuperseded(tenantId, userId, 1);
        ApiResponse<Map<String, Object>> r = controller.pin(Map.of("field", "persona"));
        assertThat(r.getCode()).isEqualTo("409");
        assertThat(r.getMessage()).contains("superseded");
    }

    @Test
    @DisplayName("POST /pin on ARCHIVED-only profile returns 410 Gone")
    void edits_archivedOnly_410() {
        // Seed then archive via forgetProfile cascade.
        seedActive(tenantId, userId, 1);
        controller.forget();
        // At this point every row for (tenantId, userId) is ARCHIVED.
        ApiResponse<Map<String, Object>> pin = controller.pin(Map.of("field", "persona"));
        assertThat(pin.getCode()).isEqualTo("410");
        assertThat(pin.getMessage()).contains("archived");

        ApiResponse<Map<String, Object>> hide = controller.hide(Map.of("field", "persona"));
        assertThat(hide.getCode()).isEqualTo("410");

        ApiResponse<Map<String, Object>> edit = controller.edit(Map.of("field", "persona", "text", "x"));
        assertThat(edit.getCode()).isEqualTo("410");

        ApiResponse<Map<String, Object>> reset = controller.reset(Map.of("field", "persona"));
        assertThat(reset.getCode()).isEqualTo("410");
    }
}
