package com.auraboot.framework.meta.service.impl;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * DR-20260715-A-002 regression guard. {@code current_tenant}, {@code uuid} and {@code expression}
 * were declared in {@link com.auraboot.framework.meta.constant.DslRegistry.AutoSetStrategy} but had
 * no runtime branch in {@link CommandAutoSetExecutor} — a field configured with one imported with
 * only a warning and then silently stayed empty (default -&gt; warn + null). These assert the three
 * newly-implemented strategies now produce values. Only the deps those strategies use are supplied
 * (uuid/current_tenant use none; expression uses a real CommandSpelEvaluator).
 */
class CommandAutoSetExecutorTest {

    private final CommandAutoSetExecutor executor =
            new CommandAutoSetExecutor(null, null, null, null, new CommandSpelEvaluator());

    private Map<String, Object> autoSet(String field, Map<String, Object> config) {
        return Map.of("autoSetFields", Map.of(field, config));
    }

    @Test
    void uuidStrategyFillsAGeneratedId() {
        Map<String, Object> payload = new HashMap<>();
        executor.executeAutoSetPhase(autoSet("id", Map.of("strategy", "uuid")), payload, 1L, 2L, null);
        assertInstanceOf(String.class, payload.get("id"));
        assertEquals(36, ((String) payload.get("id")).length()); // canonical UUID string
    }

    @Test
    void currentTenantStrategyFillsTheTenantId() {
        Map<String, Object> payload = new HashMap<>();
        executor.executeAutoSetPhase(
                autoSet("tenant", Map.of("strategy", "current_tenant")), payload, 99L, 2L, null);
        assertEquals("99", payload.get("tenant"));
    }

    @Test
    void currentTenantStrategyLeavesFieldEmptyWhenNoTenant() {
        Map<String, Object> payload = new HashMap<>();
        executor.executeAutoSetPhase(
                autoSet("tenant", Map.of("strategy", "current_tenant")), payload, null, 2L, null);
        assertNull(payload.get("tenant"));
    }

    @Test
    void expressionStrategyEvaluatesSpelAgainstPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("a", 2);
        payload.put("b", 3);
        executor.executeAutoSetPhase(
                autoSet("sum", Map.of("strategy", "expression", "expression", "#a + #b")), payload, 1L, 2L, null);
        assertEquals(5, payload.get("sum"));
    }
}
