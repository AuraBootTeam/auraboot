package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.controller.AdminShadowRunController;
import com.auraboot.framework.agent.dto.replay.ShadowRunAggregation;
import com.auraboot.framework.agent.dto.replay.ShadowRunListItem;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * D.5 Phase 1 — {@link AdminShadowRunController} integration tests.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code list_aggregations_groupedByDraft_sortedByLatest}</li>
 *   <li>{@code drilldown_paginated_returnsLatestFirst}</li>
 *   <li>{@code detail_returnsBothOutputs}</li>
 *   <li>{@code tenant_isolation_otherTenantShadowRunInvisible}</li>
 *   <li>{@code emptyFixture_returnsEmptyList}</li>
 * </ul>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("AdminShadowRunController — D.5 Phase 1")
class AdminShadowRunControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private AdminShadowRunController controller;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = TestIdGenerator.uniqueTenantId();
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            // Order matters: shadow_run FK → skill_draft (CASCADE delete handles
            // orphan cleanup, but we wipe both for determinism).
            jdbc.update("DELETE FROM ab_agent_shadow_run WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_agent_skill_draft WHERE tenant_id = ?", tenantId);
        }
    }

    // =========================================================================
    // Seeding
    // =========================================================================

    private String seedDraft(String code, String status) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_agent_skill_draft " +
                        "(pid, tenant_id, draft_skill_code, contract_yaml, " +
                        " source_pattern_hash, status, created_at) " +
                        "VALUES (?, ?, ?, 'skill_code: x', 'h_' || ?, ?, NOW())",
                pid, tenantId, code, pid, status);
        return pid;
    }

    private String seedShadowRun(
            String draftPid,
            Boolean fidelityMatch,
            Boolean outputMatch,
            BigDecimal shadowCost,
            BigDecimal originalCost) {
        return seedShadowRun(draftPid, fidelityMatch, outputMatch, shadowCost, originalCost,
                /*ageMinutesAgo*/ 0);
    }

    private String seedShadowRun(
            String draftPid,
            Boolean fidelityMatch,
            Boolean outputMatch,
            BigDecimal shadowCost,
            BigDecimal originalCost,
            int ageMinutesAgo) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_agent_shadow_run " +
                        "(pid, tenant_id, draft_id, original_run_id, " +
                        " shadow_status, shadow_duration_ms, shadow_cost_usd, shadow_tokens, " +
                        " shadow_output_hash, " +
                        " original_status, original_duration_ms, original_cost_usd, " +
                        " original_output_hash, " +
                        " output_match, fidelity_match, output_diff, created_at) " +
                        "VALUES (?, ?, ?, ?, " +
                        " 'success', 1200, ?, 42, 'sh_' || ?, " +
                        " 'success', 1500, ?, 'or_' || ?, " +
                        " ?, ?, ?::jsonb, NOW() - (? || ' minutes')::interval)",
                pid, tenantId, draftPid, UniqueIdGenerator.generate(),
                shadowCost, pid,
                originalCost, pid,
                outputMatch, fidelityMatch,
                outputMatch == Boolean.FALSE
                        ? "[{\"path\":\"/result\",\"shadow\":\"a\",\"production\":\"b\"}]"
                        : null,
                ageMinutesAgo);
        return pid;
    }

    // =========================================================================
    // Aggregation list
    // =========================================================================

    @Test
    @DisplayName("aggregations: grouped by draft, sorted by latest_at DESC, KPIs computed")
    void list_aggregations_groupedByDraft_sortedByLatest() {
        String draftA = seedDraft("auto.alpha", "SHADOW_RUNNING");
        String draftB = seedDraft("auto.beta", "SHADOW_RUNNING");

        // Draft A — 2 shadow runs: 1 fidelity match, 1 fidelity miss; both
        // output matches; cost delta sums to +0.0010. Latest 5 min ago.
        seedShadowRun(draftA, true,  true, new BigDecimal("0.0050"), new BigDecimal("0.0040"), 30);
        seedShadowRun(draftA, false, true, new BigDecimal("0.0050"), new BigDecimal("0.0050"), 5);

        // Draft B — 1 shadow run: fidelity match, output mismatch; -0.0030 cost
        // delta. Latest 1 min ago, so should sort before A.
        seedShadowRun(draftB, true, false, new BigDecimal("0.0020"), new BigDecimal("0.0050"), 1);

        ApiResponse<List<ShadowRunAggregation>> resp = controller.listAggregations();
        assertThat(resp.isSuccess()).isTrue();
        List<ShadowRunAggregation> rows = resp.getData();
        assertThat(rows).hasSize(2);

        // B first (latest_at = 1m ago), A second (latest_at = 5m ago).
        ShadowRunAggregation first = rows.get(0);
        assertThat(first.getDraftId()).isEqualTo(draftB);
        assertThat(first.getDraftSkillCode()).isEqualTo("auto.beta");
        assertThat(first.getRunCount()).isEqualTo(1L);
        assertThat(first.getFidelitySamples()).isEqualTo(1L);
        assertThat(first.getOutputSamples()).isEqualTo(1L);
        assertThat(first.getFidelityMatchRate()).isEqualTo(1.0);
        assertThat(first.getOutputMatchRate()).isEqualTo(0.0);
        assertThat(first.getCostDelta())
                .isEqualByComparingTo(new BigDecimal("-0.0030"));

        ShadowRunAggregation second = rows.get(1);
        assertThat(second.getDraftId()).isEqualTo(draftA);
        assertThat(second.getRunCount()).isEqualTo(2L);
        assertThat(second.getFidelitySamples()).isEqualTo(2L);
        assertThat(second.getOutputSamples()).isEqualTo(2L);
        // 1 of 2 fidelity = 0.5; both output matches = 1.0.
        assertThat(second.getFidelityMatchRate()).isEqualTo(0.5);
        assertThat(second.getOutputMatchRate()).isEqualTo(1.0);
        // (0.0050 + 0.0050) - (0.0040 + 0.0050) = 0.0010
        assertThat(second.getCostDelta())
                .isCloseTo(new BigDecimal("0.0010"), within(new BigDecimal("0.00005")));
    }

    // =========================================================================
    // Drilldown
    // =========================================================================

    @Test
    @DisplayName("drilldown: paginated, latest-first, returns rows for the requested draft only")
    void drilldown_paginated_returnsLatestFirst() {
        String draftA = seedDraft("auto.drill", "SHADOW_RUNNING");
        String draftB = seedDraft("auto.other", "SHADOW_RUNNING");

        // Three runs on A, one on B. Different ageMinutesAgo so ordering is
        // deterministic.
        String oldA = seedShadowRun(draftA, true, true,
                new BigDecimal("0.0010"), new BigDecimal("0.0010"), 30);
        String midA = seedShadowRun(draftA, false, true,
                new BigDecimal("0.0015"), new BigDecimal("0.0010"), 15);
        String newA = seedShadowRun(draftA, true, false,
                new BigDecimal("0.0020"), new BigDecimal("0.0010"), 1);
        seedShadowRun(draftB, true, true,
                new BigDecimal("0.0001"), new BigDecimal("0.0001"), 5);

        ApiResponse<List<ShadowRunListItem>> page0 = controller.listForDraft(draftA, 0, 2);
        assertThat(page0.isSuccess()).isTrue();
        List<ShadowRunListItem> p0 = page0.getData();
        assertThat(p0).hasSize(2);
        assertThat(p0.get(0).getPid()).isEqualTo(newA);
        assertThat(p0.get(1).getPid()).isEqualTo(midA);
        // None of B's rows should leak.
        assertThat(p0).allSatisfy(r -> assertThat(r.getDraftId()).isEqualTo(draftA));

        ApiResponse<List<ShadowRunListItem>> page1 = controller.listForDraft(draftA, 1, 2);
        List<ShadowRunListItem> p1 = page1.getData();
        assertThat(p1).hasSize(1);
        assertThat(p1.get(0).getPid()).isEqualTo(oldA);
    }

    @Test
    @DisplayName("drilldown: missing draftId yields 400; unknown id 404")
    void drilldown_validation() {
        ApiResponse<List<ShadowRunListItem>> blank = controller.listForDraft("", 0, 20);
        assertThat(blank.getCode()).isEqualTo("400");

        ApiResponse<List<ShadowRunListItem>> unknown = controller.listForDraft(
                "DRAFT_does_not_exist", 0, 20);
        assertThat(unknown.getCode()).isEqualTo("404");
        assertThat(unknown.getMessage()).isEqualTo("draft_not_found");
    }

    // =========================================================================
    // Detail
    // =========================================================================

    @Test
    @DisplayName("detail: returns shadow + original outputs and diff text")
    void detail_returnsBothOutputs() {
        String draft = seedDraft("auto.detail", "SHADOW_RUNNING");
        // Output mismatch case — output_diff JSONB is populated.
        String run = seedShadowRun(draft, true, false,
                new BigDecimal("0.0030"), new BigDecimal("0.0010"));

        ApiResponse<ShadowRunListItem> resp = controller.detail(run);
        assertThat(resp.isSuccess()).isTrue();
        ShadowRunListItem r = resp.getData();
        assertThat(r.getPid()).isEqualTo(run);
        assertThat(r.getDraftId()).isEqualTo(draft);
        assertThat(r.getShadowStatus()).isEqualTo("success");
        assertThat(r.getOriginalStatus()).isEqualTo("success");
        assertThat(r.getOutputMatch()).isFalse();
        assertThat(r.getFidelityMatch()).isTrue();
        assertThat(r.getShadowDurationMs()).isEqualTo(1200L);
        assertThat(r.getOriginalDurationMs()).isEqualTo(1500L);
        assertThat(r.getShadowCostUsd()).isEqualByComparingTo(new BigDecimal("0.0030"));
        assertThat(r.getOriginalCostUsd()).isEqualByComparingTo(new BigDecimal("0.0010"));
        assertThat(r.getOutputDiff())
                .contains("\"path\"")
                .contains("/result")
                .contains("shadow")
                .contains("production");
    }

    @Test
    @DisplayName("detail: unknown id yields 404")
    void detail_unknownId_returns404() {
        ApiResponse<ShadowRunListItem> resp = controller.detail("RUN_does_not_exist");
        assertThat(resp.getCode()).isEqualTo("404");
        assertThat(resp.getMessage()).isEqualTo("shadow_run_not_found");
    }

    // =========================================================================
    // Tenant isolation
    // =========================================================================

    @Test
    @DisplayName("tenant isolation — other tenant's shadow runs invisible to caller")
    void tenant_isolation_otherTenantShadowRunInvisible() {
        Long otherTenant = TestIdGenerator.uniqueTenantId();
        String otherDraft = UniqueIdGenerator.generate();
        String otherRun = UniqueIdGenerator.generate();
        try {
            jdbc.update(
                    "INSERT INTO ab_agent_skill_draft " +
                            "(pid, tenant_id, draft_skill_code, contract_yaml, " +
                            " source_pattern_hash, status, created_at) " +
                            "VALUES (?, ?, 'auto.foreign', 'x:1', 'h', 'SHADOW_RUNNING', NOW())",
                    otherDraft, otherTenant);
            jdbc.update(
                    "INSERT INTO ab_agent_shadow_run " +
                            "(pid, tenant_id, draft_id, original_run_id, " +
                            " shadow_status, output_match, fidelity_match, created_at) " +
                            "VALUES (?, ?, ?, ?, 'success', TRUE, TRUE, NOW())",
                    otherRun, otherTenant, otherDraft, UniqueIdGenerator.generate());

            // Aggregations for the caller tenant: empty.
            ApiResponse<List<ShadowRunAggregation>> aggs = controller.listAggregations();
            assertThat(aggs.getData()).isEmpty();

            // Drilldown by the foreign draft id: 404 (must NOT leak).
            ApiResponse<List<ShadowRunListItem>> drill = controller.listForDraft(otherDraft, 0, 20);
            assertThat(drill.getCode()).isEqualTo("404");

            // Detail by the foreign shadow_run pid: 404.
            ApiResponse<ShadowRunListItem> det = controller.detail(otherRun);
            assertThat(det.getCode()).isEqualTo("404");
            assertThat(det.getData()).isNull();
        } finally {
            jdbc.update("DELETE FROM ab_agent_shadow_run WHERE tenant_id = ?", otherTenant);
            jdbc.update("DELETE FROM ab_agent_skill_draft WHERE tenant_id = ?", otherTenant);
        }
    }

    // =========================================================================
    // Empty
    // =========================================================================

    @Test
    @DisplayName("empty fixture: aggregations and drilldown return empty list, NOT 404")
    void emptyFixture_returnsEmptyList() {
        ApiResponse<List<ShadowRunAggregation>> aggs = controller.listAggregations();
        assertThat(aggs.isSuccess()).isTrue();
        assertThat(aggs.getData()).isEmpty();

        // Drilldown with a missing draft id is 404 — but a draft that exists
        // with zero shadow runs returns []. Verify that:
        String draft = seedDraft("auto.empty", "SHADOW_RUNNING");
        ApiResponse<List<ShadowRunListItem>> drill = controller.listForDraft(draft, 0, 20);
        assertThat(drill.isSuccess()).isTrue();
        assertThat(drill.getData()).isEmpty();
    }
}
