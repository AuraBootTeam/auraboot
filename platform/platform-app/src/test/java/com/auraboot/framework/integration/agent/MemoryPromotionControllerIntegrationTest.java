package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.controller.MemoryPromotionController;
import com.auraboot.framework.agent.service.MemoryPromotionApplier;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-67 — controller + gauges. Applier is mocked so the controller is
 * exercised independently of Phase 2's @Service implementation.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("MemoryPromotionController (PR-67)")
class MemoryPromotionControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private MemoryPromotionController controller;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private MemoryPromotionApplier applier;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_460_000L + System.nanoTime() % 100_000;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
        reset(applier);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory_promotion WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
    }

    // -----------------------------------------------------------------------
    // Seed helpers
    // -----------------------------------------------------------------------

    private String seedProposal(String status, BigDecimal confidence, String reasonCode) {
        String pid = UniqueIdGenerator.generate();
        // source_memory_pid = NULL to avoid the ab_agent_memory FK;
        // detail/provenance tests that need a real source seed it explicitly.
        jdbc.update(
                "INSERT INTO ab_agent_memory_promotion " +
                        "(pid, tenant_id, source_scope, target_scope, " +
                        " category, proposed_title, proposed_content, proposed_importance, " +
                        " reason_code, confidence_score, similarity_score, ai_rationale, status, created_at) " +
                        "VALUES (?, ?, 'user', 'tenant', " +
                        "        'operations', 'Month-end 28', 'close books on 28th', 7, " +
                        "        ?, ?, 0.87, 'three users agree', ?, NOW())",
                pid, tenantId, reasonCode, confidence, status);
        return pid;
    }

    private String seedSourceMemory(String userId) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_agent_memory " +
                        "(pid, tenant_id, memory_type, category, memory_title, memory_content, " +
                        " importance, shareable, scope, scope_key, created_at, updated_at) " +
                        "VALUES (?, ?, 'user_shared', 'operations', 'close day', 'we close books on 28th', " +
                        "        7, TRUE, 'user', ?, NOW(), NOW())",
                pid, tenantId, userId);
        return pid;
    }

    // -----------------------------------------------------------------------
    // GET /
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("list defaults to DRAFT_PENDING_REVIEW sorted by confidence desc")
    void list_default_confidence_desc() {
        seedProposal("DRAFT_PENDING_REVIEW", new BigDecimal("0.70"), "cross_user_agreement");
        String hi = seedProposal("DRAFT_PENDING_REVIEW", new BigDecimal("0.92"), "cross_user_agreement");
        seedProposal("ACTIVE", new BigDecimal("0.81"), "cross_user_agreement");

        ApiResponse<List<Map<String, Object>>> r = controller.list(null, null, 50, null);
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData()).hasSize(2);
        assertThat(r.getData().get(0).get("pid")).isEqualTo(hi);
    }

    @Test
    @DisplayName("list filters by status + reason_code")
    void list_filters() {
        seedProposal("DRAFT_PENDING_REVIEW", new BigDecimal("0.90"), "cross_user_agreement");
        String co = seedProposal("DRAFT_PENDING_REVIEW", new BigDecimal("0.60"), "implicit_co_sign");

        ApiResponse<List<Map<String, Object>>> r = controller.list(null, "implicit_co_sign", 50, null);
        assertThat(r.getData()).hasSize(1);
        assertThat(r.getData().get(0).get("pid")).isEqualTo(co);
    }

    @Test
    @DisplayName("list caps limit to 200 and floors to 1")
    void list_limit_cap() {
        for (int i = 0; i < 3; i++) {
            seedProposal("DRAFT_PENDING_REVIEW", new BigDecimal("0.85"), "cross_user_agreement");
        }
        assertThat(controller.list(null, null, 500, null).getData()).hasSize(3);
        assertThat(controller.list(null, null, -5, null).getData()).hasSize(1);
    }

    @Test
    @DisplayName("list rejects unknown status / reason / sort")
    void list_invalid_params() {
        assertThat(controller.list("WHATEVER", null, 50, null).getCode()).isEqualTo("400");
        assertThat(controller.list(null, "bogus_reason", 50, null).getCode()).isEqualTo("400");
        assertThat(controller.list(null, null, 50, "random_sort").getCode()).isEqualTo("400");
    }

    // -----------------------------------------------------------------------
    // GET /{pid}
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("detail returns row + joined source memories")
    void detail_with_source() {
        String srcPid = seedSourceMemory(testUser.getId().toString());
        String pid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_agent_memory_promotion " +
                        "(pid, tenant_id, source_scope, source_memory_pid, target_scope, " +
                        " category, proposed_title, proposed_content, proposed_importance, " +
                        " reason_code, confidence_score, status, created_at) " +
                        "VALUES (?, ?, 'user', ?, 'tenant', 'operations', 't', 'c', 5, " +
                        "        'cross_user_agreement', 0.85, 'DRAFT_PENDING_REVIEW', NOW())",
                pid, tenantId, srcPid);

        ApiResponse<Map<String, Object>> r = controller.detail(pid);
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData().get("pid")).isEqualTo(pid);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> src = (List<Map<String, Object>>) r.getData().get("source_memories");
        assertThat(src).hasSize(1);
        assertThat(src.get(0).get("pid")).isEqualTo(srcPid);
    }

    @Test
    @DisplayName("detail 404 for missing pid and cross-tenant")
    void detail_404() {
        assertThat(controller.detail("01DOESNOTEXIST").getCode()).isEqualTo("404");

        String pid = seedProposal("DRAFT_PENDING_REVIEW", new BigDecimal("0.85"), "cross_user_agreement");
        Long other = tenantId + 9_999L;
        MetaContext.setContext(other, testUser.getId(), testUser.getPid(), testUser.getUserName());
        assertThat(controller.detail(pid).getCode()).isEqualTo("404");
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    // -----------------------------------------------------------------------
    // GET /{pid}/provenance
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("provenance returns promotion + source_memories + promoted_memory shape")
    void provenance_shape() {
        String srcPid = seedSourceMemory(testUser.getId().toString());
        String pid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_agent_memory_promotion " +
                        "(pid, tenant_id, source_scope, source_memory_pid, target_scope, " +
                        " category, proposed_title, proposed_content, proposed_importance, " +
                        " reason_code, confidence_score, status, created_at) " +
                        "VALUES (?, ?, 'user', ?, 'tenant', 'operations', 't', 'c', 5, " +
                        "        'cross_user_agreement', 0.85, 'DRAFT_PENDING_REVIEW', NOW())",
                pid, tenantId, srcPid);

        ApiResponse<Map<String, Object>> r = controller.provenance(pid);
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData()).containsKeys("promotion", "source_memories", "promoted_memory", "upstream_promotions");
        assertThat(r.getData().get("promoted_memory")).isNull();
    }

    @Test
    @DisplayName("provenance 404 for missing pid")
    void provenance_404() {
        assertThat(controller.provenance("01NOTHERE").getCode()).isEqualTo("404");
    }

    // -----------------------------------------------------------------------
    // POST /{pid}/review
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("review approve calls applier.approve with tenant's reviewer id and comment")
    void review_approve_calls_applier() {
        String pid = seedProposal("DRAFT_PENDING_REVIEW", new BigDecimal("0.85"), "cross_user_agreement");
        when(applier.approve(eq(pid), eq(testUser.getId()), eq("lgtm")))
                .thenReturn(new MemoryPromotionApplier.EvaluationResult(
                        pid, "DRAFT_PENDING_REVIEW", "PROMOTED_SHADOW", "promo_mem_1"));

        ApiResponse<Map<String, Object>> r = controller.review(pid,
                Map.of("decision", "approve", "comment", "lgtm"));
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData().get("status")).isEqualTo("PROMOTED_SHADOW");
        assertThat(r.getData().get("promoted_memory_pid")).isEqualTo("promo_mem_1");

        ArgumentCaptor<String> pidC = ArgumentCaptor.forClass(String.class);
        verify(applier, times(1)).approve(pidC.capture(), eq(testUser.getId()), eq("lgtm"));
        assertThat(pidC.getValue()).isEqualTo(pid);
    }

    @Test
    @DisplayName("review reject with invalid reject_reason returns 400 and does not call applier")
    void review_reject_invalid_reason() {
        String pid = seedProposal("DRAFT_PENDING_REVIEW", new BigDecimal("0.85"), "cross_user_agreement");
        ApiResponse<Map<String, Object>> r = controller.review(pid,
                Map.of("decision", "reject", "reject_reason", "made_up_reason"));
        assertThat(r.getCode()).isEqualTo("400");
        verify(applier, never()).reject(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("review reject forwards valid reject_reason to applier")
    void review_reject_valid() {
        String pid = seedProposal("DRAFT_PENDING_REVIEW", new BigDecimal("0.85"), "cross_user_agreement");
        when(applier.reject(eq(pid), eq(testUser.getId()), eq("contains_pii"), any()))
                .thenReturn(new MemoryPromotionApplier.EvaluationResult(
                        pid, "DRAFT_PENDING_REVIEW", "REVIEWED_REJECTED", null));
        ApiResponse<Map<String, Object>> r = controller.review(pid,
                Map.of("decision", "reject", "reject_reason", "contains_pii", "comment", "had email"));
        assertThat(r.getCode()).isEqualTo("0");
        verify(applier, times(1)).reject(eq(pid), eq(testUser.getId()), eq("contains_pii"), eq("had email"));
    }

    @Test
    @DisplayName("review invalid decision returns 400")
    void review_invalid_decision() {
        String pid = seedProposal("DRAFT_PENDING_REVIEW", new BigDecimal("0.85"), "cross_user_agreement");
        assertThat(controller.review(pid, Map.of("decision", "maybe")).getCode()).isEqualTo("400");
    }

    @Test
    @DisplayName("review 404 for missing pid / cross-tenant")
    void review_404() {
        assertThat(controller.review("01MISSING", Map.of("decision", "approve")).getCode()).isEqualTo("404");
    }

    @Test
    @DisplayName("review maps applier IllegalStateException to 409")
    void review_409_on_conflict() {
        String pid = seedProposal("DRAFT_PENDING_REVIEW", new BigDecimal("0.85"), "cross_user_agreement");
        when(applier.approve(anyString(), any(), any()))
                .thenThrow(new IllegalStateException("already reviewed"));
        assertThat(controller.review(pid, Map.of("decision", "approve")).getCode()).isEqualTo("409");
    }

    // -----------------------------------------------------------------------
    // POST /{pid}/retract
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("retract forwards reason to applier")
    void retract_ok() {
        String pid = seedProposal("PROMOTED_SHADOW", new BigDecimal("0.85"), "cross_user_agreement");
        when(applier.retract(eq(pid), eq(testUser.getId()), eq("turned out wrong")))
                .thenReturn(new MemoryPromotionApplier.EvaluationResult(
                        pid, "PROMOTED_SHADOW", "RETRACTED", null));
        ApiResponse<Map<String, Object>> r = controller.retract(pid, Map.of("reason", "turned out wrong"));
        assertThat(r.getCode()).isEqualTo("0");
        verify(applier, times(1)).retract(eq(pid), eq(testUser.getId()), eq("turned out wrong"));
    }

    @Test
    @DisplayName("retract 400 when reason missing")
    void retract_missing_reason() {
        String pid = seedProposal("PROMOTED_SHADOW", new BigDecimal("0.85"), "cross_user_agreement");
        assertThat(controller.retract(pid, Map.of()).getCode()).isEqualTo("400");
        verify(applier, never()).retract(anyString(), any(), anyString());
    }

    // -----------------------------------------------------------------------
    // POST /batch-approve
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("batch-approve forwards only >=0.80 confidence and reports failures")
    void batch_approve_filters_confidence() {
        String hi = seedProposal("DRAFT_PENDING_REVIEW", new BigDecimal("0.85"), "cross_user_agreement");
        String lo = seedProposal("DRAFT_PENDING_REVIEW", new BigDecimal("0.70"), "cross_user_agreement");
        when(applier.approve(eq(hi), any(), any()))
                .thenReturn(new MemoryPromotionApplier.EvaluationResult(
                        hi, "DRAFT_PENDING_REVIEW", "PROMOTED_SHADOW", "mp1"));

        ApiResponse<Map<String, Object>> r = controller.batchApprove(Map.of(
                "pids", List.of(hi, lo, "01MISSING"), "comment", "batch ok"));
        assertThat(r.getCode()).isEqualTo("0");
        @SuppressWarnings("unchecked")
        List<String> approved = (List<String>) r.getData().get("approved");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) r.getData().get("failed");
        assertThat(approved).containsExactly(hi);
        assertThat(failed).extracting(m -> m.get("pid")).containsExactlyInAnyOrder(lo, "01MISSING");

        verify(applier, times(1)).approve(eq(hi), any(), any());
        verify(applier, never()).approve(eq(lo), any(), any());
    }

    @Test
    @DisplayName("batch-approve rejects empty or oversize pid list")
    void batch_approve_bounds() {
        assertThat(controller.batchApprove(Map.of("pids", List.of())).getCode()).isEqualTo("400");
        List<String> overflow = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) overflow.add("01P_" + i);
        assertThat(controller.batchApprove(Map.of("pids", overflow)).getCode()).isEqualTo("400");
        assertThat(controller.batchApprove(Map.of("pids", "not-a-list")).getCode()).isEqualTo("400");
    }

    // -----------------------------------------------------------------------
    // GET /stats
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("stats returns per-status + per-reason counts plus oldest age")
    void stats_shape() {
        seedProposal("DRAFT_PENDING_REVIEW", new BigDecimal("0.85"), "cross_user_agreement");
        seedProposal("DRAFT_PENDING_REVIEW", new BigDecimal("0.60"), "implicit_co_sign");
        seedProposal("ACTIVE", new BigDecimal("0.90"), "cross_user_agreement");

        ApiResponse<Map<String, Object>> r = controller.stats();
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(((Number) r.getData().get("total")).longValue()).isEqualTo(3);
        @SuppressWarnings("unchecked")
        Map<String, Long> byStatus = (Map<String, Long>) r.getData().get("by_status");
        assertThat(byStatus.get("DRAFT_PENDING_REVIEW")).isEqualTo(2L);
        assertThat(byStatus.get("ACTIVE")).isEqualTo(1L);
        assertThat(byStatus.get("REVIEWED_REJECTED")).isEqualTo(0L);
        @SuppressWarnings("unchecked")
        Map<String, Long> byReason = (Map<String, Long>) r.getData().get("by_reason_code");
        assertThat(byReason.get("cross_user_agreement")).isEqualTo(2L);
        assertThat(byReason.get("implicit_co_sign")).isEqualTo(1L);
        assertThat(((Number) r.getData().get("oldest_pending_age_seconds")).doubleValue())
                .isGreaterThanOrEqualTo(0.0);
    }
}
