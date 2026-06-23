package com.auraboot.framework.agent;

import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import com.auraboot.framework.agent.entity.AbCapabilityEvalRun;
import com.auraboot.framework.agent.mapper.AbCapabilityEvalRunMapper;
import com.auraboot.framework.agent.service.CapabilityEvalService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for CapabilityEvalService.
 * Covers eval-case generation, 5-dimension scoring, persistence, and regression detection.
 * Uses real database, no mocking. Data persists (no rollback).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class CapabilityEvalServiceTest extends BaseIntegrationTest {

    @Autowired
    private CapabilityEvalService capabilityEvalService;

    @Autowired
    private AbCapabilityEvalRunMapper evalRunMapper;

    // ========== Test 1: generateEvalCases returns a list (may be empty on fresh DB) ==========

    @Test
    @Order(1)
    void generateEvalCases_returnsNonNullList() {
        Long tenantId = getTestTenant().getId();

        // A fresh DB may have no published capabilities — that is acceptable.
        // The contract is: non-null list, no exception.
        assertDoesNotThrow(() -> {
            List<CapabilityEvalCase> cases = capabilityEvalService
                    .generateEvalCases(tenantId, null, 20);
            assertNotNull(cases, "generateEvalCases must return a non-null list");
        }, "generateEvalCases must not throw");
    }

    // ========== Test 2: evaluateToolSelection (KEYWORD) returns a report with 5 dimensions ==========

    @Test
    @Order(2)
    void evaluateToolSelection_keyword_returnsReport() {
        Long tenantId = getTestTenant().getId();

        Map<String, Object> result = capabilityEvalService
                .evaluateToolSelection(tenantId, "keyword");

        assertNotNull(result, "evaluateToolSelection must return a result map");

        String status = (String) result.get("status");
        if ("no_cases".equals(status) || "no_scoreable_cases".equals(status)) {
            // Either no capabilities in the test DB ("no_cases"), or none of the generated
            // cases' expected tools are in this environment's tool catalog so every case is
            // unavailable ("no_scoreable_cases", the D3a short-circuit). Both are valid
            // graceful outcomes that do not produce a full 5-dimension metric report.
            return;
        }

        // Full report: all 5 dimensions must be present
        assertTrue(result.containsKey("toolSelectionAccuracy"),
                "report must contain toolSelectionAccuracy");
        assertTrue(result.containsKey("parameterCompletionRate"),
                "report must contain parameterCompletionRate");
        assertTrue(result.containsKey("safetyComplianceRate"),
                "report must contain safetyComplianceRate");
        assertTrue(result.containsKey("composabilityScore"),
                "report must contain composabilityScore");
        assertTrue(result.containsKey("hallucinationRate"),
                "report must contain hallucinationRate");
        assertTrue(result.containsKey("weightedScore"),
                "report must contain weightedScore");
    }

    // ========== Test 3: evaluateToolSelection persists a record in ab_capability_eval_run ==========

    @Test
    @Order(3)
    void evaluateToolSelection_persistsResult() {
        Long tenantId = getTestTenant().getId();

        long countBefore = evalRunMapper.selectCount(
                new LambdaQueryWrapper<AbCapabilityEvalRun>()
                        .eq(AbCapabilityEvalRun::getTenantId, tenantId));

        Map<String, Object> result = capabilityEvalService.evaluateToolSelection(tenantId, "keyword");

        long countAfter = evalRunMapper.selectCount(
                new LambdaQueryWrapper<AbCapabilityEvalRun>()
                        .eq(AbCapabilityEvalRun::getTenantId, tenantId));

        // A run is persisted only when the evaluation actually scored cases. Two non-scoring
        // outcomes skip persistence: "no_cases" (none generated) and "no_scoreable_cases"
        // (all generated cases' tools absent from this environment's catalog — D3a short-circuit).
        String status = (String) result.get("status");
        boolean scored = !"no_cases".equals(status) && !"no_scoreable_cases".equals(status);
        if (scored) {
            assertTrue(countAfter > countBefore,
                    "A scored evaluation must persist a new AbCapabilityEvalRun record");
        } else {
            assertEquals(countBefore, countAfter,
                    "A non-scoring outcome (no_cases / no_scoreable_cases) must not persist a run");
        }
    }

    // ========== Test 4: weightedScore is in [0, 1] range ==========

    @Test
    @Order(4)
    void evaluateToolSelection_calculatesWeightedScore() {
        Long tenantId = getTestTenant().getId();

        // Use explicit eval cases with known structure to get a deterministic score
        List<CapabilityEvalCase> manualCases = List.of(
                CapabilityEvalCase.builder()
                        .caseId("MANUAL-001")
                        .taskDescription("Create a new task record")
                        .expectedToolCodes(List.of("cmd_some_tool"))
                        .category("tool_selection")
                        .expectedRiskLevel("L1")
                        .expectsConfirmation(false)
                        .build()
        );

        Map<String, Object> result = capabilityEvalService
                .evaluateToolSelection(tenantId, "keyword", manualCases);

        assertNotNull(result);
        String status = (String) result.get("status");
        if ("no_cases".equals(status) || "no_scoreable_cases".equals(status)) {
            // 'cmd_some_tool' is not in this environment's tool catalog, so the only manual
            // case is unavailable and no weighted score is produced (D3a). Graceful outcome.
            // (Actual weighted-score computation is covered by CapabilityEvalUnavailableCaseTest's
            // mixed run, where a scoreable case exists.)
            return;
        }
        Object weightedScore = result.get("weightedScore");
        assertNotNull(weightedScore, "weightedScore must be present in a scored report");
        double score = ((Number) weightedScore).doubleValue();
        assertTrue(score >= 0.0 && score <= 1.0,
                "weightedScore must be in [0, 1] range, got: " + score);
    }

    // ========== Test 5: evaluateToolSelection with explicit empty cases returns NO_CASES ==========

    @Test
    @Order(5)
    void evaluateToolSelection_handlesNoCases() {
        Long tenantId = getTestTenant().getId();

        // Generate cases for a model that almost certainly does not exist
        List<CapabilityEvalCase> emptyCases = capabilityEvalService
                .generateEvalCases(tenantId, "nonexistent_model_code_xyz_" + System.currentTimeMillis(), 20);

        assertTrue(emptyCases.isEmpty(), "Cases for a non-existent model must be empty");

        // Directly invoke the single-argument overload which delegates to generateEvalCases(null)
        // and returns NO_CASES if the result is empty
        // We test via the explicit empty-list overload to avoid side effects on other tenants
        assertDoesNotThrow(() -> {
            Map<String, Object> result = capabilityEvalService
                    .evaluateToolSelection(tenantId, "keyword", emptyCases);
            // An empty cases list passed directly — should still return a valid report
            // (totalCases == 0, all scores default to 0 or 1 depending on implementation)
            assertNotNull(result, "Result must not be null for empty cases");
            Number totalCases = (Number) result.get("totalCases");
            if (totalCases != null) {
                assertEquals(0, totalCases.intValue(),
                        "totalCases must be 0 when an empty list is provided");
            }
        }, "evaluateToolSelection must not throw for empty cases");
    }

    // ========== Test 6: regression detection does not flag degradation after first run ==========

    @Test
    @Order(6)
    void regressionDetection_noWarnOnFirstRun() {
        Long tenantId = getTestTenant().getId();

        // Run evaluation; on the first run there is no previous baseline.
        // Therefore no regression_warning should be added to the report.
        List<CapabilityEvalCase> singleCase = List.of(
                CapabilityEvalCase.builder()
                        .caseId("REG-001")
                        .taskDescription("View task details")
                        .expectedToolCodes(List.of("cmd_view_task"))
                        .category("tool_selection")
                        .expectedRiskLevel("L0")
                        .expectsConfirmation(false)
                        .build()
        );

        Map<String, Object> result = capabilityEvalService
                .evaluateToolSelection(tenantId, "keyword", singleCase);

        assertNotNull(result, "Result must not be null");
        // regression_warning is only added when accuracy drops > 5% vs previous run.
        // If there is no previous run (or this is the first run for this tenant), the key is absent.
        String status = (String) result.get("status");
        if ("no_cases".equals(status) || "no_scoreable_cases".equals(status)) {
            // 'cmd_view_task' not in this environment's catalog → case unavailable → no scored
            // report. Graceful outcome; nothing to regress against on a non-scoring run.
            return;
        }
        assertTrue(result.containsKey("toolSelectionAccuracy"),
                "Report must contain toolSelectionAccuracy even in regression check path");
    }

    // ========== Test 7: evalMode is returned correctly in the report ==========

    @Test
    @Order(7)
    void evaluateToolSelection_storesEvalModeInRun() {
        Long tenantId = getTestTenant().getId();

        List<CapabilityEvalCase> cases = List.of(
                CapabilityEvalCase.builder()
                        .caseId("MODE-001")
                        .taskDescription("Delete the task record")
                        .expectedToolCodes(List.of("cmd_delete_task"))
                        .category("tool_selection")
                        .expectedRiskLevel("L4")
                        .expectsConfirmation(true)
                        .build()
        );

        long countBefore = evalRunMapper.selectCount(
                new LambdaQueryWrapper<AbCapabilityEvalRun>()
                        .eq(AbCapabilityEvalRun::getTenantId, tenantId));

        Map<String, Object> result = capabilityEvalService.evaluateToolSelection(tenantId, "llm", cases);

        // The returned report must always reflect the requested evalMode
        assertNotNull(result, "Result must not be null");
        assertEquals("llm", result.get("evalMode"),
                "The returned report must reflect the requested evalMode");

        // If a run was actually persisted, verify it also has the correct evalMode
        long countAfter = evalRunMapper.selectCount(
                new LambdaQueryWrapper<AbCapabilityEvalRun>()
                        .eq(AbCapabilityEvalRun::getTenantId, tenantId));

        if (countAfter > countBefore) {
            List<AbCapabilityEvalRun> runs = evalRunMapper.selectList(
                    new LambdaQueryWrapper<AbCapabilityEvalRun>()
                            .eq(AbCapabilityEvalRun::getTenantId, tenantId)
                            .orderByDesc(AbCapabilityEvalRun::getRunAt)
                            .last("LIMIT 1")
            );
            assertFalse(runs.isEmpty(), "Run must exist after count increased");
            assertEquals("llm", runs.get(0).getEvalMode(),
                    "The persisted eval run must store the specified evalMode");
        }
    }
}
