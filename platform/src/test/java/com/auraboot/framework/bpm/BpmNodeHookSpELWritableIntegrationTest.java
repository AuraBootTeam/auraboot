package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.entity.BpmNodeHook;
import com.auraboot.framework.bpm.service.BpmNodeHookService;
import com.auraboot.framework.bpm.service.BpmNodeHookService.HookExecutionResult;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for GAP-257: BpmNodeHookService SpEL context is writable,
 * and hook mutations propagate back to the caller's process variables map
 * (which at runtime is SmartEngine's {@code ExecutionContext.getRequest()}).
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>{@code #setVar(#vars, k, v)} function call — var visible on the map.</li>
 *   <li>{@code #vars['k'] = v} indexer mutation — var visible on the map.</li>
 *   <li>Script can read existing variables via {@code #vars['k']} / property style.</li>
 *   <li>Security guard: {@code T(java.lang.Runtime)} rejected as
 *       SpelEvaluationException, no side effects on the variables map.</li>
 *   <li>Security guard: {@code new java.io.File('/tmp/x')} constructor rejected.</li>
 * </ul>
 *
 * @author AuraBoot Team
 */
@Slf4j
@DisplayName("BPM Node Hook SpEL Writable Context Tests (GAP-257)")
class BpmNodeHookSpELWritableIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BpmNodeHookService hookService;

    private BpmNodeHook buildScriptHook(String processKey, String nodeId,
                                        String hookType, String script,
                                        String failStrategy) {
        return BpmNodeHook.builder()
                .processKey(processKey)
                .nodeId(nodeId)
                .hookType(hookType)
                .hookConfig(Map.of("type", "script", "script", script))
                .failStrategy(failStrategy)
                .async(false)
                .enabled(true)
                .executionOrder(1)
                .build();
    }

    @Test
    @DisplayName("SPEL-01: #setVar writes into caller's map and propagates")
    void spel01_setVarFunctionPropagates() {
        String processKey = "proc-spel-setvar-" + System.nanoTime();
        String nodeId = "node-spel-01";
        hookService.createHook(buildScriptHook(processKey, nodeId, "pre_check",
                "#setVar(#vars, 'approverRole', 'manager')", "block"));

        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 1000);

        HookExecutionResult result = hookService.executePreChecks(processKey, nodeId, vars);

        assertTrue(result.passed(), "Script mutation should not fail pre-check");
        assertEquals("manager", vars.get("approverRole"),
                "setVar() must write back to the caller's variable map");
        assertEquals(1000, vars.get("amount"),
                "Existing variables must remain intact");

        log.info("SPEL-01 PASSED: #setVar wrote approverRole=manager to live map");
    }

    @Test
    @DisplayName("SPEL-02: #vars['x'] = v indexer mutation propagates")
    void spel02_indexerAssignmentPropagates() {
        String processKey = "proc-spel-indexer-" + System.nanoTime();
        String nodeId = "node-spel-02";
        // SpEL indexer assignment returns the assigned value; boolean-coerced
        // via the truthy-value policy the hook uses (non-zero Number → true,
        // non-null Object → true by the Number/Boolean check; String returns
        // true via the default branch).
        hookService.createHook(buildScriptHook(processKey, nodeId, "pre_check",
                "#vars['slaLevel'] = 'gold'", "block"));

        Map<String, Object> vars = new HashMap<>();
        HookExecutionResult result = hookService.executePreChecks(processKey, nodeId, vars);

        assertTrue(result.passed(), "Script must not fail pre-check");
        assertEquals("gold", vars.get("slaLevel"),
                "#vars['k']=v must mutate the same underlying map");

        log.info("SPEL-02 PASSED: #vars['slaLevel']='gold' propagated");
    }

    @Test
    @DisplayName("SPEL-03: script reads existing variable via #vars['k']")
    void spel03_scriptReadsExistingVariable() {
        String processKey = "proc-spel-read-" + System.nanoTime();
        String nodeId = "node-spel-03";
        // #vars['amount'] > 500 returns Boolean → directly used as result.
        hookService.createHook(buildScriptHook(processKey, nodeId, "pre_check",
                "#vars['amount'] > 500", "block"));

        Map<String, Object> varsPass = new HashMap<>();
        varsPass.put("amount", 1000);
        HookExecutionResult pass = hookService.executePreChecks(processKey, nodeId, varsPass);
        assertTrue(pass.passed(), "amount=1000 > 500 → pre-check passes");

        Map<String, Object> varsFail = new HashMap<>();
        varsFail.put("amount", 100);
        HookExecutionResult fail = hookService.executePreChecks(processKey, nodeId, varsFail);
        assertFalse(fail.passed(), "amount=100 < 500 → pre-check fails (BLOCK)");
        assertNotNull(fail.message(), "BLOCK failure must carry a message");

        log.info("SPEL-03 PASSED: read-and-compare on #vars['amount']");
    }

    @Test
    @DisplayName("SPEL-04: T(Runtime) reference rejected, no side effects")
    void spel04_typeReferenceRejected() {
        String processKey = "proc-spel-rce-type-" + System.nanoTime();
        String nodeId = "node-spel-04";
        // Attempt a classic SpEL RCE vector. The DENY_TYPE_LOCATOR throws
        // SpelEvaluationException, which propagates out of executeScript and
        // is caught by executePreChecks — for BLOCK strategy, result.passed
        // must be false and the variables map must be unchanged.
        hookService.createHook(buildScriptHook(processKey, nodeId, "pre_check",
                "T(java.lang.Runtime).getRuntime().exec('echo pwned')", "block"));

        Map<String, Object> vars = new HashMap<>();
        vars.put("before", "untouched");
        HookExecutionResult result = hookService.executePreChecks(processKey, nodeId, vars);

        assertFalse(result.passed(), "T(Runtime) must not be allowed to pass pre-check");
        assertNotNull(result.message(), "Security rejection must carry a message");
        assertTrue(result.message().contains("Pre-check error"),
                "Message should route through the error branch, got: " + result.message());
        assertEquals("untouched", vars.get("before"),
                "No side effects expected on the variables map");
        assertNull(vars.get("pwned"),
                "Script must not have had the chance to write anything");

        log.info("SPEL-04 PASSED: T(Runtime) rejected without side effects, message={}",
                result.message());
    }

    @Test
    @DisplayName("SPEL-05: constructor call new File(...) rejected")
    void spel05_constructorCallRejected() {
        String processKey = "proc-spel-rce-ctor-" + System.nanoTime();
        String nodeId = "node-spel-05";
        // Constructor resolution without a registered ConstructorResolver
        // fails to resolve, throwing SpelEvaluationException (CONSTRUCTOR_NOT_FOUND).
        hookService.createHook(buildScriptHook(processKey, nodeId, "pre_check",
                "new java.io.File('/tmp/aura-bpm-hook-rce-probe').exists()", "block"));

        Map<String, Object> vars = new HashMap<>();
        HookExecutionResult result = hookService.executePreChecks(processKey, nodeId, vars);

        assertFalse(result.passed(), "Constructor invocation must not be allowed");
        assertNotNull(result.message(), "Security rejection must carry a message");

        log.info("SPEL-05 PASSED: constructor call rejected, message={}", result.message());
    }

    @Test
    @DisplayName("SPEL-06: post-action hook mutation propagates to caller's map")
    void spel06_postActionWritesBack() {
        String processKey = "proc-spel-postwrite-" + System.nanoTime();
        String nodeId = "node-spel-06";
        hookService.createHook(buildScriptHook(processKey, nodeId, "post_action",
                "#setVar(#vars, 'postFlag', true)", "block"));

        Map<String, Object> vars = new HashMap<>();
        hookService.executePostActions(processKey, nodeId, vars);

        assertEquals(Boolean.TRUE, vars.get("postFlag"),
                "post-action hook must be able to push variables for downstream nodes");

        log.info("SPEL-06 PASSED: post-action #setVar wrote postFlag=true");
    }
}
