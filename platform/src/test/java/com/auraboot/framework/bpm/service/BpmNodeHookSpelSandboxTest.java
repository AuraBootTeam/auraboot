package com.auraboot.framework.bpm.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast, Spring-free unit tests for the SpEL sandbox boundary used by BPM node
 * hook scripts ({@link BpmNodeHookService#buildScriptEvaluationContext(Map)}).
 *
 * <p>Security regression: before this hardening the hook context was a
 * {@code StandardEvaluationContext} guarded only by a type-locator that blocked
 * {@code T(...)}. That left method invocation fully enabled, so the reflective
 * escape {@code ''.getClass().forName('java.lang.Runtime')...} bypassed the
 * guard and reached host RCE. The context is now a {@link org.springframework.expression.spel.support.SimpleEvaluationContext}
 * which structurally registers no method resolver, so <em>all</em> method calls
 * (including that reflection chain) are unresolvable.
 *
 * <p>The tests below also pin the behaviour the sandbox must preserve
 * (GAP-257): {@code #setVar(...)} function calls and {@code #vars['x']=y}
 * indexer mutation still propagate to the caller's map.
 */
@DisplayName("BPM Node Hook SpEL Sandbox (unit)")
class BpmNodeHookSpelSandboxTest {

    private final ExpressionParser parser = new SpelExpressionParser();

    private Object eval(String expr, Map<String, Object> vars) throws Exception {
        EvaluationContext ctx = BpmNodeHookService.buildScriptEvaluationContext(vars);
        return parser.parseExpression(expr).getValue(ctx);
    }

    // ---- behaviour that MUST be preserved (GAP-257) ----

    @Test
    @DisplayName("SANDBOX-01: #setVar writes back into the caller's map")
    void setVarFunctionPropagates() throws Exception {
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 1000);
        eval("#setVar(#vars, 'approverRole', 'manager')", vars);
        assertEquals("manager", vars.get("approverRole"),
                "#setVar must write back to the caller's variable map");
        assertEquals(1000, vars.get("amount"), "existing variables must remain intact");
    }

    @Test
    @DisplayName("SANDBOX-02: #vars['x']=v indexer mutation propagates")
    void indexerAssignmentPropagates() throws Exception {
        Map<String, Object> vars = new HashMap<>();
        eval("#vars['slaLevel'] = 'gold'", vars);
        assertEquals("gold", vars.get("slaLevel"),
                "#vars['k']=v must mutate the same underlying map");
    }

    @Test
    @DisplayName("SANDBOX-03: script reads existing variable via #vars['k']")
    void readsExistingVariable() throws Exception {
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 1000);
        assertEquals(Boolean.TRUE, eval("#vars['amount'] > 500", vars));
        vars.put("amount", 100);
        assertEquals(Boolean.FALSE, eval("#vars['amount'] > 500", vars));
    }

    // ---- RCE vectors that MUST be rejected ----

    @Test
    @DisplayName("SANDBOX-04: reflective getClass().forName() chain is rejected (the real bypass)")
    void reflectionEscapeRejected() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("before", "untouched");
        // The exact vector that defeated the old T()-only guard.
        String payload = "''.getClass().forName('java.lang.Runtime')"
                + ".getMethod('getRuntime').invoke(null)";
        assertThrows(EvaluationException.class, () -> eval(payload, vars),
                "method invocation (getClass/forName/invoke) must be structurally unresolvable");
        assertEquals("untouched", vars.get("before"), "no side effects on the map");
    }

    @Test
    @DisplayName("SANDBOX-05: T(java.lang.Runtime) type reference is rejected")
    void typeReferenceRejected() {
        assertThrows(EvaluationException.class,
                () -> eval("T(java.lang.Runtime).getRuntime().exec('echo pwned')", new HashMap<>()));
    }

    @Test
    @DisplayName("SANDBOX-06: constructor call new File(...) is rejected")
    void constructorRejected() {
        assertThrows(EvaluationException.class,
                () -> eval("new java.io.File('/tmp/aura-bpm-hook-rce-probe').exists()", new HashMap<>()));
    }

    @Test
    @DisplayName("SANDBOX-07: plain method call on an exposed variable is rejected")
    void methodCallOnVariableRejected() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("s", "abc");
        assertThrows(EvaluationException.class, () -> eval("#vars['s'].getClass().getName()", vars));
        assertNull(vars.get("leak"));
    }
}
