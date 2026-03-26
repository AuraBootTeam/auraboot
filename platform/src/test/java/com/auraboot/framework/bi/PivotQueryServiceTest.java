package com.auraboot.framework.bi;

import com.auraboot.framework.bi.dto.PivotQueryRequest;
import com.auraboot.framework.bi.dto.PivotQueryResponse;
import com.auraboot.framework.bi.service.impl.PivotQueryServiceImpl;
import com.auraboot.framework.datasource.dao.mapper.DynamicQueryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PivotQueryService.
 * Tests pivot logic, aggregation, and input validation.
 */
@ExtendWith(MockitoExtension.class)
class PivotQueryServiceTest {

    @Mock
    private DynamicQueryMapper dynamicQueryMapper;

    @InjectMocks
    private PivotQueryServiceImpl pivotQueryService;

    private PivotQueryRequest basicRequest;

    @BeforeEach
    void setUp() {
        basicRequest = new PivotQueryRequest();
        basicRequest.setModelCode("ns_content");
        basicRequest.setRowDimensions(List.of("category"));
        basicRequest.setColDimensions(List.of("status"));
        basicRequest.setValueField("file_size");
        basicRequest.setAggregation("sum");
        basicRequest.setMaxColumns(50);
        basicRequest.setIncludeSubtotals(true);
        basicRequest.setIncludeGrandTotal(true);
    }

    @Test
    void executePivot_withValidRequest_returnsPivotedData() {
        // Arrange: simulate grouped query results
        List<Map<String, Object>> rawData = List.of(
                Map.of("category", "image", "status", "active", "agg_value", 1000L),
                Map.of("category", "image", "status", "draft", "agg_value", 200L),
                Map.of("category", "video", "status", "active", "agg_value", 5000L),
                Map.of("category", "video", "status", "draft", "agg_value", 800L)
        );
        when(dynamicQueryMapper.queryData(anyString())).thenReturn(rawData);
        when(dynamicQueryMapper.countData(anyString())).thenReturn(100L);

        // Act
        PivotQueryResponse response = pivotQueryService.executePivot(basicRequest, 1L);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getRowHeaders()).hasSize(2);
        assertThat(response.getColHeaders()).containsExactly("active", "draft");
        assertThat(response.getCells()).hasSize(2);
        assertThat(response.getTotalRecords()).isEqualTo(100L);
        assertThat(response.getAggregation()).isEqualTo("sum");
        assertThat(response.getValueField()).isEqualTo("file_size");

        // Verify first row (IMAGE): ACTIVE=1000, DRAFT=200
        assertThat(response.getCells().get(0)).containsExactly(1000L, 200L);
        // Verify second row (VIDEO): ACTIVE=5000, DRAFT=800
        assertThat(response.getCells().get(1)).containsExactly(5000L, 800L);

        // Row subtotals
        assertThat(response.getRowSubtotals()).isNotNull();
        assertThat(response.getRowSubtotals()).hasSize(2);
        assertThat(((Number) response.getRowSubtotals().get(0)).doubleValue()).isEqualTo(1200.0);
        assertThat(((Number) response.getRowSubtotals().get(1)).doubleValue()).isEqualTo(5800.0);

        // Column subtotals
        assertThat(response.getColSubtotals()).isNotNull();
        assertThat(response.getColSubtotals()).hasSize(2);

        // Grand total
        assertThat(response.getGrandTotal()).isNotNull();
        assertThat(((Number) response.getGrandTotal()).doubleValue()).isEqualTo(7000.0);
    }

    @Test
    void executePivot_withoutColDimension_returnsSimplePivot() {
        basicRequest.setColDimensions(null);

        List<Map<String, Object>> rawData = List.of(
                Map.of("category", "image", "agg_value", 1200L),
                Map.of("category", "video", "agg_value", 5800L)
        );
        when(dynamicQueryMapper.queryData(anyString())).thenReturn(rawData);
        when(dynamicQueryMapper.countData(anyString())).thenReturn(50L);

        PivotQueryResponse response = pivotQueryService.executePivot(basicRequest, 1L);

        assertThat(response.getRowHeaders()).hasSize(2);
        assertThat(response.getCells()).hasSize(2);
        assertThat(response.getCells().get(0)).containsExactly(1200L);
        assertThat(response.getCells().get(1)).containsExactly(5800L);
        assertThat(((Number) response.getGrandTotal()).doubleValue()).isEqualTo(7000.0);
    }

    @Test
    void executePivot_invalidAggregation_throwsException() {
        basicRequest.setAggregation("invalid");

        assertThatThrownBy(() -> pivotQueryService.executePivot(basicRequest, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid aggregation");
    }

    @Test
    void executePivot_invalidIdentifier_throwsException() {
        basicRequest.setModelCode("DROP TABLE; --");

        assertThatThrownBy(() -> pivotQueryService.executePivot(basicRequest, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid SQL identifier");
    }

    @Test
    void executePivot_emptyResult_returnsEmptyPivot() {
        when(dynamicQueryMapper.queryData(anyString())).thenReturn(List.of());
        when(dynamicQueryMapper.countData(anyString())).thenReturn(0L);

        PivotQueryResponse response = pivotQueryService.executePivot(basicRequest, 1L);

        assertThat(response.getRowHeaders()).isEmpty();
        assertThat(response.getTotalRecords()).isEqualTo(0);
    }

    @Test
    void executePivot_withFilters_includesFilterInQuery() {
        basicRequest.setFilters(List.of(
                Map.of("fieldName", "status", "operator", "EQ", "value", "active")
        ));

        List<Map<String, Object>> rawData = List.of(
                Map.of("category", "image", "status", "active", "agg_value", 1000L)
        );
        when(dynamicQueryMapper.queryData(anyString())).thenReturn(rawData);
        when(dynamicQueryMapper.countData(anyString())).thenReturn(10L);

        PivotQueryResponse response = pivotQueryService.executePivot(basicRequest, 1L);

        assertThat(response.getRowHeaders()).hasSize(1);
        assertThat(response.getTotalRecords()).isEqualTo(10L);
    }
}
