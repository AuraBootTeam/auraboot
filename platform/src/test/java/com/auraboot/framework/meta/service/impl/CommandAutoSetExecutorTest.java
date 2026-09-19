package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void preserveInputKeepsAnExplicitBusinessTimestamp() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("activityAt", "2026-08-10T14:30:00+08:00");

        executor.executeAutoSetPhase(
                autoSet("activityAt", Map.of(
                        "strategy", "current_datetime",
                        "preserveInput", true)),
                payload,
                1L,
                2L,
                null);

        assertEquals("2026-08-10T14:30:00+08:00", payload.get("activityAt"));
    }

    /**
     * Regression guard (2026-09-18): the seq query used to aggregate
     * {@code MAX(CAST(SUBSTRING(code ...) AS INTEGER))} over every row matching the
     * prefix. Fallback codes written by an earlier failure (e.g. {@code STU-...-endc8})
     * made the CAST throw, aborting the caller's transaction — every subsequent
     * create in the tenant failed with SQLSTATE 25P02. The aggregation must only see
     * pure-numeric suffixes.
     */
    @Test
    void autoCodeSequencingExcludesNonNumericSuffixes() {
        DynamicDataMapper mapper = mock(DynamicDataMapper.class);
        MetaModelService models = mock(MetaModelService.class);
        TenantClock clock = mock(TenantClock.class);
        CommandAutoSetExecutor autoCodeExecutor =
                new CommandAutoSetExecutor(mapper, models, clock, null, new CommandSpelEvaluator());

        CommandDefinition command = new CommandDefinition();
        command.setModelCode("xy_student");
        when(models.getTableName("xy_student")).thenReturn("mt_xy_student");
        when(clock.businessDate(any())).thenReturn(LocalDate.of(2026, 9, 18));
        when(mapper.selectByQuery(anyString(), any())).thenReturn(List.of(Map.of("max_seq", 7)));

        Map<String, Object> payload = new HashMap<>();
        MetaContext.setContext(42L, 7L, "user-pid", "user@example.com");
        try {
            autoCodeExecutor.executeAutoSetPhase(
                    autoSet("xy_stu_code", Map.of(
                            "strategy", "auto_generate",
                            "pattern", "STU-{yyyyMMdd}-{seq}")),
                    payload, 42L, 7L, command);
        } finally {
            MetaContext.clear();
        }

        assertEquals("STU-20260918-008", payload.get("xy_stu_code"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(mapper).selectByQuery(sql.capture(), any());
        assertTrue(sql.getValue().contains("~ '^[0-9]+$'"),
                "seq aggregation must exclude non-numeric suffixes so one dirty row cannot abort the transaction");
    }
}
