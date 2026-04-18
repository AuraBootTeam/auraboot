package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.MemoryPromotionApplier;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link MemoryPromotionApplier} (PR-66 Phase 2).
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("MemoryPromotionApplier (PR-66)")
class MemoryPromotionApplierIntegrationTest extends BaseIntegrationTest {

    @Autowired private MemoryPromotionApplier applier;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_791_000L + System.nanoTime() % 10_000;
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory_promotion WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
    }

    /** Seed a DRAFT_PENDING_REVIEW promotion row and return its pid. */
    private String seedDraft() {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_memory_promotion ("
                        + "pid, tenant_id, source_scope, target_scope, category, "
                        + "proposed_title, proposed_content, proposed_importance, "
                        + "status, created_at, updated_at) "
                        + "VALUES (?, ?, 'user', 'tenant', 'ops', 'title', 'content', 7, "
                        + "'DRAFT_PENDING_REVIEW', NOW(), NOW())",
                pid, tenantId);
        return pid;
    }

    /** Seed a PROMOTED_SHADOW row with a paired memory. */
    private String[] seedShadow() {
        // create the proposed memory first
        String memoryPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                        + " memory_title, memory_content, importance, shareable, scope, scope_key, "
                        + " shadow_mode, created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, 'default', 'fact', 'ops', 't', 'c', 7, TRUE, 'tenant', NULL, "
                        + "TRUE, NOW(), NOW(), FALSE)",
                memoryPid, tenantId);
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_memory_promotion ("
                        + "pid, tenant_id, source_scope, target_scope, category, "
                        + "proposed_title, proposed_content, proposed_importance, "
                        + "status, promoted_memory_pid, shadow_started_at, shadow_ends_at, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, 'user', 'tenant', 'ops', 't', 'c', 7, "
                        + "'PROMOTED_SHADOW', ?, NOW(), NOW() + INTERVAL '7 days', NOW(), NOW())",
                pid, tenantId, memoryPid);
        return new String[]{pid, memoryPid};
    }

    // ---------------------- approve ----------------------

    @Test
    @DisplayName("approve: DRAFT → PROMOTED_SHADOW, creates memory with shadow_mode=TRUE and shadow_ends_at=+7d")
    void approve_flipsToShadow() {
        String pid = seedDraft();
        MemoryPromotionApplier.EvaluationResult result = applier.approve(pid, 42L, "looks good");

        assertThat(result.previousStatus()).isEqualTo("DRAFT_PENDING_REVIEW");
        assertThat(result.newStatus()).isEqualTo("PROMOTED_SHADOW");
        assertThat(result.promotedMemoryPid()).isNotBlank();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, reviewer_id, review_comment, promoted_memory_pid, "
                        + "       shadow_started_at, shadow_ends_at "
                        + "FROM ab_agent_memory_promotion WHERE pid = ?", pid);
        assertThat(row.get("status")).isEqualTo("PROMOTED_SHADOW");
        assertThat(((Number) row.get("reviewer_id")).longValue()).isEqualTo(42L);
        assertThat(row.get("review_comment")).isEqualTo("looks good");
        assertThat(row.get("shadow_started_at")).isNotNull();
        assertThat(row.get("shadow_ends_at")).isNotNull();

        Map<String, Object> mem = jdbc.queryForMap(
                "SELECT scope, scope_key, shadow_mode, promoted_from_pid, shareable "
                        + "FROM ab_agent_memory WHERE pid = ?",
                result.promotedMemoryPid());
        assertThat(mem.get("scope")).isEqualTo("tenant");
        assertThat(mem.get("shadow_mode")).isEqualTo(true);
        assertThat(mem.get("promoted_from_pid")).isEqualTo(pid);
        assertThat(mem.get("shareable")).isEqualTo(true);
    }

    // ---------------------- reject ----------------------

    @Test
    @DisplayName("reject: DRAFT → REVIEWED_REJECTED, no memory created, reject_reason persisted")
    void reject_storesReason() {
        String pid = seedDraft();
        applier.reject(pid, 42L, "too_specific", "only affects dev team");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, reject_reason, review_comment, reviewer_id "
                        + "FROM ab_agent_memory_promotion WHERE pid = ?", pid);
        assertThat(row.get("status")).isEqualTo("REVIEWED_REJECTED");
        assertThat(row.get("reject_reason")).isEqualTo("too_specific");
        assertThat(row.get("review_comment")).isEqualTo("only affects dev team");

        // no memory row created for this tenant at 'tenant' scope
        Integer memCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory WHERE tenant_id = ? AND scope='tenant'",
                Integer.class, tenantId);
        assertThat(memCount).isZero();
    }

    @Test
    @DisplayName("reject: arbitrary reject_reason rejected with IllegalArgumentException")
    void reject_invalidReason_throws() {
        String pid = seedDraft();
        assertThatThrownBy(() -> applier.reject(pid, 42L, "random_string", "comment"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("random_string");
        // status unchanged
        String status = jdbc.queryForObject(
                "SELECT status FROM ab_agent_memory_promotion WHERE pid = ?", String.class, pid);
        assertThat(status).isEqualTo("DRAFT_PENDING_REVIEW");
    }

    // ---------------------- retract ----------------------

    @Test
    @DisplayName("retract: PROMOTED_SHADOW → RETRACTED and shadow memory soft-deleted")
    void retract_softDeletesMemory() {
        String[] ids = seedShadow();
        String pid = ids[0];
        String memoryPid = ids[1];

        applier.retract(pid, 99L, "wrong fact");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, review_comment FROM ab_agent_memory_promotion WHERE pid = ?", pid);
        assertThat(row.get("status")).isEqualTo("RETRACTED");
        assertThat(row.get("review_comment")).isEqualTo("wrong fact");

        Boolean deleted = jdbc.queryForObject(
                "SELECT deleted_flag FROM ab_agent_memory WHERE pid = ?", Boolean.class, memoryPid);
        assertThat(deleted).isTrue();
    }

    @Test
    @DisplayName("retract fails when status is ACTIVE (must be shadow)")
    void retract_afterActive_throws() {
        String[] ids = seedShadow();
        String pid = ids[0];
        // flip to ACTIVE manually
        jdbc.update("UPDATE ab_agent_memory_promotion SET status='ACTIVE' WHERE pid=?", pid);

        assertThatThrownBy(() -> applier.retract(pid, 99L, "changed mind"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------------------- concurrency ----------------------

    @Test
    @DisplayName("concurrent approve: only one wins, the other throws IllegalStateException")
    void concurrent_approve_race() throws Exception {
        String pid = seedDraft();
        CompletableFuture<Throwable> a = CompletableFuture.supplyAsync(() -> {
            try { applier.approve(pid, 1L, "c1"); return null; }
            catch (Throwable t) { return t; }
        });
        CompletableFuture<Throwable> b = CompletableFuture.supplyAsync(() -> {
            try { applier.approve(pid, 2L, "c2"); return null; }
            catch (Throwable t) { return t; }
        });
        Throwable ta = a.get();
        Throwable tb = b.get();
        long failures = java.util.stream.Stream.of(ta, tb).filter(t -> t != null).count();
        assertThat(failures).isEqualTo(1L);

        String finalStatus = jdbc.queryForObject(
                "SELECT status FROM ab_agent_memory_promotion WHERE pid = ?", String.class, pid);
        assertThat(finalStatus).isEqualTo("PROMOTED_SHADOW");
    }

    @Test
    @DisplayName("unknown pid throws IllegalArgumentException (cross-tenant / not-found probe)")
    void unknown_pid_throws() {
        assertThatThrownBy(() -> applier.approve(UniqueIdGenerator.generate(), 1L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
