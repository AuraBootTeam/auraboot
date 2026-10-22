package com.auraboot.framework.integration.agent;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.auraboot.framework.integration.TestIdGenerator;

/**
 * PR-65 Phase 1 — schema invariants for {@code ab_agent_memory_promotion}.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Memory Promotion schema (PR-65)")
class MemoryPromotionSchemaIntegrationTest extends BaseIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String tag;

    @BeforeEach
    void setup() {
        tenantId = TestIdGenerator.uniqueTenantId();
        tag = "mps" + Long.toString(System.nanoTime() & 0xfffff, 36) + "_";
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory_promotion WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
    }

    private String seedMemory(String pidSuffix, String scope, String scopeKey) {
        String pid = tag + pidSuffix;
        jdbc.update("INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                        + " memory_title, memory_content, importance, shareable, scope, scope_key, "
                        + " created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, 'test', 'fact', 'general', 'title', 'content', 5, FALSE, ?, ?, NOW(), NOW(), FALSE)",
                pid, tenantId, scope, scopeKey);
        return pid;
    }

    private String insertPromotion(String promPid,
                                   String sourceScope, String sourcePid,
                                   String targetScope, String status,
                                   String rejectReason) {
        jdbc.update("INSERT INTO ab_agent_memory_promotion "
                        + "(pid, tenant_id, source_scope, source_memory_pid, target_scope, "
                        + " category, proposed_content, status, reject_reason, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'general', 'c', ?, ?, NOW(), NOW())",
                promPid, tenantId, sourceScope, sourcePid, targetScope, status, rejectReason);
        return promPid;
    }

    @Test
    @DisplayName("Inserts with valid scope pair + status succeed")
    void happyPath() {
        String m = seedMemory("m1", "user", "42");
        String p = insertPromotion(tag + "p1", "user", m, "tenant", "DRAFT_PENDING_REVIEW", null);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, source_memory_pid FROM ab_agent_memory_promotion WHERE pid = ?", p);
        assertThat(row.get("status")).isEqualTo("DRAFT_PENDING_REVIEW");
        assertThat(row.get("source_memory_pid")).isEqualTo(m);
    }

    @Test
    @DisplayName("Invalid status rejected by CHECK constraint")
    void invalidStatusRejected() {
        String m = seedMemory("m2", "user", "42");
        assertThatThrownBy(() ->
                insertPromotion(tag + "p2", "user", m, "tenant", "BOGUS_STATUS", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Invalid scope pair rejected (e.g. session→tenant)")
    void invalidScopePairRejected() {
        String m = seedMemory("m3", "session", null);
        assertThatThrownBy(() ->
                insertPromotion(tag + "p3", "session", m, "tenant", "DRAFT_PENDING_REVIEW", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Invalid reject_reason rejected")
    void invalidRejectReasonRejected() {
        String m = seedMemory("m4", "user", "42");
        assertThatThrownBy(() ->
                insertPromotion(tag + "p4", "user", m, "tenant", "REVIEWED_REJECTED", "lol_bad_reason"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Duplicate pid rejected by UNIQUE")
    void duplicatePidRejected() {
        String m = seedMemory("m5", "user", "42");
        insertPromotion(tag + "p5", "user", m, "tenant", "DRAFT_PENDING_REVIEW", null);
        assertThatThrownBy(() ->
                insertPromotion(tag + "p5", "user", m, "tenant", "DRAFT_PENDING_REVIEW", null))
                .isInstanceOfAny(DuplicateKeyException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("FK source_memory_pid SET NULL when source memory is hard-deleted")
    void fkSetNullOnSourceDelete() {
        String m = seedMemory("m6", "user", "42");
        String p = insertPromotion(tag + "p6", "user", m, "tenant", "DRAFT_PENDING_REVIEW", null);
        jdbc.update("DELETE FROM ab_agent_memory WHERE pid = ?", m);
        String fk = jdbc.queryForObject(
                "SELECT source_memory_pid FROM ab_agent_memory_promotion WHERE pid = ?",
                String.class, p);
        assertThat(fk).isNull();
    }

    @Test
    @DisplayName("ab_agent_memory.shadow_mode + promoted_from_pid columns present")
    void companionColumnsPresent() {
        String m = seedMemory("m7", "tenant", null);
        jdbc.update("UPDATE ab_agent_memory SET shadow_mode = TRUE, promoted_from_pid = 'prom_xyz' WHERE pid = ?", m);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT shadow_mode, promoted_from_pid FROM ab_agent_memory WHERE pid = ?", m);
        assertThat(row.get("shadow_mode")).isEqualTo(true);
        assertThat(row.get("promoted_from_pid")).isEqualTo("prom_xyz");
    }
}
