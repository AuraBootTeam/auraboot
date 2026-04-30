package com.auraboot.framework.conversation;

import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase D.5 integration test for the
 * {@code migrations/2026-04-30-d5-sender-type-backfill.sql} script. Asserts
 * the historical drift convergence semantics against a real PostgreSQL
 * database (per AGENTS.md backend-integration-test red lines: real DB, no
 * mocks). Closes v3.3 Q8 / Phase D Q-D.3=α historical-data side.
 *
 * <p>Cases:
 * <ol>
 *   <li>Legacy 'system'+0 ai_response row + matching aurabot AgentDefinition
 *       → row flipped to 'agent'+aurabot_id.</li>
 *   <li>Legacy row but tenant has no aurabot AgentDefinition → row left
 *       unchanged (DO $$ block's CONTINUE branch).</li>
 *   <li>Non-ai_response system rows (e.g. 'system' message_type) untouched
 *       — anti-clobber filter works.</li>
 *   <li>Idempotency — running the script twice does not re-flip already-
 *       converted rows.</li>
 * </ol>
 *
 * <p>Uses {@code Propagation.NOT_SUPPORTED} so the bulk INSERT/UPDATE work
 * is auto-committed and observable across the {@code DO $$} block. Test
 * teardown clears its own data via {@code DELETE WHERE} on the testRunId
 * prefix.
 */
@Slf4j
@SpringBootTest(classes = com.auraboot.framework.application.TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Rollback(false)
@DisplayName("D.5 sender_type backfill SQL — historical drift convergence")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class D5SenderTypeBackfillTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String backfillSql;

    private final long testRunId = System.nanoTime();
    private Long agentlessTenantId;
    private Long aurabotTenantId;
    private Long aurabotAgentDefId;

    private long legacyRowId;            // ai_response, 'system'+0, w/ aurabot agent → should flip
    private long legacyRowIdAgentless;   // ai_response, 'system'+0, no aurabot agent → unchanged
    private long unrelatedSystemRowId;   // system message_type, 'system'+0 → unchanged
    private long alreadyAgentRowId;      // already 'agent', should be untouched

    @BeforeEach
    void setUp() throws Exception {
        if (backfillSql == null) {
            byte[] bytes = new ClassPathResource(
                    "database/migrations/2026-04-30-d5-sender-type-backfill.sql")
                    .getInputStream().readAllBytes();
            backfillSql = new String(bytes, StandardCharsets.UTF_8);
        }

        // Two tenants — one with aurabot AgentDefinition row, one without
        aurabotTenantId = 1_000_000_000L + testRunId;
        agentlessTenantId = aurabotTenantId + 1;

        Long convId = jdbcTemplate.queryForObject(
                "INSERT INTO ab_im_conversation (tenant_id, type, name) VALUES (?, 'group', ?) RETURNING id",
                Long.class, aurabotTenantId, "d5-test-conv-" + testRunId);
        Long agentlessConvId = jdbcTemplate.queryForObject(
                "INSERT INTO ab_im_conversation (tenant_id, type, name) VALUES (?, 'group', ?) RETURNING id",
                Long.class, agentlessTenantId, "d5-test-conv-agentless-" + testRunId);

        String aurabotPid = "ad-d5-" + testRunId;
        aurabotAgentDefId = jdbcTemplate.queryForObject(
                "INSERT INTO ab_agent_definition (pid, tenant_id, agent_code, name, agent_type, status) "
                        + "VALUES (?, ?, 'aurabot', 'AuraBot D.5 test', 'reactive', 'active') RETURNING id",
                Long.class, aurabotPid, aurabotTenantId);

        // Legacy ai_response row (the one we want flipped)
        legacyRowId = jdbcTemplate.queryForObject(
                "INSERT INTO ab_im_message "
                        + "(conversation_id, tenant_id, sender_id, sender_type, seq, message_type, content, created_at) "
                        + "VALUES (?, ?, 0, 'system', 1, 'ai_response', 'legacy AI text', ?) RETURNING id",
                Long.class, convId, aurabotTenantId, java.sql.Timestamp.from(Instant.now()));

        // Legacy ai_response row but tenant has NO aurabot AgentDefinition
        legacyRowIdAgentless = jdbcTemplate.queryForObject(
                "INSERT INTO ab_im_message "
                        + "(conversation_id, tenant_id, sender_id, sender_type, seq, message_type, content, created_at) "
                        + "VALUES (?, ?, 0, 'system', 1, 'ai_response', 'legacy AI text 2', ?) RETURNING id",
                Long.class, agentlessConvId, agentlessTenantId, java.sql.Timestamp.from(Instant.now()));

        // Unrelated system message (should NOT be touched)
        unrelatedSystemRowId = jdbcTemplate.queryForObject(
                "INSERT INTO ab_im_message "
                        + "(conversation_id, tenant_id, sender_id, sender_type, seq, message_type, content, created_at) "
                        + "VALUES (?, ?, 0, 'system', 2, 'system', '[user joined]', ?) RETURNING id",
                Long.class, convId, aurabotTenantId, java.sql.Timestamp.from(Instant.now()));

        // Already-agent row (should NOT be touched)
        alreadyAgentRowId = jdbcTemplate.queryForObject(
                "INSERT INTO ab_im_message "
                        + "(conversation_id, tenant_id, sender_id, sender_type, seq, message_type, content, created_at) "
                        + "VALUES (?, ?, ?, 'agent', 3, 'ai_response', 'fresh agent text', ?) RETURNING id",
                Long.class, convId, aurabotTenantId, aurabotAgentDefId, java.sql.Timestamp.from(Instant.now()));
    }

    @AfterEach
    void cleanup() {
        // Clean up test rows (NOT_SUPPORTED means inserts are committed).
        jdbcTemplate.update("DELETE FROM ab_im_message WHERE tenant_id IN (?, ?)",
                aurabotTenantId, agentlessTenantId);
        jdbcTemplate.update("DELETE FROM ab_im_conversation WHERE tenant_id IN (?, ?)",
                aurabotTenantId, agentlessTenantId);
        jdbcTemplate.update("DELETE FROM ab_agent_definition WHERE tenant_id = ?", aurabotTenantId);
    }

    /**
     * Run the backfill SQL inline through JdbcTemplate. The SQL contains a
     * top-level SELECT (audit), a DO $$ block (UPDATE per tenant), and a
     * trailing SELECT (verify). PostgreSQL JDBC handles this multi-statement
     * input as a single batch.
     */
    private void runBackfill() {
        jdbcTemplate.execute(backfillSql);
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("legacy 'system'+0 ai_response WITH aurabot agent -> flipped to 'agent'+aurabot_id")
    void legacyRow_withAurabotAgent_flipped() {
        runBackfill();

        var after = jdbcTemplate.queryForMap(
                "SELECT sender_type, sender_id FROM ab_im_message WHERE id = ?", legacyRowId);
        assertThat(after.get("sender_type")).isEqualTo("agent");
        assertThat(((Number) after.get("sender_id")).longValue()).isEqualTo(aurabotAgentDefId);
    }

    @Test
    @DisplayName("legacy row WITHOUT aurabot agent -> unchanged (CONTINUE branch)")
    void legacyRow_withoutAurabotAgent_unchanged() {
        runBackfill();

        var after = jdbcTemplate.queryForMap(
                "SELECT sender_type, sender_id FROM ab_im_message WHERE id = ?", legacyRowIdAgentless);
        assertThat(after.get("sender_type")).isEqualTo("system");
        assertThat(((Number) after.get("sender_id")).longValue()).isZero();
    }

    @Test
    @DisplayName("unrelated system message (message_type='system') -> NOT touched (anti-clobber filter)")
    void unrelatedSystemMessage_notTouched() {
        runBackfill();

        var after = jdbcTemplate.queryForMap(
                "SELECT sender_type, sender_id, message_type FROM ab_im_message WHERE id = ?", unrelatedSystemRowId);
        assertThat(after.get("sender_type")).isEqualTo("system");
        assertThat(((Number) after.get("sender_id")).longValue()).isZero();
        assertThat(after.get("message_type")).isEqualTo("system");
    }

    @Test
    @DisplayName("already-agent row -> not touched")
    void alreadyAgentRow_notTouched() {
        runBackfill();

        var after = jdbcTemplate.queryForMap(
                "SELECT sender_type, sender_id FROM ab_im_message WHERE id = ?", alreadyAgentRowId);
        assertThat(after.get("sender_type")).isEqualTo("agent");
        assertThat(((Number) after.get("sender_id")).longValue()).isEqualTo(aurabotAgentDefId);
    }

    @Test
    @DisplayName("idempotent — running twice produces same result, no double-flip")
    void runTwice_isIdempotent() {
        runBackfill();
        runBackfill();

        var legacy = jdbcTemplate.queryForMap(
                "SELECT sender_type, sender_id FROM ab_im_message WHERE id = ?", legacyRowId);
        assertThat(legacy.get("sender_type")).isEqualTo("agent");
        assertThat(((Number) legacy.get("sender_id")).longValue()).isEqualTo(aurabotAgentDefId);

        var agentless = jdbcTemplate.queryForMap(
                "SELECT sender_type FROM ab_im_message WHERE id = ?", legacyRowIdAgentless);
        assertThat(agentless.get("sender_type")).isEqualTo("system");
    }
}
