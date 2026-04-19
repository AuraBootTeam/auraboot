package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.UserSoulProfileDeriver;
import com.auraboot.framework.agent.service.UserSoulProfileEditor;
import com.auraboot.framework.agent.service.UserSoulProfileEditor.EditResult;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PR-76 Phase 2 — {@link UserSoulProfileEditor}. */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("User Soul Profile editor (PR-76)")
class UserSoulProfileEditorIntegrationTest extends BaseIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserSoulProfileEditor editor;
    @Autowired private UserSoulProfileDeriver deriver;

    private Long tenantId;
    private String userId;

    @BeforeEach
    void setup() {
        tenantId = 9_780_000L + System.nanoTime() % 10_000;
        userId = "ue_" + Long.toString(System.nanoTime() & 0xffff, 36);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_user_soul_profile WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
    }

    private String seedActive() {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_user_soul_profile "
                        + "(pid, tenant_id, user_id, version, status, profile, profile_hash, "
                        + " activated_at, created_at) "
                        + "VALUES (?, ?, ?, 1, 'active', ?::jsonb, ?, NOW(), NOW())",
                pid, tenantId, userId, "{\"persona\":{\"text\":\"engineer\"}}", "h:" + pid);
        return pid;
    }

    private String readEditedFields(String pid) {
        return jdbc.queryForObject(
                "SELECT COALESCE(edited_fields::text, '{}') FROM ab_agent_user_soul_profile WHERE pid = ?",
                String.class, pid);
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    @Test
    @DisplayName("pin — persists locked flag on edited_fields")
    void pinPersists() {
        String pid = seedActive();
        EditResult r = editor.pin(tenantId, userId, "persona");
        assertThat(r.pid()).isEqualTo(pid);
        assertThat(r.editedFields()).containsEntry("persona", "locked");
        assertThat(norm(readEditedFields(pid))).contains("\"persona\":\"locked\"");
    }

    @Test
    @DisplayName("hide — persists hidden flag")
    void hidePersists() {
        String pid = seedActive();
        editor.hide(tenantId, userId, "preferences.communication_style");
        assertThat(norm(readEditedFields(pid))).contains("\"preferences.communication_style\":\"hidden\"");
    }

    @Test
    @DisplayName("edit — stores override_text + edited_at")
    void editStoresOverride() {
        String pid = seedActive();
        EditResult r = editor.edit(tenantId, userId, "persona", "product manager");
        Object entry = r.editedFields().get("persona");
        assertThat(entry).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> entryMap = (Map<String, Object>) entry;
        assertThat(entryMap).containsEntry("override_text", "product manager");
        assertThat(entryMap.get("edited_at")).asString().isNotBlank();

        String json = readEditedFields(pid);
        assertThat(json).contains("product manager").contains("edited_at");
    }

    @Test
    @DisplayName("reset(field) — removes key")
    void resetFieldRemovesKey() {
        String pid = seedActive();
        editor.pin(tenantId, userId, "persona");
        editor.pin(tenantId, userId, "boundaries");
        editor.reset(tenantId, userId, "persona");
        String json = norm(readEditedFields(pid));
        assertThat(json).doesNotContain("\"persona\":\"locked\"").contains("\"boundaries\":\"locked\"");
    }

    @Test
    @DisplayName("reset(null) — clears entire map")
    void resetAllClears() {
        String pid = seedActive();
        editor.pin(tenantId, userId, "persona");
        editor.pin(tenantId, userId, "boundaries");
        editor.reset(tenantId, userId, null);
        String json = readEditedFields(pid);
        assertThat(json.replaceAll("\\s+", "")).isEqualTo("{}");
    }

    @Test
    @DisplayName("hideProfile — sets hidden_at but row stays")
    void hideProfile() {
        String pid = seedActive();
        editor.hideProfile(tenantId, userId);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, hidden_at FROM ab_agent_user_soul_profile WHERE pid = ?", pid);
        assertThat(row.get("status")).isEqualTo("active");
        assertThat(row.get("hidden_at")).isNotNull();
    }

    @Test
    @DisplayName("forgetProfile — archives all rows, inserts tombstone, blocks future derivation")
    void forgetCascades() {
        String active = seedActive();
        // Seed memories so deriver would otherwise produce a draft.
        String tag = "uefg" + Long.toString(System.nanoTime() & 0xfffff, 36) + "_";
        for (int i = 1; i <= 5; i++) {
            jdbc.update("INSERT INTO ab_agent_memory "
                            + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                            + " memory_title, memory_content, importance, shareable, scope, scope_key, "
                            + " created_at, updated_at, deleted_flag) "
                            + "VALUES (?, ?, 'default', 'fact', 'profile', 't', 'c', 8, FALSE, 'user', ?, NOW(), NOW(), FALSE)",
                    tag + i, tenantId, userId);
        }

        EditResult r = editor.forgetProfile(tenantId, userId);
        assertThat(r.status()).isEqualTo("archived");
        // Original active row is archived.
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ab_agent_user_soul_profile WHERE pid = ?", String.class, active))
                .isEqualTo("archived");
        // Tombstone is present and carries _forgotten = true.
        Long tombstoneCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile "
                        + "WHERE tenant_id = ? AND user_id = ? AND status = 'archived' "
                        + "  AND (edited_fields ->> '_forgotten') = 'true'",
                Long.class, tenantId, userId);
        assertThat(tombstoneCount).isGreaterThanOrEqualTo(1L);

        // Deriver respects the tombstone.
        ReflectionTestUtils.setField(deriver, "enabled", true);
        ReflectionTestUtils.setField(deriver, "minMemories", 3);
        ReflectionTestUtils.setField(deriver, "lookBackDays", 90);
        ReflectionTestUtils.setField(deriver, "llmEnabled", false);
        var result = deriver.deriveForUser(tenantId, userId);
        assertThat(result.outcome()).isEqualTo(UserSoulProfileDeriver.Outcome.SKIPPED_FORGOTTEN);
    }

    @Test
    @DisplayName("Cross-tenant access rejected")
    void crossTenantRejected() {
        seedActive();
        Long otherTenant = tenantId + 1;
        assertThatThrownBy(() -> editor.pin(otherTenant, userId, "persona"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Unknown user rejected")
    void unknownUserRejected() {
        assertThatThrownBy(() -> editor.pin(tenantId, "nonexistent_user_" + System.nanoTime(), "persona"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ARCHIVED profile cannot be edited")
    void archivedCannotEdit() {
        seedActive();
        editor.forgetProfile(tenantId, userId);
        assertThatThrownBy(() -> editor.pin(tenantId, userId, "persona"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("forgetProfile with no rows throws")
    void forgetNoRowsThrows() {
        assertThatThrownBy(() -> editor.forgetProfile(tenantId, userId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("isForgotten reflects tombstone state")
    void isForgottenReflectsState() {
        assertThat(editor.isForgotten(tenantId, userId)).isFalse();
        seedActive();
        editor.forgetProfile(tenantId, userId);
        assertThat(editor.isForgotten(tenantId, userId)).isTrue();
    }
}
