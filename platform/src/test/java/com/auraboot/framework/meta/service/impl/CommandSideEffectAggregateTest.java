package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DocumentFlowService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for the AGGREGATE sideEffect in CommandSideEffectExecutor.
 * Tests all 5 aggregate functions: SUM, COUNT, AVG, MAX, MIN.
 */
@ExtendWith(MockitoExtension.class)
class CommandSideEffectAggregateTest {

    @Mock
    private DynamicDataMapper dynamicDataMapper;
    @Mock
    private DynamicDataService dynamicDataService;
    @Mock
    private MetaModelService metaModelService;
    @Mock
    private CommandSpelEvaluator spelEvaluator;
    @Mock
    private DocumentFlowService documentFlowService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CommandSideEffectExecutor executor;

    private static final Long TENANT_ID = 1L;
    private static final Long USER_ID = 100L;

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    @BeforeEach
    void setUp() {
        executor = new CommandSideEffectExecutor(dynamicDataMapper, dynamicDataService, metaModelService, spelEvaluator, documentFlowService, objectMapper);
    }

    // ── Helper to run AGGREGATE through the full sideEffect pipeline ──────────

    /**
     * Build and execute an AGGREGATE side effect via executeSideEffectPhase.
     * Returns the captured update data map for assertions.
     */
    private Map<String, Object> executeAggregateAndCapture(String function, List<Map<String, Object>> childRows) {
        Map<String, Object> currentRecord = new HashMap<>();
        currentRecord.put("order_id", "order-001");

        Map<String, Object> effect = new HashMap<>();
        effect.put("action", "aggregate");
        effect.put("targetModel", "pm_order");
        effect.put("childModel", "pm_order_line");
        effect.put("childField", "amount");
        effect.put("parentField", "total_amount");
        effect.put("parentFk", "order_id");
        if (function != null) {
            effect.put("function", function);
        }

        when(metaModelService.getTableName("pm_order_line")).thenReturn("mt_pm_order_line");
        when(metaModelService.getTableName("pm_order")).thenReturn("mt_pm_order");
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(childRows);
        when(dynamicDataMapper.update(anyString(), anyMap(), anyMap())).thenReturn(1);

        Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
        executor.executeSideEffectPhase(execConfig, currentRecord, TENANT_ID, USER_ID, null, null, null);

        ArgumentCaptor<Map<String, Object>> dataCaptor = mapCaptor();
        verify(dynamicDataMapper).update(eq("mt_pm_order"), dataCaptor.capture(), anyMap());
        return dataCaptor.getValue();
    }

    // ── computeAggregate pure unit tests ──────────────────────────────────────

    @Nested
    @DisplayName("computeAggregate — pure function tests")
    class ComputeAggregateTests {

        @Test
        @DisplayName("SUM: 10 + 20 + 30 = 60")
        void testSumFunction() {
            List<BigDecimal> values = List.of(
                    new BigDecimal("10"),
                    new BigDecimal("20"),
                    new BigDecimal("30")
            );
            BigDecimal result = CommandSideEffectExecutor.computeAggregate("sum", values);
            assertEquals(new BigDecimal("60"), result);
        }

        @Test
        @DisplayName("COUNT: 3 values → 3")
        void testCountFunction() {
            List<BigDecimal> values = List.of(
                    new BigDecimal("10"),
                    new BigDecimal("20"),
                    new BigDecimal("30")
            );
            BigDecimal result = CommandSideEffectExecutor.computeAggregate("count", values);
            assertEquals(new BigDecimal("3"), result);
        }

        @Test
        @DisplayName("AVG: (10 + 20 + 30) / 3 = 20.0000")
        void testAvgFunction() {
            List<BigDecimal> values = List.of(
                    new BigDecimal("10"),
                    new BigDecimal("20"),
                    new BigDecimal("30")
            );
            BigDecimal result = CommandSideEffectExecutor.computeAggregate("avg", values);
            assertEquals(new BigDecimal("20.0000"), result);
        }

        @Test
        @DisplayName("AVG: non-even division → 4 decimal places HALF_UP")
        void testAvgFunctionRounding() {
            List<BigDecimal> values = List.of(
                    new BigDecimal("10"),
                    new BigDecimal("20"),
                    new BigDecimal("33")
            );
            // (10 + 20 + 33) / 3 = 63 / 3 = 21.0000
            BigDecimal result = CommandSideEffectExecutor.computeAggregate("avg", values);
            assertEquals(new BigDecimal("21.0000"), result);
        }

        @Test
        @DisplayName("AVG: repeating decimal → 4 decimal places HALF_UP")
        void testAvgFunctionRepeatingDecimal() {
            List<BigDecimal> values = List.of(
                    new BigDecimal("1"),
                    new BigDecimal("2")
            );
            // (1 + 2) / 2 = 1.5000
            BigDecimal result = CommandSideEffectExecutor.computeAggregate("avg", values);
            assertEquals(new BigDecimal("1.5000"), result);
        }

        @Test
        @DisplayName("MAX: 10, 50, 30 → 50")
        void testMaxFunction() {
            List<BigDecimal> values = List.of(
                    new BigDecimal("10"),
                    new BigDecimal("50"),
                    new BigDecimal("30")
            );
            BigDecimal result = CommandSideEffectExecutor.computeAggregate("max", values);
            assertEquals(new BigDecimal("50"), result);
        }

        @Test
        @DisplayName("MIN: 10, 50, 5 → 5")
        void testMinFunction() {
            List<BigDecimal> values = List.of(
                    new BigDecimal("10"),
                    new BigDecimal("50"),
                    new BigDecimal("5")
            );
            BigDecimal result = CommandSideEffectExecutor.computeAggregate("min", values);
            assertEquals(new BigDecimal("5"), result);
        }

        @Test
        @DisplayName("All functions return ZERO for empty list")
        void testEmptyValues() {
            List<BigDecimal> empty = List.of();
            assertEquals(BigDecimal.ZERO, CommandSideEffectExecutor.computeAggregate("sum", empty));
            assertEquals(BigDecimal.ZERO, CommandSideEffectExecutor.computeAggregate("count", empty));
            assertEquals(BigDecimal.ZERO, CommandSideEffectExecutor.computeAggregate("avg", empty));
            assertEquals(BigDecimal.ZERO, CommandSideEffectExecutor.computeAggregate("max", empty));
            assertEquals(BigDecimal.ZERO, CommandSideEffectExecutor.computeAggregate("min", empty));
        }

        @Test
        @DisplayName("Single value: all functions work correctly")
        void testSingleValue() {
            List<BigDecimal> single = List.of(new BigDecimal("42"));
            assertEquals(new BigDecimal("42"), CommandSideEffectExecutor.computeAggregate("sum", single));
            assertEquals(new BigDecimal("1"), CommandSideEffectExecutor.computeAggregate("count", single));
            assertEquals(new BigDecimal("42.0000"), CommandSideEffectExecutor.computeAggregate("avg", single));
            assertEquals(new BigDecimal("42"), CommandSideEffectExecutor.computeAggregate("max", single));
            assertEquals(new BigDecimal("42"), CommandSideEffectExecutor.computeAggregate("min", single));
        }

        @Test
        @DisplayName("MAX/MIN with negative values")
        void testNegativeValues() {
            List<BigDecimal> values = List.of(
                    new BigDecimal("-5"),
                    new BigDecimal("10"),
                    new BigDecimal("-20")
            );
            assertEquals(new BigDecimal("10"), CommandSideEffectExecutor.computeAggregate("max", values));
            assertEquals(new BigDecimal("-20"), CommandSideEffectExecutor.computeAggregate("min", values));
        }
    }

    // ── Integration-style tests through executeSideEffectAggregate ────────────

    @Nested
    @DisplayName("executeSideEffectAggregate — end-to-end through pipeline")
    class PipelineTests {

        @Test
        @DisplayName("SUM via pipeline: 10 + 20 + 30 = 60")
        void testSumFunction() {
            List<Map<String, Object>> children = List.of(
                    Map.of("amount", new BigDecimal("10")),
                    Map.of("amount", new BigDecimal("20")),
                    Map.of("amount", new BigDecimal("30"))
            );

            Map<String, Object> data = executeAggregateAndCapture("sum", children);
            assertEquals(new BigDecimal("60"), data.get("total_amount"));
        }

        @Test
        @DisplayName("COUNT via pipeline: 3 child rows → 3")
        void testCountFunction() {
            List<Map<String, Object>> children = List.of(
                    Map.of("amount", new BigDecimal("10")),
                    Map.of("amount", new BigDecimal("20")),
                    Map.of("amount", new BigDecimal("30"))
            );

            Map<String, Object> data = executeAggregateAndCapture("count", children);
            assertEquals(new BigDecimal("3"), data.get("total_amount"));
        }

        @Test
        @DisplayName("AVG via pipeline: (10 + 20 + 30) / 3 = 20.0000")
        void testAvgFunction() {
            List<Map<String, Object>> children = List.of(
                    Map.of("amount", new BigDecimal("10")),
                    Map.of("amount", new BigDecimal("20")),
                    Map.of("amount", new BigDecimal("30"))
            );

            Map<String, Object> data = executeAggregateAndCapture("avg", children);
            assertEquals(new BigDecimal("20.0000"), data.get("total_amount"));
        }

        @Test
        @DisplayName("MAX via pipeline: 10, 50, 30 → 50")
        void testMaxFunction() {
            List<Map<String, Object>> children = List.of(
                    Map.of("amount", new BigDecimal("10")),
                    Map.of("amount", new BigDecimal("50")),
                    Map.of("amount", new BigDecimal("30"))
            );

            Map<String, Object> data = executeAggregateAndCapture("max", children);
            assertEquals(new BigDecimal("50"), data.get("total_amount"));
        }

        @Test
        @DisplayName("MIN via pipeline: 10, 50, 5 → 5")
        void testMinFunction() {
            List<Map<String, Object>> children = List.of(
                    Map.of("amount", new BigDecimal("10")),
                    Map.of("amount", new BigDecimal("50")),
                    Map.of("amount", new BigDecimal("5"))
            );

            Map<String, Object> data = executeAggregateAndCapture("min", children);
            assertEquals(new BigDecimal("5"), data.get("total_amount"));
        }

        @Test
        @DisplayName("Empty children: SUM → 0")
        void testEmptyChildrenSum() {
            Map<String, Object> data = executeAggregateAndCapture("sum", List.of());
            assertEquals(BigDecimal.ZERO, data.get("total_amount"));
        }

        @Test
        @DisplayName("Empty children: COUNT → 0")
        void testEmptyChildrenCount() {
            Map<String, Object> data = executeAggregateAndCapture("count", List.of());
            assertEquals(BigDecimal.ZERO, data.get("total_amount"));
        }

        @Test
        @DisplayName("Empty children: AVG → 0")
        void testEmptyChildrenAvg() {
            Map<String, Object> data = executeAggregateAndCapture("avg", List.of());
            assertEquals(BigDecimal.ZERO, data.get("total_amount"));
        }

        @Test
        @DisplayName("Null values are skipped")
        void testNullValuesSkipped() {
            Map<String, Object> row1 = new HashMap<>();
            row1.put("amount", new BigDecimal("10"));
            Map<String, Object> row2 = new HashMap<>();
            row2.put("amount", null);
            Map<String, Object> row3 = new HashMap<>();
            row3.put("amount", new BigDecimal("30"));

            List<Map<String, Object>> children = List.of(row1, row2, row3);

            Map<String, Object> data = executeAggregateAndCapture("sum", children);
            // 10 + 30 = 40 (null skipped)
            assertEquals(new BigDecimal("40"), data.get("total_amount"));
        }

        @Test
        @DisplayName("Null values: COUNT only counts non-null")
        void testNullValuesCountSkipped() {
            Map<String, Object> row1 = new HashMap<>();
            row1.put("amount", new BigDecimal("10"));
            Map<String, Object> row2 = new HashMap<>();
            row2.put("amount", null);
            Map<String, Object> row3 = new HashMap<>();
            row3.put("amount", new BigDecimal("30"));

            List<Map<String, Object>> children = List.of(row1, row2, row3);

            Map<String, Object> data = executeAggregateAndCapture("count", children);
            // Only 2 non-null values
            assertEquals(new BigDecimal("2"), data.get("total_amount"));
        }

        @Test
        @DisplayName("Default function is SUM when 'function' key is absent")
        void testDefaultFunctionIsSUM() {
            List<Map<String, Object>> children = List.of(
                    Map.of("amount", new BigDecimal("10")),
                    Map.of("amount", new BigDecimal("20")),
                    Map.of("amount", new BigDecimal("30"))
            );

            // Pass null function so the key is not set → defaults to SUM
            Map<String, Object> data = executeAggregateAndCapture(null, children);
            assertEquals(new BigDecimal("60"), data.get("total_amount"));
        }

        @Test
        @DisplayName("Mixed number types: Integer, Long, BigDecimal")
        void testMixedNumberTypes() {
            List<Map<String, Object>> children = List.of(
                    Map.of("amount", 10),                        // Integer
                    Map.of("amount", 20L),                       // Long
                    Map.of("amount", new BigDecimal("30.50"))    // BigDecimal
            );

            Map<String, Object> data = executeAggregateAndCapture("sum", children);
            // 10.0 + 20.0 + 30.50 = 60.50 (use compareTo to ignore scale differences)
            BigDecimal actual = (BigDecimal) data.get("total_amount");
            assertEquals(0, new BigDecimal("60.50").compareTo(actual),
                    "Expected 60.50 but got " + actual);
        }

        @Test
        @DisplayName("String numeric values are parsed correctly")
        void testStringNumericValues() {
            List<Map<String, Object>> children = List.of(
                    Map.of("amount", "15.5"),
                    Map.of("amount", "24.5")
            );

            Map<String, Object> data = executeAggregateAndCapture("sum", children);
            assertEquals(new BigDecimal("40.0"), data.get("total_amount"));
        }

        @Test
        @DisplayName("Unsupported function skips silently (no update)")
        void testUnsupportedFunctionSkips() {
            Map<String, Object> currentRecord = new HashMap<>();
            currentRecord.put("order_id", "order-001");

            Map<String, Object> effect = new HashMap<>();
            effect.put("action", "aggregate");
            effect.put("targetModel", "pm_order");
            effect.put("childModel", "pm_order_line");
            effect.put("childField", "amount");
            effect.put("parentField", "total_amount");
            effect.put("parentFk", "order_id");
            effect.put("function", "median"); // unsupported

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
            executor.executeSideEffectPhase(execConfig, currentRecord, TENANT_ID, USER_ID, null, null, null);

            // Should not call update at all
            verify(dynamicDataMapper, never()).update(anyString(), anyMap(), anyMap());
        }
    }
}
