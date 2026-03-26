package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DocumentFlowService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * Unit tests for the AGGREGATE sideEffect childFilter feature.
 * Validates that an optional childFilter condition is appended to the SQL query.
 */
@ExtendWith(MockitoExtension.class)
class CommandSideEffectChildFilterTest {

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
     * Helper: build and execute an AGGREGATE side effect, capturing the SQL passed to selectByQuery.
     */
    private String executeAggregateAndCaptureSql(String childFilter) {
        Map<String, Object> currentRecord = new HashMap<>();
        currentRecord.put("cc_pr_contract_id", "contract-001");

        Map<String, Object> effect = new HashMap<>();
        effect.put("action", "aggregate");
        effect.put("targetModel", "cc_contract");
        effect.put("childModel", "cc_payment_receipt");
        effect.put("childField", "cc_pr_amount");
        effect.put("parentField", "cc_paid_amount");
        effect.put("parentFk", "cc_pr_contract_id");
        effect.put("function", "sum");
        if (childFilter != null) {
            effect.put("childFilter", childFilter);
        }

        when(metaModelService.getTableName("cc_payment_receipt")).thenReturn("mt_cc_payment_receipt");
        when(metaModelService.getTableName("cc_contract")).thenReturn("mt_cc_contract");
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(
                List.of(Map.of("cc_pr_amount", new BigDecimal("100"))));
        when(dynamicDataMapper.update(anyString(), anyMap(), anyMap())).thenReturn(1);

        Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
        executor.executeSideEffectPhase(execConfig, currentRecord, TENANT_ID, USER_ID, null, null, null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(dynamicDataMapper).selectByQuery(sqlCaptor.capture(), anyMap());
        return sqlCaptor.getValue();
    }

    @Test
    @DisplayName("AGGREGATE with childFilter appends the condition to SQL")
    void testChildFilterAppendsCondition() {
        String sql = executeAggregateAndCaptureSql("cc_pr_type = 'payment'");

        assertTrue(sql.contains("AND cc_pr_type = 'payment'"),
                "SQL should contain the childFilter condition. Actual SQL: " + sql);
        // Verify the base WHERE clause is intact
        assertTrue(sql.contains("WHERE cc_pr_contract_id = #{params.parentId}"),
                "SQL should contain the base FK condition. Actual SQL: " + sql);
        assertTrue(sql.contains("tenant_id = #{params.tenantId}"),
                "SQL should contain the tenant condition. Actual SQL: " + sql);
    }

    @Test
    @DisplayName("AGGREGATE without childFilter (null) does not change SQL")
    void testNullChildFilterDoesNotChangeSQL() {
        String sql = executeAggregateAndCaptureSql(null);

        assertFalse(sql.contains("cc_pr_type"),
                "SQL should NOT contain any childFilter condition when null. Actual SQL: " + sql);
        // Verify the base SQL ends after the tenant_id condition
        assertTrue(sql.endsWith("tenant_id = #{params.tenantId}"),
                "SQL should end with the tenant condition when no childFilter. Actual SQL: " + sql);
    }

    @Test
    @DisplayName("AGGREGATE with blank childFilter does not change SQL")
    void testBlankChildFilterDoesNotChangeSQL() {
        String sql = executeAggregateAndCaptureSql("   ");

        assertFalse(sql.contains("cc_pr_type"),
                "SQL should NOT contain any childFilter condition when blank. Actual SQL: " + sql);
        assertTrue(sql.endsWith("tenant_id = #{params.tenantId}"),
                "SQL should end with the tenant condition when childFilter is blank. Actual SQL: " + sql);
    }
}
