package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.entity.BpmNodeHook;
import com.auraboot.framework.bpm.mapper.BpmNodeHookMapper;
import com.auraboot.framework.bpm.service.BpmNodeHookService;
import com.auraboot.framework.bpm.service.BpmNodeHookService.HookExecutionResult;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for BpmNodeHookService covering CRUD operations,
 * pre-check/post-action hook execution, fail strategies (BLOCK/WARN/SKIP),
 * and hook type dispatching (REST_CALL, SCRIPT, DROOLS_RULE, unknown).
 *
 * @author AuraBoot Team
 */
@Slf4j
@DisplayName("BPM Node Hook Service Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BpmNodeHookServiceTest extends BaseIntegrationTest {

    @Autowired
    private BpmNodeHookService hookService;

    @Autowired
    private BpmNodeHookMapper hookMapper;

    // ==================== Helper Methods ====================

    private BpmNodeHook buildHook(String processKey, String nodeId, String hookType,
                                  Map<String, Object> config, String failStrategy,
                                  Boolean async, Integer executionOrder) {
        return BpmNodeHook.builder()
                .processKey(processKey)
                .nodeId(nodeId)
                .hookType(hookType)
                .hookConfig(config)
                .failStrategy(failStrategy)
                .async(async)
                .enabled(true)
                .executionOrder(executionOrder)
                .build();
    }

    // ==================== Test Cases ====================

    @Test
    @Order(1)
    @DisplayName("HOOK-01: Create hook persists with auto-generated pid and tenantId")
    void hook01_createHookPersists() {
        BpmNodeHook hook = buildHook(
                "proc-create-01", "node-01", "pre_check",
                Map.of("type", "script", "script", "true"),
                "block", false, 1
        );

        BpmNodeHook created = hookService.createHook(hook);

        assertNotNull(created.getPid(), "PID should be auto-generated");
        assertEquals(getTestTenant().getId(), created.getTenantId(), "TenantId should match current tenant");
        assertNotNull(created.getCreatedAt(), "createdAt should be set");
        assertNotNull(created.getUpdatedAt(), "updatedAt should be set");

        // Verify persisted via mapper
        BpmNodeHook found = hookMapper.findByPid(created.getPid());
        assertNotNull(found, "Hook should be found by PID after creation");
        assertEquals("proc-create-01", found.getProcessKey());
        assertEquals("node-01", found.getNodeId());
        assertEquals("pre_check", found.getHookType());

        log.info("HOOK-01 PASSED: Hook created with pid={}, tenantId={}", created.getPid(), created.getTenantId());
    }

    @Test
    @Order(2)
    @DisplayName("HOOK-02: Update config and failStrategy reflected in DB")
    void hook02_updateHookChangesReflected() {
        BpmNodeHook hook = buildHook(
                "proc-update-02", "node-02", "pre_check",
                Map.of("type", "script", "script", "true"),
                "block", false, 1
        );
        BpmNodeHook created = hookService.createHook(hook);

        // Update config and failStrategy
        BpmNodeHook update = new BpmNodeHook();
        update.setHookConfig(Map.of("type", "script", "script", "return false"));
        update.setFailStrategy("warn");

        BpmNodeHook updated = hookService.updateHook(created.getPid(), update);

        assertEquals("warn", updated.getFailStrategy(), "failStrategy should be updated to WARN");
        assertEquals("script", updated.getHookConfig().get("type"), "Config type should remain SCRIPT");
        assertEquals("return false", updated.getHookConfig().get("script"), "Config script should be updated");

        // Verify via mapper
        BpmNodeHook fromDb = hookMapper.findByPid(created.getPid());
        assertEquals("warn", fromDb.getFailStrategy());

        log.info("HOOK-02 PASSED: Hook updated successfully");
    }

    @Test
    @Order(3)
    @DisplayName("HOOK-03: Delete removes hook from DB (soft delete)")
    void hook03_deleteRemovesFromDb() {
        BpmNodeHook hook = buildHook(
                "proc-delete-03", "node-03", "pre_check",
                Map.of("type", "script", "script", "true"),
                "block", false, 1
        );
        BpmNodeHook created = hookService.createHook(hook);
        String pid = created.getPid();

        // Verify exists
        assertNotNull(hookMapper.findByPid(pid), "Hook should exist before delete");

        // Delete
        hookService.deleteHook(pid);

        // Verify gone (findByPid excludes soft-deleted)
        assertNull(hookMapper.findByPid(pid), "Hook should not be found after delete");

        log.info("HOOK-03 PASSED: Hook deleted, pid={}", pid);
    }

    @Test
    @Order(4)
    @DisplayName("HOOK-04: getHooks filters by hookType correctly")
    void hook04_getHooksFiltersByType() {
        String processKey = "proc-filter-04-" + System.nanoTime();
        String nodeId = "node-04";

        // Create PRE_CHECK hook
        hookService.createHook(buildHook(
                processKey, nodeId, "pre_check",
                Map.of("type", "script", "script", "true"),
                "block", false, 1
        ));

        // Create POST_ACTION hook
        hookService.createHook(buildHook(
                processKey, nodeId, "post_action",
                Map.of("type", "script", "script", "true"),
                "block", false, 1
        ));

        // Query PRE_CHECK only
        List<BpmNodeHook> preChecks = hookService.getHooks(processKey, nodeId, "pre_check");
        assertEquals(1, preChecks.size(), "Should find exactly 1 PRE_CHECK hook");
        assertEquals("pre_check", preChecks.getFirst().getHookType());

        // Query POST_ACTION only
        List<BpmNodeHook> postActions = hookService.getHooks(processKey, nodeId, "post_action");
        assertEquals(1, postActions.size(), "Should find exactly 1 POST_ACTION hook");
        assertEquals("post_action", postActions.getFirst().getHookType());

        log.info("HOOK-04 PASSED: getHooks correctly filters by hookType");
    }

    @Test
    @Order(5)
    @DisplayName("HOOK-05: PRE_CHECK BLOCK stops on REST_CALL failure → passed=false")
    void hook05_preCheckBlockStopsOnFailure() {
        String processKey = "proc-block-05-" + System.nanoTime();
        String nodeId = "node-05";

        // REST_CALL to TEST-NET-1 (non-routable, or SsrfValidator rejects → exception → BLOCK catches)
        hookService.createHook(buildHook(
                processKey, nodeId, "pre_check",
                Map.of("type", "rest_call", "url", "http://192.0.2.1:9999/unreachable", "method", "post"),
                "block", false, 1
        ));

        HookExecutionResult result = hookService.executePreChecks(processKey, nodeId, Map.of("key", "value"));

        assertFalse(result.passed(), "PRE_CHECK with BLOCK + failed REST_CALL should return passed=false");
        assertNotNull(result.message(), "Message should be non-null on BLOCK failure");

        log.info("HOOK-05 PASSED: BLOCK strategy stops execution, message={}", result.message());
    }

    @Test
    @Order(6)
    @DisplayName("HOOK-06: PRE_CHECK WARN logs but passes on REST_CALL failure")
    void hook06_preCheckWarnPassesOnFailure() {
        String processKey = "proc-warn-06-" + System.nanoTime();
        String nodeId = "node-06";

        hookService.createHook(buildHook(
                processKey, nodeId, "pre_check",
                Map.of("type", "rest_call", "url", "http://192.0.2.1:9999/unreachable", "method", "post"),
                "warn", false, 1
        ));

        HookExecutionResult result = hookService.executePreChecks(processKey, nodeId, Map.of("key", "value"));

        assertTrue(result.passed(), "PRE_CHECK with WARN strategy should still pass");

        log.info("HOOK-06 PASSED: WARN strategy logs warning but continues");
    }

    @Test
    @Order(7)
    @DisplayName("HOOK-07: PRE_CHECK SKIP continues silently on failure")
    void hook07_preCheckSkipContinues() {
        String processKey = "proc-skip-07-" + System.nanoTime();
        String nodeId = "node-07";

        hookService.createHook(buildHook(
                processKey, nodeId, "pre_check",
                Map.of("type", "rest_call", "url", "http://192.0.2.1:9999/unreachable", "method", "post"),
                "skip", false, 1
        ));

        HookExecutionResult result = hookService.executePreChecks(processKey, nodeId, Map.of("key", "value"));

        assertTrue(result.passed(), "PRE_CHECK with SKIP strategy should pass");
        assertNull(result.message(), "Message should be null when all checks pass");

        log.info("HOOK-07 PASSED: SKIP strategy continues silently");
    }

    @Test
    @Order(8)
    @DisplayName("HOOK-08: No hooks returns passed=true with null message")
    void hook08_noHooksReturnsPassed() {
        String processKey = "proc-nonexistent-08-" + System.nanoTime();

        HookExecutionResult result = hookService.executePreChecks(processKey, "no-node", Map.of());

        assertTrue(result.passed(), "Empty hooks should return passed=true");
        assertNull(result.message(), "Empty hooks should return null message");

        log.info("HOOK-08 PASSED: No hooks returns HookExecutionResult(true, null)");
    }

    @Test
    @Order(9)
    @DisplayName("HOOK-09: POST_ACTION sync with SCRIPT type executes without exception")
    void hook09_postActionSyncScript() {
        String processKey = "proc-postaction-09-" + System.nanoTime();
        String nodeId = "node-09";

        hookService.createHook(buildHook(
                processKey, nodeId, "post_action",
                Map.of("type", "script", "script", "true"),
                "block", false, 1
        ));

        // Should not throw
        assertDoesNotThrow(
                () -> hookService.executePostActions(processKey, nodeId, Map.of("key", "value")),
                "POST_ACTION with SCRIPT type should execute without exception"
        );

        log.info("HOOK-09 PASSED: POST_ACTION sync SCRIPT executes inline");
    }

    @Test
    @Order(10)
    @DisplayName("HOOK-10: POST_ACTION BLOCK with REST_CALL failure — REST_CALL catches internally, no exception propagated")
    void hook10_postActionBlockRestCallFailure() {
        String processKey = "proc-postblock-10-" + System.nanoTime();
        String nodeId = "node-10";

        hookService.createHook(buildHook(
                processKey, nodeId, "post_action",
                Map.of("type", "rest_call", "url", "http://192.0.2.1:9999/unreachable", "method", "post"),
                "block", false, 1
        ));

        // REST_CALL catches its own HTTP exception and returns false (not throws).
        // executePostActions only throws BusinessException if executeHook() throws an exception,
        // not when it returns false. So no exception is propagated here.
        assertDoesNotThrow(
                () -> hookService.executePostActions(processKey, nodeId, Map.of("key", "value")),
                "POST_ACTION with REST_CALL failure should not throw (REST_CALL catches internally)"
        );

        log.info("HOOK-10 PASSED: POST_ACTION BLOCK with REST_CALL failure — no exception (handled internally)");
    }

    @Test
    @Order(11)
    @DisplayName("HOOK-11: SCRIPT type always returns true (placeholder)")
    void hook11_scriptTypeReturnsTrue() {
        String processKey = "proc-script-11-" + System.nanoTime();
        String nodeId = "node-11";

        hookService.createHook(buildHook(
                processKey, nodeId, "pre_check",
                Map.of("type", "script", "script", "true"),
                "block", false, 1
        ));

        HookExecutionResult result = hookService.executePreChecks(processKey, nodeId, Map.of());

        assertTrue(result.passed(), "SCRIPT type should return true (placeholder implementation)");
        assertNull(result.message(), "Message should be null when hook passes");

        log.info("HOOK-11 PASSED: SCRIPT type returns true");
    }

    @Test
    @Order(12)
    @DisplayName("HOOK-12: Unknown hook type returns true (default case)")
    void hook12_unknownTypeReturnsTrue() {
        String processKey = "proc-unknown-12-" + System.nanoTime();
        String nodeId = "node-12";

        hookService.createHook(buildHook(
                processKey, nodeId, "pre_check",
                Map.of("type", "unknown_type"),
                "block", false, 1
        ));

        HookExecutionResult result = hookService.executePreChecks(processKey, nodeId, Map.of());

        assertTrue(result.passed(), "Unknown hook type should return true (default case)");
        assertNull(result.message(), "Message should be null for unknown type that returns true");

        log.info("HOOK-12 PASSED: Unknown hook type returns true");
    }

    @Test
    @Order(13)
    @DisplayName("HOOK-13: Execution order respected — hooks returned sorted by executionOrder ASC")
    void hook13_executionOrderRespected() {
        String processKey = "proc-order-13-" + System.nanoTime();
        String nodeId = "node-13";

        // Create hooks with out-of-order executionOrder values
        hookService.createHook(buildHook(
                processKey, nodeId, "pre_check",
                Map.of("type", "script", "script", "step3"),
                "block", false, 3
        ));
        hookService.createHook(buildHook(
                processKey, nodeId, "pre_check",
                Map.of("type", "script", "script", "step1"),
                "block", false, 1
        ));
        hookService.createHook(buildHook(
                processKey, nodeId, "pre_check",
                Map.of("type", "script", "script", "step2"),
                "block", false, 2
        ));

        List<BpmNodeHook> hooks = hookService.getHooks(processKey, nodeId, "pre_check");

        assertEquals(3, hooks.size(), "Should find all 3 hooks");
        assertEquals(1, hooks.get(0).getExecutionOrder(), "First hook should have executionOrder=1");
        assertEquals(2, hooks.get(1).getExecutionOrder(), "Second hook should have executionOrder=2");
        assertEquals(3, hooks.get(2).getExecutionOrder(), "Third hook should have executionOrder=3");

        // Also verify via script content to confirm order
        assertEquals("step1", hooks.get(0).getHookConfig().get("script"));
        assertEquals("step2", hooks.get(1).getHookConfig().get("script"));
        assertEquals("step3", hooks.get(2).getHookConfig().get("script"));

        log.info("HOOK-13 PASSED: Hooks returned in executionOrder ASC");
    }

    @Test
    @Order(14)
    @DisplayName("HOOK-14: DROOLS_RULE with nonexistent rule — BLOCK fails, WARN/SKIP passes")
    void hook14_droolsRuleNonexistentRule() {
        String processKey = "proc-drools-14-" + System.nanoTime();
        String nodeId = "node-14";

        // DROOLS_RULE with BLOCK strategy — nonexistent rule should throw → exception → BLOCK returns false
        hookService.createHook(buildHook(
                processKey, nodeId, "pre_check",
                Map.of("type", "drools_rule", "ruleCode", "nonexistent-rule-14"),
                "block", false, 1
        ));

        HookExecutionResult blockResult = hookService.executePreChecks(processKey, nodeId, Map.of());
        assertFalse(blockResult.passed(), "DROOLS_RULE with nonexistent rule + BLOCK should fail");
        assertNotNull(blockResult.message(), "Message should contain failure info");
        // Drools rule failure returns false (caught internally), triggering "Pre-check failed:" path
        assertTrue(blockResult.message().contains("Pre-check failed") || blockResult.message().contains("Pre-check error"),
                "Message should indicate failure, got: " + blockResult.message());

        // Now test WARN strategy with a different processKey
        String processKeyWarn = "proc-drools-14-warn-" + System.nanoTime();
        hookService.createHook(buildHook(
                processKeyWarn, nodeId, "pre_check",
                Map.of("type", "drools_rule", "ruleCode", "nonexistent-rule-14"),
                "warn", false, 1
        ));

        HookExecutionResult warnResult = hookService.executePreChecks(processKeyWarn, nodeId, Map.of());
        assertTrue(warnResult.passed(), "DROOLS_RULE with nonexistent rule + WARN should pass");

        log.info("HOOK-14 PASSED: DROOLS_RULE nonexistent rule — BLOCK={}, WARN={}",
                blockResult.passed(), warnResult.passed());
    }

    @Test
    @Order(15)
    @DisplayName("HOOK-15: PRE_CHECK BLOCK with exception returns passed=false with error message")
    void hook15_preCheckBlockWithException() {
        String processKey = "proc-exception-15-" + System.nanoTime();
        String nodeId = "node-15";

        // DROOLS_RULE with nonexistent rule → DroolsEngineService.evaluate throws IllegalArgumentException
        // → executeHook catches and returns false → BLOCK strategy → HookExecutionResult(false, "Pre-check error: ...")
        hookService.createHook(buildHook(
                processKey, nodeId, "pre_check",
                Map.of("type", "drools_rule", "ruleCode", "nonexistent-rule-15"),
                "block", false, 1
        ));

        HookExecutionResult result = hookService.executePreChecks(processKey, nodeId, Map.of("fact1", "value1"));

        assertFalse(result.passed(), "BLOCK strategy with exception should return passed=false");
        assertNotNull(result.message(), "Message should not be null");
        // The drools executeDroolsRule catches the exception and returns false,
        // so this follows the "hook returns false + BLOCK" path → "Pre-check failed: ..."
        // OR if the exception propagates up, it follows the catch(Exception) path → "Pre-check error: ..."
        assertTrue(
                result.message().contains("Pre-check failed") || result.message().contains("Pre-check error"),
                "Message should indicate pre-check failure or error, got: " + result.message()
        );

        log.info("HOOK-15 PASSED: BLOCK + exception → passed=false, message={}", result.message());
    }
}
