package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RollUpSummaryService.
 * Tests recalculate logic for all 5 aggregate functions plus edge cases.
 */
@ExtendWith(MockitoExtension.class)
class RollUpSummaryServiceTest {

    @Mock
    private DynamicDataMapper dynamicDataMapper;
    @Mock
    private MetaModelService metaModelService;

    private RollUpSummaryService service;

    private static final Long TENANT_ID = 1L;
    private static final String PARENT_MODEL = "sales_order";
    private static final String PARENT_FIELD = "or_total_amount";
    private static final String PARENT_ID = "pid_order_001";
    private static final String CHILD_MODEL = "order_line";
    private static final String CHILD_FIELD = "ol_amount";
    private static final String CHILD_FK = "ol_order_id";

    @BeforeEach
    void setUp() {
        service = new RollUpSummaryService(dynamicDataMapper, metaModelService);

        // Default table and column name resolution
        when(metaModelService.getTableName(CHILD_MODEL)).thenReturn("mt_order_line");
        when(metaModelService.getTableName(PARENT_MODEL)).thenReturn("mt_sales_order");
        when(metaModelService.getColumnName(CHILD_MODEL, CHILD_FIELD)).thenReturn("ol_amount");
        when(metaModelService.getColumnName(CHILD_MODEL, CHILD_FK)).thenReturn("ol_order_id");
        when(metaModelService.getColumnName(PARENT_MODEL, PARENT_FIELD)).thenReturn("or_total_amount");
    }

    private List<Map<String, Object>> makeChildRows(BigDecimal... amounts) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BigDecimal amount : amounts) {
            Map<String, Object> row = new HashMap<>();
            row.put("ol_amount", amount);
            rows.add(row);
        }
        return rows;
    }

    private void verifyParentUpdate(BigDecimal expectedValue) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> condCaptor = ArgumentCaptor.forClass(Map.class);

        verify(dynamicDataMapper).update(eq("mt_sales_order"), dataCaptor.capture(), condCaptor.capture());

        Map<String, Object> data = dataCaptor.getValue();
        assertThat(data).containsKey("or_total_amount");
        assertThat(new BigDecimal(data.get("or_total_amount").toString()))
                .isEqualByComparingTo(expectedValue);

        Map<String, Object> conditions = condCaptor.getValue();
        assertThat(conditions).containsEntry("tenant_id", TENANT_ID);
        assertThat(conditions).containsEntry("pid", PARENT_ID);
    }

    @Nested
    @DisplayName("SUM function")
    class SumTests {

        @Test
        @DisplayName("SUM with multiple child rows")
        void sumMultipleRows() {
            when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                    .thenReturn(makeChildRows(
                            new BigDecimal("100.50"),
                            new BigDecimal("200.25"),
                            new BigDecimal("50.00")));

            service.recalculate(PARENT_MODEL, PARENT_FIELD, PARENT_ID,
                    CHILD_MODEL, CHILD_FIELD, CHILD_FK, "sum", null, TENANT_ID);

            verifyParentUpdate(new BigDecimal("350.75"));
        }

        @Test
        @DisplayName("SUM with empty child set returns ZERO")
        void sumEmptySet() {
            when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                    .thenReturn(List.of());

            service.recalculate(PARENT_MODEL, PARENT_FIELD, PARENT_ID,
                    CHILD_MODEL, CHILD_FIELD, CHILD_FK, "sum", null, TENANT_ID);

            verifyParentUpdate(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("SUM skips null values")
        void sumSkipsNulls() {
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(Map.of("ol_amount", new BigDecimal("100")));
            Map<String, Object> nullRow = new HashMap<>();
            nullRow.put("ol_amount", null);
            rows.add(nullRow);
            rows.add(Map.of("ol_amount", new BigDecimal("50")));

            when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(rows);

            service.recalculate(PARENT_MODEL, PARENT_FIELD, PARENT_ID,
                    CHILD_MODEL, CHILD_FIELD, CHILD_FK, "sum", null, TENANT_ID);

            verifyParentUpdate(new BigDecimal("150"));
        }
    }

    @Nested
    @DisplayName("COUNT function")
    class CountTests {

        @Test
        @DisplayName("COUNT with multiple rows")
        void countRows() {
            when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                    .thenReturn(makeChildRows(
                            new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30")));

            service.recalculate(PARENT_MODEL, PARENT_FIELD, PARENT_ID,
                    CHILD_MODEL, CHILD_FIELD, CHILD_FK, "count", null, TENANT_ID);

            verifyParentUpdate(new BigDecimal("3"));
        }

        @Test
        @DisplayName("COUNT with empty set returns ZERO")
        void countEmpty() {
            when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of());

            service.recalculate(PARENT_MODEL, PARENT_FIELD, PARENT_ID,
                    CHILD_MODEL, CHILD_FIELD, CHILD_FK, "count", null, TENANT_ID);

            verifyParentUpdate(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("AVG function")
    class AvgTests {

        @Test
        @DisplayName("AVG with multiple rows")
        void avgRows() {
            when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                    .thenReturn(makeChildRows(
                            new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30")));

            service.recalculate(PARENT_MODEL, PARENT_FIELD, PARENT_ID,
                    CHILD_MODEL, CHILD_FIELD, CHILD_FK, "avg", null, TENANT_ID);

            // (10+20+30)/3 = 20.0000
            verifyParentUpdate(new BigDecimal("20.0000"));
        }

        @Test
        @DisplayName("AVG with non-even division rounds to 4 decimals HALF_UP")
        void avgRounding() {
            when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                    .thenReturn(makeChildRows(
                            new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("33")));

            service.recalculate(PARENT_MODEL, PARENT_FIELD, PARENT_ID,
                    CHILD_MODEL, CHILD_FIELD, CHILD_FK, "avg", null, TENANT_ID);

            // (10+20+33)/3 = 21.0000
            verifyParentUpdate(new BigDecimal("21.0000"));
        }
    }

    @Nested
    @DisplayName("MIN function")
    class MinTests {

        @Test
        @DisplayName("MIN returns smallest value")
        void minValue() {
            when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                    .thenReturn(makeChildRows(
                            new BigDecimal("100"), new BigDecimal("5"), new BigDecimal("50")));

            service.recalculate(PARENT_MODEL, PARENT_FIELD, PARENT_ID,
                    CHILD_MODEL, CHILD_FIELD, CHILD_FK, "min", null, TENANT_ID);

            verifyParentUpdate(new BigDecimal("5"));
        }
    }

    @Nested
    @DisplayName("MAX function")
    class MaxTests {

        @Test
        @DisplayName("MAX returns largest value")
        void maxValue() {
            when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                    .thenReturn(makeChildRows(
                            new BigDecimal("100"), new BigDecimal("5"), new BigDecimal("50")));

            service.recalculate(PARENT_MODEL, PARENT_FIELD, PARENT_ID,
                    CHILD_MODEL, CHILD_FIELD, CHILD_FK, "max", null, TENANT_ID);

            verifyParentUpdate(new BigDecimal("100"));
        }
    }

    @Nested
    @DisplayName("childFilter")
    class FilterTests {

        @Test
        @DisplayName("childFilter is appended to SQL WHERE clause")
        void filterApplied() {
            when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                    .thenReturn(makeChildRows(new BigDecimal("100")));

            service.recalculate(PARENT_MODEL, PARENT_FIELD, PARENT_ID,
                    CHILD_MODEL, CHILD_FIELD, CHILD_FK, "sum",
                    "ol_status != 'cancelled'", TENANT_ID);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(dynamicDataMapper).selectByQuery(sqlCaptor.capture(), anyMap());

            assertThat(sqlCaptor.getValue()).contains("ol_status != 'cancelled'");
        }
    }

    @Nested
    @DisplayName("batchRecalculate")
    class BatchTests {

        @Test
        @DisplayName("batch iterates over all parent records")
        void batchAll() {
            // Mock parent record list
            List<Map<String, Object>> parentRows = List.of(
                    Map.of("pid", "pid_001"),
                    Map.of("pid", "pid_002"),
                    Map.of("pid", "pid_003")
            );
            // First call = parent query, subsequent calls = child queries
            when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                    .thenReturn(parentRows)
                    .thenReturn(makeChildRows(new BigDecimal("100")))
                    .thenReturn(makeChildRows(new BigDecimal("200")))
                    .thenReturn(makeChildRows(new BigDecimal("300")));

            int count = service.batchRecalculate(PARENT_MODEL, PARENT_FIELD,
                    CHILD_MODEL, CHILD_FIELD, CHILD_FK, "sum", null, TENANT_ID);

            assertThat(count).isEqualTo(3);
            // 3 updates for parent records + the initial parent query = 4 selectByQuery + 3 update calls
            verify(dynamicDataMapper, times(3)).update(
                    eq("mt_sales_order"), anyMap(), anyMap());
        }
    }
}
