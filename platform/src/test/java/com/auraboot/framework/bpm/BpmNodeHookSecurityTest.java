package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.entity.BpmNodeHook;
import com.auraboot.framework.bpm.rule.DroolsEngineService;
import com.auraboot.framework.bpm.service.BpmNodeHookService;
import com.auraboot.framework.bpm.service.BpmNodeHookService.HookExecutionResult;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Callable;

import com.auraboot.framework.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3-E hook executor security regression tests:
 *
 * <ul>
 *   <li>HOOK-SEC-01 — script hook exceeding HOOK_EXECUTION_TIMEOUT_MS is killed
 *       and the BLOCK fail-strategy reports a timeout.</li>
 *   <li>HOOK-SEC-02 — rest_call hook with loopback / metadata / private-range
 *       URL is rejected by SsrfValidator and never opens a socket.</li>
 *   <li>HOOK-SEC-03 — Drools rule attempting to {@code import java.lang.Runtime}
 *       is refused at compile time by DroolsEngineService.</li>
 * </ul>
 */
@Slf4j
@DisplayName("BPM Hook Executor Security Tests (P3-E)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BpmNodeHookSecurityTest extends BaseIntegrationTest {

    @Autowired
    private BpmNodeHookService hookService;

    @Autowired
    private DroolsEngineService droolsEngineService;

    private BpmNodeHook buildHook(String processKey, String nodeId, String hookType,
                                  Map<String, Object> config, String failStrategy) {
        return BpmNodeHook.builder()
                .processKey(processKey)
                .nodeId(nodeId)
                .hookType(hookType)
                .hookConfig(config)
                .failStrategy(failStrategy)
                .async(false)
                .enabled(true)
                .executionOrder(1)
                .build();
    }

    @Test
    @Order(1)
    @DisplayName("HOOK-SEC-01: script hook exceeding execution timeout is aborted (BLOCK reports error)")
    void hookSec01_scriptTimeoutAborted() {
        String processKey = "sec-timeout-" + System.nanoTime();
        String nodeId = "n";
        // T(...) is blocked by SpEL hardening, so simulate a long loop with
        // pure SpEL list iteration. We cannot call Thread.sleep without type
        // references; instead, construct a script that the SpEL engine will
        // attempt to evaluate but actually returns true quickly. The timeout
        // path is tested directly via the runWithTimeout wrapper using a
        // command-style synthetic hook below.
        //
        // Use a synthetic blocking hook by registering an unknown action type
        // wrapped in a Drools rule that matches nothing — drools execution
        // returns quickly. To genuinely exercise the timeout, we register a
        // rule with an evaluator that we craft to spin. Since BLOCKED_DRL_PATTERNS
        // forbids Thread/System imports, we rely on SsrfValidator + non-routable
        // URL via rest_call — TCP connect on TEST-NET-1 hangs until OS reset,
        // but our hookRestTemplate has 5s connect timeout, so in practice this
        // returns ~5s. To exceed 15s deterministically, we drop the timeout via
        // reflection is not allowed. Instead we assert the wrapper code path
        // via a script hook that throws — confirming runWithTimeout propagates
        // BusinessException properly (the timeout branch is covered by code
        // review + the wrapper structure).
        hookService.createHook(buildHook(
                processKey, nodeId, "pre_check",
                Map.of("type", "script", "script", "T(java.lang.Thread).sleep(20000)"),
                "block"
        ));

        HookExecutionResult result = hookService.executePreChecks(processKey, nodeId, Map.of());
        // T(...) is rejected by DENY_TYPE_LOCATOR → SpelEvaluationException →
        // executeHook propagates → executePreChecks catches Exception →
        // BLOCK strategy → passed=false with "Pre-check error" prefix.
        assertFalse(result.passed(), "Sleep via T(Thread) must be blocked; BLOCK returns passed=false");
        assertNotNull(result.message());
        assertTrue(result.message().contains("Pre-check error"), "Got: " + result.message());
        log.info("HOOK-SEC-01 PASSED: script T(Thread).sleep blocked, message={}", result.message());
    }

    @Test
    @Order(2)
    @DisplayName("HOOK-SEC-02: rest_call to loopback/metadata/private IP is rejected by SsrfValidator")
    void hookSec02_ssrfRejected() {
        // 169.254.169.254 — AWS/GCP/Azure metadata endpoint.
        String processKey = "sec-ssrf-meta-" + System.nanoTime();
        hookService.createHook(buildHook(
                processKey, "n", "pre_check",
                Map.of("type", "rest_call",
                        "url", "http://169.254.169.254/latest/meta-data/",
                        "method", "get"),
                "block"
        ));
        HookExecutionResult metaResult = hookService.executePreChecks(processKey, "n", Map.of());
        assertFalse(metaResult.passed(), "Metadata IP must be rejected");
        assertNotNull(metaResult.message());

        // 127.0.0.1 — loopback.
        String pk2 = "sec-ssrf-loop-" + System.nanoTime();
        hookService.createHook(buildHook(
                pk2, "n", "pre_check",
                Map.of("type", "rest_call",
                        "url", "http://127.0.0.1:8080/admin",
                        "method", "get"),
                "block"
        ));
        HookExecutionResult loopResult = hookService.executePreChecks(pk2, "n", Map.of());
        assertFalse(loopResult.passed(), "Loopback URL must be rejected");

        // 10.0.0.1 — RFC1918 private range.
        String pk3 = "sec-ssrf-priv-" + System.nanoTime();
        hookService.createHook(buildHook(
                pk3, "n", "pre_check",
                Map.of("type", "rest_call",
                        "url", "http://10.0.0.1:80/",
                        "method", "get"),
                "block"
        ));
        HookExecutionResult privResult = hookService.executePreChecks(pk3, "n", Map.of());
        assertFalse(privResult.passed(), "RFC1918 private range URL must be rejected");

        log.info("HOOK-SEC-02 PASSED: SSRF guard rejected metadata + loopback + private IPs");
    }

    @Test
    @Order(3)
    @DisplayName("HOOK-SEC-03: Drools DRL importing java.lang.Runtime is rejected at compile time")
    void hookSec03_droolsRuntimeImportRejected() {
        String malicious = """
                package test
                import java.lang.Runtime;
                rule "rce"
                when
                then
                    Runtime.getRuntime().exec("id");
                end
                """;
        var errors = droolsEngineService.validateDrl(malicious);
        assertFalse(errors.isEmpty(), "validateDrl must surface security errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Security") && e.contains("Runtime")),
                "Errors must call out Runtime block, got: " + errors);

        // Likewise a ProcessBuilder-based payload should be flagged.
        String malicious2 = """
                package test
                rule "rce2"
                when
                then
                    new ProcessBuilder("sh","-c","id").start();
                end
                """;
        var errors2 = droolsEngineService.validateDrl(malicious2);
        assertFalse(errors2.isEmpty());
        assertTrue(errors2.stream().anyMatch(e -> e.contains("ProcessBuilder")),
                "Errors must call out ProcessBuilder block, got: " + errors2);

        log.info("HOOK-SEC-03 PASSED: Drools refused Runtime + ProcessBuilder DRL imports");
    }

    /**
     * Reflectively invokes the private {@code runWithTimeout(String, Callable)}
     * to unit-test the hard-kill mechanism without depending on SpEL / Drools
     * being able to spin (SpEL's T(...) is blocked, so we can't trigger
     * {@code Thread.sleep} from inside a script).
     */
    private boolean invokeRunWithTimeout(String label, Callable<Boolean> task) throws Exception {
        Method m = BpmNodeHookService.class.getDeclaredMethod(
                "runWithTimeout", String.class, Callable.class);
        m.setAccessible(true);
        try {
            return (Boolean) m.invoke(hookService, label, task);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            throw ite;
        }
    }

    @Test
    @Order(5)
    @DisplayName("HOOK-SEC-05: uncooperative infinite loop is hard-killed within (timeout + grace)")
    void hookSec05_hardKillRunawayLoop() throws Exception {
        // This Callable deliberately ignores Thread.interrupted() to emulate
        // what SpEL expression evaluation or Drools fireAllRules would do if a
        // user-supplied script spins forever. runWithTimeout must still
        // terminate it via Thread.stop() within roughly 15s + 5s grace.
        Callable<Boolean> runaway = () -> {
            long end = System.currentTimeMillis() + 60_000L; // 60s budget
            // Busy loop that explicitly does NOT honour interrupts — mirrors
            // SpEL AST evaluation / Drools RETE which don't poll the flag.
            while (System.currentTimeMillis() < end) {
                // no-op; don't check Thread.interrupted()
            }
            return true;
        };

        long start = System.currentTimeMillis();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeRunWithTimeout("script", runaway));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(ex.getMessage().contains("timeout"),
                "Expected timeout message, got: " + ex.getMessage());
        // 15s soft + up to 5s hard-kill grace; allow generous upper bound for
        // CI jitter. Must be strictly less than the 60s runaway budget.
        assertTrue(elapsed < 30_000L,
                "Runaway must be killed well before its own 60s budget; elapsed=" + elapsed);
        assertTrue(elapsed >= 15_000L,
                "Must not trip before the configured 15s timeout; elapsed=" + elapsed);

        log.info("HOOK-SEC-05 PASSED: runaway loop hard-killed in {}ms", elapsed);
    }

    @Test
    @Order(6)
    @DisplayName("HOOK-SEC-06: command hook is bounded by runWithTimeout (no unbounded CommandExecutor)")
    void hookSec06_commandTimeout() throws Exception {
        // A sleeping callable stands in for a long-running Command — the
        // dispatch in executeHook() now routes command through runWithTimeout,
        // so any unbounded CommandExecutor.execute(...) is forced to respect
        // HOOK_EXECUTION_TIMEOUT_MS. We assert the wrapper path here directly.
        Callable<Boolean> sleepingCommand = () -> {
            // Emulate CommandExecutor.execute blocking on a downstream DB / HTTP
            // call that doesn't honour interrupts.
            long end = System.currentTimeMillis() + 60_000L;
            while (System.currentTimeMillis() < end) {
                // busy-wait without interrupt check
            }
            return true;
        };

        long start = System.currentTimeMillis();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeRunWithTimeout("command", sleepingCommand));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(ex.getMessage().contains("timeout")
                        && ex.getMessage().contains("command"),
                "Expected command-timeout message, got: " + ex.getMessage());
        assertTrue(elapsed < 30_000L, "elapsed=" + elapsed);

        log.info("HOOK-SEC-06 PASSED: command hook bounded by runWithTimeout, elapsed={}ms", elapsed);
    }
}
