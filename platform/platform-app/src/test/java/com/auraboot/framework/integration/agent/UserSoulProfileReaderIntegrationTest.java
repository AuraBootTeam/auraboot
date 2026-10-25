package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.UserSoulProfileReader;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-77 Phase 3 — {@link UserSoulProfileReader#loadForGrounding(Long, String)}.
 *
 * <p>Covers all acceptance criteria from plan §12 Phase 3 + §5.5 semantics:
 * active-only, hide/supersede gates, staleness line, edit-merge (hide &gt; override),
 * tenant isolation, cross-user isolation, null-input behaviour, length cap.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("User Soul Profile Reader (PR-77 Phase 3)")
class UserSoulProfileReaderIntegrationTest extends BaseIntegrationTest {

    @Autowired private UserSoulProfileReader reader;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String userId;

    @BeforeEach
    void setup() {
        long base = System.nanoTime() % 1_000_000;
        tenantId = 9_770_000L + base;
        userId = "u_" + base;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_user_soul_profile WHERE tenant_id = ?", tenantId);
    }

    // =========================================================================
    // Seed helpers
    // =========================================================================

    private String seed(Long tid, String uid, String status, String profileJson,
                        String editedFieldsJson, boolean hidden, boolean stale) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_agent_user_soul_profile (pid, tenant_id, user_id, version, status, " +
                        "profile, profile_hash, hidden_at, stale_flagged_at, edited_fields, activated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?)",
                pid, tid, uid, 1, status,
                profileJson, "hash_" + pid,
                hidden ? Timestamp.from(Instant.now()) : null,
                stale ? Timestamp.from(Instant.now()) : null,
                editedFieldsJson,
                Timestamp.from(Instant.now()));
        return pid;
    }

    private static final String RICH_PROFILE = """
            {
              "persona": {"text": "E-commerce engineer, tenant admin, pragmatic."},
              "preferences": {
                "communication_style": {"text": "concise bullet points; code examples welcome"},
                "domain_vocabulary": {"text": "SKU, 月结, PO"},
                "working_hours": {"text": "09:00-19:00 Asia/Shanghai"}
              },
              "habits": {"recurring_actions": [
                {"pattern": "月底对账", "frequency": "monthly"},
                {"pattern": "周一 standup", "frequency": "weekly"}
              ]},
              "expertise": {"domains": [
                {"name": "inventory management"}, {"name": "SQL analytics"}
              ]},
              "boundaries": {"text": "never auto-approve commit-level changes"},
              "language": "zh-CN"
            }
            """;

    // =========================================================================
    // Cases
    // =========================================================================

    @Test
    @DisplayName("ACTIVE profile → ProfileSection with rendered text")
    void active_profile_returns_section() {
        seed(tenantId, userId, "active", RICH_PROFILE, null, false, false);

        Optional<UserSoulProfileReader.ProfileSection> section =
                reader.loadForGrounding(tenantId, userId);

        assertThat(section).isPresent();
        String text = section.get().renderedPromptText();
        assertThat(text).contains("About this user");
        assertThat(text).contains("E-commerce engineer");
        assertThat(text).contains("concise bullet points");
        assertThat(text).contains("月底对账");
        assertThat(text).contains("inventory management");
        assertThat(text).contains("Boundaries");
        assertThat(section.get().stale()).isFalse();
        assertThat(section.get().rawProfile()).isNotEmpty();
    }

    @Test
    @DisplayName("No ACTIVE row → Optional.empty")
    void no_row_returns_empty() {
        Optional<UserSoulProfileReader.ProfileSection> section =
                reader.loadForGrounding(tenantId, userId);
        assertThat(section).isEmpty();
    }

    @Test
    @DisplayName("hidden_at set → Optional.empty even when ACTIVE")
    void hidden_row_returns_empty() {
        seed(tenantId, userId, "active", RICH_PROFILE, null, true, false);
        assertThat(reader.loadForGrounding(tenantId, userId)).isEmpty();
    }

    @Test
    @DisplayName("Only ACTIVE returns — DRAFT / SUPERSEDED / ARCHIVED do not")
    void only_active_returns() {
        seed(tenantId, userId, "draft", RICH_PROFILE, null, false, false);
        assertThat(reader.loadForGrounding(tenantId, userId)).isEmpty();

        jdbc.update("DELETE FROM ab_agent_user_soul_profile WHERE tenant_id = ?", tenantId);
        seed(tenantId, userId, "superseded", RICH_PROFILE, null, false, false);
        assertThat(reader.loadForGrounding(tenantId, userId)).isEmpty();

        jdbc.update("DELETE FROM ab_agent_user_soul_profile WHERE tenant_id = ?", tenantId);
        seed(tenantId, userId, "archived", RICH_PROFILE, null, false, false);
        assertThat(reader.loadForGrounding(tenantId, userId)).isEmpty();
    }

    @Test
    @DisplayName("stale_flagged_at set → stale=true + warning line")
    void stale_appends_warning() {
        seed(tenantId, userId, "active", RICH_PROFILE, null, false, true);

        Optional<UserSoulProfileReader.ProfileSection> section =
                reader.loadForGrounding(tenantId, userId);

        assertThat(section).isPresent();
        assertThat(section.get().stale()).isTrue();
        assertThat(section.get().renderedPromptText()).contains("may be outdated");
    }

    @Test
    @DisplayName("edited_fields hides persona → persona line omitted")
    void edited_fields_hide() {
        String edited = "{\"persona\": \"hidden\"}";
        seed(tenantId, userId, "active", RICH_PROFILE, edited, false, false);

        Optional<UserSoulProfileReader.ProfileSection> section =
                reader.loadForGrounding(tenantId, userId);

        assertThat(section).isPresent();
        String text = section.get().renderedPromptText();
        assertThat(text).doesNotContain("E-commerce engineer");
        assertThat(text).doesNotContain("Persona:");
        // Other fields remain
        assertThat(text).contains("concise bullet points");
    }

    @Test
    @DisplayName("edited_fields override_text → rendered text uses override")
    void edited_fields_override() {
        String edited = "{\"persona\": {\"override_text\": \"Overridden persona X\"}}";
        seed(tenantId, userId, "active", RICH_PROFILE, edited, false, false);

        String text = reader.loadForGrounding(tenantId, userId).orElseThrow().renderedPromptText();
        assertThat(text).contains("Overridden persona X");
        assertThat(text).doesNotContain("E-commerce engineer");
    }

    @Test
    @DisplayName("edited_fields hide wins over override")
    void hide_wins_over_override() {
        String edited = "{\"persona\": {\"override_text\": \"X\", \"hidden\": true}}";
        seed(tenantId, userId, "active", RICH_PROFILE, edited, false, false);

        String text = reader.loadForGrounding(tenantId, userId).orElseThrow().renderedPromptText();
        assertThat(text).doesNotContain("Persona:");
        assertThat(text).doesNotContain("Overridden");
        assertThat(text).doesNotContain("X");
    }

    @Test
    @DisplayName("Cross-tenant isolation: different tenant → empty")
    void tenant_isolation() {
        seed(tenantId, userId, "active", RICH_PROFILE, null, false, false);
        Long otherTenant = tenantId + 99;
        assertThat(reader.loadForGrounding(otherTenant, userId)).isEmpty();
    }

    @Test
    @DisplayName("Cross-user isolation: user B query does not see user A's profile")
    void cross_user_isolation() {
        seed(tenantId, userId, "active", RICH_PROFILE, null, false, false);
        assertThat(reader.loadForGrounding(tenantId, userId + "_other")).isEmpty();
    }

    @Test
    @DisplayName("Null tenantId / userId → Optional.empty, never throws")
    void null_inputs_return_empty() {
        assertThat(reader.loadForGrounding(null, userId)).isEmpty();
        assertThat(reader.loadForGrounding(tenantId, null)).isEmpty();
        assertThat(reader.loadForGrounding(tenantId, "")).isEmpty();
        assertThat(reader.loadForGrounding(tenantId, "   ")).isEmpty();
    }

    @Test
    @DisplayName("Rendered text capped at MAX_PROMPT_CHARS even for verbose profiles")
    void length_bounded() {
        String huge = "{\"persona\": {\"text\": \"" + "x".repeat(2000) + "\"},"
                + "\"preferences\": {\"communication_style\": {\"text\": \""
                + "y".repeat(2000) + "\"}}}";
        seed(tenantId, userId, "active", huge, null, false, false);

        Optional<UserSoulProfileReader.ProfileSection> section =
                reader.loadForGrounding(tenantId, userId);
        assertThat(section).isPresent();
        assertThat(section.get().renderedPromptText().length())
                .isLessThanOrEqualTo(UserSoulProfileReader.MAX_PROMPT_CHARS);
    }
}
