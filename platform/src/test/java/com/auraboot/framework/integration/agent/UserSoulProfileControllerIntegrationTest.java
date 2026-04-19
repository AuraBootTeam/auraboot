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

    @BeforeEach
    void setup() {
        tenantId = 9_810_000L + System.nanoTime() % 10_000;
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
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_user_soul_profile WHERE tenant_id = ?", tenantId);
    }

    // -----------------------------------------------------------------------
    // Seed helpers
    // -----------------------------------------------------------------------

    private String seedActive(Long tenant, String user, int version) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_user_soul_profile "
                        + "(pid, tenant_id, user_id, version, status, profile, profile_hash, "
                        + " derivation_confidence, activated_at, created_at) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE', ?::jsonb, ?, 0.85, NOW(), NOW())",
                pid, tenant, user, version,
                "{\"persona\":{\"text\":\"engineer\"}}", "h:" + pid);
        return pid;
    }

    private String seedSuperseded(Long tenant, String user, int version) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_user_soul_profile "
                        + "(pid, tenant_id, user_id, version, status, profile, profile_hash, "
                        + " derivation_confidence, activated_at, superseded_at, created_at) "
                        + "VALUES (?, ?, ?, ?, 'SUPERSEDED', ?::jsonb, ?, 0.80, NOW(), NOW(), NOW())",
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
        assertThat(r.getData().get("status")).isEqualTo("ACTIVE");
        assertThat(r.getData().get("profile_json")).asString().contains("engineer");
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
                        + "WHERE tenant_id = ? AND user_id = ? AND status = 'ARCHIVED'",
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
        assertThat(r.getData().get("status")).isEqualTo("ARCHIVED");

        Long archivedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile "
                        + "WHERE tenant_id = ? AND user_id = ? AND status = 'ARCHIVED'",
                Long.class, tenantId, victim);
        assertThat(archivedCount).isGreaterThanOrEqualTo(2L);

        Long tombstoneCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile "
                        + "WHERE tenant_id = ? AND user_id = ? "
                        + "  AND status = 'ARCHIVED' "
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
        assertThat(byStatus.get("ACTIVE")).isEqualTo(1L);
    }
}
