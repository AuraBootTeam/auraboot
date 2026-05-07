package com.auraboot.framework.bi;

import com.auraboot.framework.bi.dto.PivotQueryRequest;
import com.auraboot.framework.bi.dto.PivotQueryResponse;
import com.auraboot.framework.bi.service.impl.PivotQueryServiceImpl;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PivotQueryService — covers the in-memory pivot shaping plus
 * the validation rules introduced by the B0 SQL-injection hotfix.
 *
 * <p>Real SQL parameterisation and end-to-end injection attempts are covered
 * by {@code PivotQueryServiceSecurityIntegrationTest} against a live database.
 */
@ExtendWith(MockitoExtension.class)
class PivotQueryServiceTest {

    @Mock
    private DynamicDataMapper dynamicDataMapper;

    @Mock
    private MetaModelService metaModelService;

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

        // Default model definition matching the basic request.
        ModelDefinition model = ModelDefinition.builder()
                .code("ns_content")
                .tableName("ns_content")
                .fields(List.of(
                        field("category"),
                        field("status"),
                        field("file_size")))
                .build();
        lenient().when(metaModelService.getModelDefinition("ns_content"))
                .thenReturn(Optional.of(model));
    }

    private FieldDefinition field(String code) {
        return FieldDefinition.builder()
                .code(code)
                .columnName(code)
                .build();
    }

    @Test
    void executePivot_withValidRequest_returnsPivotedData() {
        List<Map<String, Object>> rawData = List.of(
                Map.of("category", "image", "status", "active", "agg_value", 1000L),
                Map.of("category", "image", "status", "draft", "agg_value", 200L),
                Map.of("category", "video", "status", "active", "agg_value", 5000L),
                Map.of("category", "video", "status", "draft", "agg_value", 800L)
        );
        when(dynamicDataMapper.selectByQuery(anyString(), any())).thenReturn(rawData);
        when(dynamicDataMapper.countByQuery(anyString(), any())).thenReturn(100L);

        PivotQueryResponse response = pivotQueryService.executePivot(basicRequest, 1L);

        assertThat(response).isNotNull();
        assertThat(response.getRowHeaders()).hasSize(2);
        assertThat(response.getColHeaders()).containsExactly("active", "draft");
        assertThat(response.getCells()).hasSize(2);
        assertThat(response.getTotalRecords()).isEqualTo(100L);
        assertThat(response.getAggregation()).isEqualTo("sum");
        assertThat(response.getValueField()).isEqualTo("file_size");

        assertThat(response.getCells().get(0)).containsExactly(1000L, 200L);
        assertThat(response.getCells().get(1)).containsExactly(5000L, 800L);

        assertThat(response.getRowSubtotals()).hasSize(2);
        assertThat(((Number) response.getRowSubtotals().get(0)).doubleValue()).isEqualTo(1200.0);
        assertThat(((Number) response.getRowSubtotals().get(1)).doubleValue()).isEqualTo(5800.0);

        assertThat(response.getColSubtotals()).hasSize(2);

        assertThat(((Number) response.getGrandTotal()).doubleValue()).isEqualTo(7000.0);
    }

    @Test
    void executePivot_withoutColDimension_returnsSimplePivot() {
        basicRequest.setColDimensions(null);

        List<Map<String, Object>> rawData = List.of(
                Map.of("category", "image", "agg_value", 1200L),
                Map.of("category", "video", "agg_value", 5800L)
        );
        when(dynamicDataMapper.selectByQuery(anyString(), any())).thenReturn(rawData);
        when(dynamicDataMapper.countByQuery(anyString(), any())).thenReturn(50L);

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
    void executePivot_unknownModel_throwsException() {
        basicRequest.setModelCode("ghost_model");
        when(metaModelService.getModelDefinition("ghost_model")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pivotQueryService.executePivot(basicRequest, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown modelCode");
    }

    @Test
    void executePivot_unknownField_throwsException() {
        basicRequest.setValueField("not_a_field");

        assertThatThrownBy(() -> pivotQueryService.executePivot(basicRequest, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown valueField");
    }

    @Test
    void executePivot_modelCodeWithSqlInjection_throwsException() {
        basicRequest.setModelCode("DROP TABLE; --");
        when(metaModelService.getModelDefinition(eq("DROP TABLE; --")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> pivotQueryService.executePivot(basicRequest, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executePivot_emptyResult_returnsEmptyPivot() {
        when(dynamicDataMapper.selectByQuery(anyString(), any())).thenReturn(List.of());
        when(dynamicDataMapper.countByQuery(anyString(), any())).thenReturn(0L);

        PivotQueryResponse response = pivotQueryService.executePivot(basicRequest, 1L);

        assertThat(response.getRowHeaders()).isEmpty();
        assertThat(response.getTotalRecords()).isEqualTo(0);
    }

    @Test
    void executePivot_withFilters_filterFieldMustExistInModel() {
        basicRequest.setFilters(List.of(
                Map.of("fieldName", "not_a_field", "operator", "EQ", "value", "active")
        ));

        assertThatThrownBy(() -> pivotQueryService.executePivot(basicRequest, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown filter field");
    }

    @Test
    void executePivot_filterWithUnknownOperator_throwsException() {
        basicRequest.setFilters(List.of(
                Map.of("fieldName", "status", "operator", "INJECT", "value", "active")
        ));

        assertThatThrownBy(() -> pivotQueryService.executePivot(basicRequest, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid operator");
    }

    @Test
    void executePivot_nullTenantId_throwsException() {
        assertThatThrownBy(() -> pivotQueryService.executePivot(basicRequest, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    /**
     * Verifies the SQL-injection hotfix contract: every user-controllable value
     * (tenantId, filter values) is passed via {@code #&#123;params.*&#125;} placeholders,
     * never concatenated into the SQL text. The malicious payload must show up in the
     * params map, not in the SQL string.
     */
    @Test
    void executePivot_parameterisesUserSuppliedValues() {
        String maliciousValue = "'; DROP TABLE ns_content; --";
        basicRequest.setFilters(List.of(
                Map.of("fieldName", "status", "operator", "EQ", "value", maliciousValue)
        ));
        when(dynamicDataMapper.selectByQuery(anyString(), any())).thenReturn(List.of());
        when(dynamicDataMapper.countByQuery(anyString(), any())).thenReturn(0L);

        pivotQueryService.executePivot(basicRequest, 42L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).selectByQuery(sqlCaptor.capture(), paramsCaptor.capture());

        String sql = sqlCaptor.getValue();
        Map<String, Object> params = paramsCaptor.getValue();

        // SQL must reference parameters by placeholder, not contain the values themselves.
        assertThat(sql).contains("#{params.tenantId}");
        assertThat(sql).contains("#{params.f0}");
        assertThat(sql).doesNotContain("DROP TABLE");
        assertThat(sql).doesNotContain("'42'");
        assertThat(sql).doesNotContain("42 ");
        // Exactly one literal "42" only as part of "tenant_id = #{params.tenantId}", never as digit.
        assertThat(sql.replaceAll("#\\{[^}]+\\}", "")).doesNotContain("42");

        // The malicious payload must travel as a JDBC parameter.
        assertThat(params).containsEntry("tenantId", 42L);
        assertThat(params).containsEntry("f0", maliciousValue);
    }
}
