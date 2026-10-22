package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.CapabilityViewService;
import com.auraboot.framework.agent.service.ToolDryRunService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ToolDryRunService — versioning, sandbox transaction, and plan validation.
 *
 * Covers: non-existent tool returns error, dryRun result structure, toolCode/dryRun flags,
 * version mismatch warning, sandboxRun always sets sandbox=true, dryRunPlan step count,
 * and plan with missing toolCode returns error per-step.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ToolDryRunServiceTest extends BaseIntegrationTest {

    @Autowired
    private ToolDryRunService toolDryRunService;

    @Autowired
    private CapabilityViewService capabilityViewService;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    private Long tenantId;

    /** A real ACTIVE tool code loaded from ab_agent_tool for the test tenant. */
    private String testToolCode;

    @BeforeEach
    void setup() {
        tenantId = getTestTenant().getId();

        // Find a real ACTIVE tool to use in tests
        List<Map<String, Object>> tools = dynamicDataMapper.selectByQueryWithoutTenant(
                "SELECT tool_code FROM ab_agent_tool " +
                "WHERE tenant_id = #{params.tenantId} " +
                "AND tool_status = 'active' " +
                "AND deleted_flag = FALSE " +
                "LIMIT 1",
                Map.of("tenantId", tenantId)
        );
        if (!tools.isEmpty()) {
            testToolCode = (String) tools.get(0).get("tool_code");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1: non-existent tool code produces "Tool not found" error
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void dryRun_nonExistentTool_returnsToolNotFoundError() {
        String fakeCode = "nonexistent_tool_xyz_" + System.currentTimeMillis();
        Map<String, Object> result = toolDryRunService.dryRun(tenantId, fakeCode, Map.of());

        assertNotNull(result, "dryRun must return a non-null result map");
        assertEquals(fakeCode, result.get("toolCode"), "toolCode must be echoed back in the result");
        assertFalse((Boolean) result.get("valid"), "non-existent tool must produce valid=false");

        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) result.get("errors");
        assertNotNull(errors, "errors list must be present");
        assertFalse(errors.isEmpty(), "errors must contain at least one entry");
        assertTrue(errors.get(0).contains("not found"),
                "Error message must contain 'not found': " + errors.get(0));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2: dryRun result always contains dryRun=true flag and toolCode
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void dryRun_alwaysIncludesDryRunFlagAndToolCode() {
        String anyCode = testToolCode != null ? testToolCode : "any_tool";
        Map<String, Object> result = toolDryRunService.dryRun(tenantId, anyCode, Map.of());

        assertNotNull(result, "dryRun must return a non-null result");
        assertEquals(anyCode, result.get("toolCode"), "toolCode must be present in result");
        assertEquals(Boolean.TRUE, result.get("dryRun"), "dryRun flag must be true");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3: dryRun on a real tool returns tool_version field
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void dryRun_realTool_includesToolVersion() {
        if (testToolCode == null) {
            return; // No tools available — skip
        }

        Map<String, Object> result = toolDryRunService.dryRun(tenantId, testToolCode, Map.of());

        assertTrue(result.containsKey("tool_version"),
                "dryRun result must include tool_version for real tools");
        assertInstanceOf(Number.class, result.get("tool_version"),
                "tool_version must be a numeric value");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 4: _expectedVersion mismatch produces a version warning
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void dryRun_versionMismatch_producesWarning() {
        if (testToolCode == null) {
            return; // No tools available — skip
        }

        Map<String, Object> input = new HashMap<>();
        input.put("_expectedVersion", Integer.MAX_VALUE); // Guaranteed mismatch

        Map<String, Object> result = toolDryRunService.dryRun(tenantId, testToolCode, input);

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.getOrDefault("warnings", List.of());
        boolean hasVersionWarning = warnings.stream()
                .anyMatch(w -> w.contains("version") || w.contains("Version"));

        assertTrue(hasVersionWarning,
                "Version mismatch must produce a warning containing 'version'. Warnings: " + warnings);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 5: _expectedVersion matching actual version produces no version warning
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void dryRun_versionMatch_producesNoVersionWarning() {
        if (testToolCode == null) {
            return; // No tools available — skip
        }

        // First get the actual version
        Map<String, Object> probe = toolDryRunService.dryRun(tenantId, testToolCode, Map.of());
        Object versionObj = probe.get("tool_version");
        if (!(versionObj instanceof Number)) {
            return; // Cannot determine version — skip
        }
        int actualVersion = ((Number) versionObj).intValue();

        // Now call with matching version
        Map<String, Object> input = Map.of("_expectedVersion", actualVersion);
        Map<String, Object> result = toolDryRunService.dryRun(tenantId, testToolCode, input);

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.getOrDefault("warnings", List.of());
        boolean hasVersionWarning = warnings.stream()
                .anyMatch(w -> w.contains("version") || w.contains("Version"));

        assertFalse(hasVersionWarning,
                "Matching version must NOT produce a version warning. Warnings: " + warnings);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 6: sandboxRun always sets sandbox=true in result
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    void sandboxRun_alwaysSetsSandboxFlag() {
        if (testToolCode == null) {
            return; // No tools available — skip
        }

        Map<String, Object> result = toolDryRunService.sandboxRun(tenantId, testToolCode, Map.of());

        assertNotNull(result, "sandboxRun must return a non-null result");
        assertEquals(Boolean.TRUE, result.get("sandbox"),
                "sandboxRun result must always contain sandbox=true");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 7: sandboxRun result contains validation sub-map
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    void sandboxRun_containsValidationSubMap() {
        if (testToolCode == null) {
            return; // No tools available — skip
        }

        Map<String, Object> result = toolDryRunService.sandboxRun(tenantId, testToolCode, Map.of());

        assertTrue(result.containsKey("validation"),
                "sandboxRun result must contain 'validation' sub-map");
        assertInstanceOf(Map.class, result.get("validation"),
                "validation field must be a Map");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 8: sandboxRun does NOT commit data (transaction is rolled back)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(8)
    void sandboxRun_doesNotPersistData() {
        if (testToolCode == null) {
            return; // No tools available — skip
        }

        // Count ab_agent_observation rows before and after sandboxRun.
        // SandboxContext suppresses events, so no observations should be written.
        long before = countObservations();
        toolDryRunService.sandboxRun(tenantId, testToolCode, Map.of());
        long after = countObservations();

        assertEquals(before, after,
                "sandboxRun must not persist observation rows (SandboxContext should suppress events)");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 9: dryRunPlan returns correct totalSteps
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(9)
    void dryRunPlan_returnsCorrectTotalSteps() {
        if (testToolCode == null) {
            return; // No tools available — skip
        }

        List<Map<String, Object>> steps = List.of(
                Map.of("toolCode", testToolCode, "input", Map.of()),
                Map.of("toolCode", testToolCode, "input", Map.of())
        );

        Map<String, Object> result = toolDryRunService.dryRunPlan(tenantId, steps);

        assertNotNull(result, "dryRunPlan must return a non-null result");
        assertEquals(2, result.get("totalSteps"),
                "dryRunPlan must report totalSteps = 2");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 10: dryRunPlan step with missing toolCode produces per-step error
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void dryRunPlan_stepWithMissingToolCode_producesPerStepError() {
        List<Map<String, Object>> steps = List.of(
                Map.of("input", Map.of())  // toolCode intentionally omitted
        );

        Map<String, Object> result = toolDryRunService.dryRunPlan(tenantId, steps);

        assertNotNull(result, "dryRunPlan must return a non-null result");
        assertEquals(Boolean.FALSE, result.get("planValid"),
                "Plan with missing toolCode must be invalid");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stepResults = (List<Map<String, Object>>) result.get("steps");
        assertNotNull(stepResults, "steps list must be present");
        assertFalse(stepResults.isEmpty(), "steps list must be non-empty");

        @SuppressWarnings("unchecked")
        List<String> stepErrors = (List<String>) stepResults.get(0).getOrDefault("errors", List.of());
        assertFalse(stepErrors.isEmpty(), "Step with missing toolCode must have errors");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 11: dryRunPlan with empty steps returns planValid=true and totalSteps=0
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(11)
    void dryRunPlan_emptySteps_returnsTruePlanValid() {
        Map<String, Object> result = toolDryRunService.dryRunPlan(tenantId, List.of());

        assertNotNull(result, "dryRunPlan must return a non-null result for empty plan");
        assertEquals(Boolean.TRUE, result.get("planValid"),
                "Empty plan must be considered valid");
        assertEquals(0, result.get("totalSteps"),
                "Empty plan must have totalSteps=0");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 12: dryRun with null input map does not throw
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(12)
    void dryRun_nullInput_doesNotThrow() {
        String anyCode = testToolCode != null ? testToolCode : "any_tool";
        assertDoesNotThrow(
                () -> toolDryRunService.dryRun(tenantId, anyCode, null),
                "dryRun must handle null input map without throwing"
        );
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private long countObservations() {
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                "SELECT COUNT(*) AS cnt FROM ab_agent_observation " +
                "WHERE tenant_id = #{params.tenantId}",
                Map.of("tenantId", tenantId)
        );
        if (rows.isEmpty()) return 0L;
        Object cnt = rows.get(0).get("cnt");
        return cnt instanceof Number n ? n.longValue() : 0L;
    }
}
