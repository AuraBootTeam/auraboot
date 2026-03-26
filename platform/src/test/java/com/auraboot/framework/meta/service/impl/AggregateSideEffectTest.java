package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.DocumentFlowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the AGGREGATE sideEffect type in CommandSideEffectExecutor.
 */
@ExtendWith(MockitoExtension.class)
class AggregateSideEffectTest {

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

    @BeforeEach
    void setUp() {
        executor = new CommandSideEffectExecutor(dynamicDataMapper, dynamicDataService, metaModelService, spelEvaluator, documentFlowService, objectMapper);
    }

    /**
     * Helper to build an AGGREGATE side effect config and execute it.
     */
    private void executeAggregate(Map<String, Object> currentRecord, Map<String, Object> effectOverrides,
                                   List<Map<String, Object>> childRows) {
        Map<String, Object> effect = new HashMap<>();
        effect.put("action", "aggregate");
        effect.put("targetModel", "pm_order");
        effect.put("childModel", "pm_order_line");
        effect.put("childField", "amount");
        effect.put("parentField", "total_amount");
        effect.put("parentFk", "order_id");
        effect.put("function", "sum");
        effect.putAll(effectOverrides);

        String targetModel = (String) effect.get("targetModel");
        String childModel = (String) effect.get("childModel");

        when(metaModelService.getTableName(childModel)).thenReturn("mt_pm_order_line");
        when(metaModelService.getTableName(targetModel)).thenReturn("mt_pm_order");
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(childRows);
        when(dynamicDataMapper.update(anyString(), anyMap(), anyMap())).thenReturn(1);

        Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
        executor.executeSideEffectPhase(execConfig, currentRecord, TENANT_ID, USER_ID, null, null, null);
    }

    @Test
    void testSumAggregation() {
        // 3 child rows with amounts 100, 200, 300 -> sum = 600
        List<Map<String, Object>> children = List.of(
                Map.of("amount", new BigDecimal("100")),
                Map.of("amount", new BigDecimal("200")),
                Map.of("amount", new BigDecimal("300"))
        );

        Map<String, Object> currentRecord = new HashMap<>();
        currentRecord.put("order_id", "order-001");

        executeAggregate(currentRecord, Map.of(), children);

        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).update(eq("mt_pm_order"), dataCaptor.capture(), anyMap());

        Map<String, Object> updateData = dataCaptor.getValue();
        assertEquals(new BigDecimal("600"), updateData.get("total_amount"));
    }

    @Test
    void testEmptyChildren() {
        // No children -> sum = 0
        Map<String, Object> currentRecord = new HashMap<>();
        currentRecord.put("order_id", "order-002");

        executeAggregate(currentRecord, Map.of(), List.of());

        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).update(eq("mt_pm_order"), dataCaptor.capture(), anyMap());

        assertEquals(BigDecimal.ZERO, dataCaptor.getValue().get("total_amount"));
    }

    @Test
    void testNullFieldValues() {
        // Some children have null amounts -> treated as 0
        Map<String, Object> row1 = new HashMap<>();
        row1.put("amount", new BigDecimal("150"));
        Map<String, Object> row2 = new HashMap<>();
        row2.put("amount", null);
        Map<String, Object> row3 = new HashMap<>();
        row3.put("amount", new BigDecimal("50"));

        List<Map<String, Object>> children = List.of(row1, row2, row3);

        Map<String, Object> currentRecord = new HashMap<>();
        currentRecord.put("order_id", "order-003");

        executeAggregate(currentRecord, Map.of(), children);

        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).update(eq("mt_pm_order"), dataCaptor.capture(), anyMap());

        assertEquals(new BigDecimal("200"), dataCaptor.getValue().get("total_amount"));
    }

    @Test
    void testUpdateTriggerWithDifferentParentFk() {
        // Use a different parentFk field name
        List<Map<String, Object>> children = List.of(
                Map.of("line_total", 500)
        );

        Map<String, Object> currentRecord = new HashMap<>();
        currentRecord.put("po_id", "po-999");

        Map<String, Object> overrides = Map.of(
                "targetModel", "pm_purchase_order",
                "childModel", "pm_po_line",
                "childField", "line_total",
                "parentField", "grand_total",
                "parentFk", "po_id"
        );

        when(metaModelService.getTableName("pm_po_line")).thenReturn("mt_pm_po_line");
        when(metaModelService.getTableName("pm_purchase_order")).thenReturn("mt_pm_purchase_order");
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(children);
        when(dynamicDataMapper.update(anyString(), anyMap(), anyMap())).thenReturn(1);

        Map<String, Object> effect = new HashMap<>(overrides);
        effect.put("action", "aggregate");
        effect.put("function", "sum");

        Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
        executor.executeSideEffectPhase(execConfig, currentRecord, TENANT_ID, USER_ID, null, null, null);

        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).update(eq("mt_pm_purchase_order"), dataCaptor.capture(), anyMap());

        // 500 as integer -> BigDecimal.valueOf(500.0)
        BigDecimal expected = BigDecimal.valueOf(500.0);
        assertEquals(expected, dataCaptor.getValue().get("grand_total"));
    }

    @Test
    void testMissingConfigFieldsSkipsGracefully() {
        // Missing childModel -> should warn and skip, no update call
        Map<String, Object> effect = new HashMap<>();
        effect.put("action", "aggregate");
        effect.put("targetModel", "pm_order");
        // missing childModel, childField, parentField, parentFk

        Map<String, Object> currentRecord = Map.of("id", "rec-1");

        Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
        executor.executeSideEffectPhase(execConfig, currentRecord, TENANT_ID, USER_ID, null, null, null);

        verify(dynamicDataMapper, never()).update(anyString(), anyMap(), anyMap());
    }
}
