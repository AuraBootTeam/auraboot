package com.auraboot.framework.meta.service.executor;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.dto.SortField;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqlViewModelExecutorTest {

    @Mock private MetaModelService metaModelService;
    @Mock private JdbcTemplate jdbc;

    @InjectMocks private SqlViewModelExecutor executor;

    @BeforeEach
    void setupCtx() {
        MetaContext.setContext(7L, 99L, "u-1", "alice");
    }

    @AfterEach
    void clearCtx() {
        MetaContext.clear();
    }

    private ModelDefinition def(String code, String view, String pk, ModelCapabilities caps) {
        return ModelDefinition.builder()
            .code(code)
            .sourceType("sqlView")
            .sourceRef(view)
            .primaryKey(pk)
            .capabilities(caps)
            .build();
    }

    @Test
    void sourceType_is_sqlView() {
        assertThat(executor.sourceType()).isEqualTo("sqlView");
    }

    @Test
    void list_throws_when_view_name_unsafe() {
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "bad name; DROP", "id", null));
        assertThatThrownBy(() -> executor.list("u", DynamicQueryRequest.builder().build()))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("unsafe SQL identifier");
    }

    @Test
    void list_throws_when_model_missing() {
        when(metaModelService.getDefinitionByCode("u")).thenReturn(null);
        assertThatThrownBy(() -> executor.list("u", DynamicQueryRequest.builder().build()))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("Model definition not found");
    }

    @Test
    void list_throws_when_sourceRef_blank() {
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "", "id", null));
        assertThatThrownBy(() -> executor.list("u", DynamicQueryRequest.builder().build()))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("missing sourceRef");
    }

    @Test
    void list_returns_empty_when_count_zero_and_skips_data_query() {
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "v_users", "id", ModelCapabilities.empty()));
        // information_schema returns 0 cols
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);

        DynamicQueryRequest req = DynamicQueryRequest.builder().pageNum(1).pageSize(20).build();
        PaginationResult<Map<String, Object>> r = executor.list("u", req);

        assertThat(r.getRecords()).isEmpty();
        assertThat(r.getTotal()).isEqualTo(0L);
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void list_runs_count_then_data_with_pagination_params() {
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "v_users", "id", ModelCapabilities.empty()));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0); // no tenant_id
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);
        Map<String, Object> row = Map.of("id", 1, "name", "x");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));

        PaginationResult<Map<String, Object>> r = executor.list("u",
            DynamicQueryRequest.builder().pageNum(2).pageSize(10).build());

        assertThat(r.getTotal()).isEqualTo(2L);
        assertThat(r.getRecords()).containsExactly(row);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), params.capture());
        assertThat(sql.getValue()).contains("FROM v_users").contains("LIMIT ? OFFSET ?");
        Object[] p = params.getValue();
        // last two are pageSize, offset
        assertThat(p[p.length - 2]).isEqualTo(10);
        assertThat(p[p.length - 1]).isEqualTo(10); // (2-1)*10
    }

    @Test
    void list_appends_tenant_filter_when_view_has_tenant_id_column() {
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "v_users", "id", ModelCapabilities.empty()));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1); // has tenant_id
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("id", 1)));

        executor.list("u", DynamicQueryRequest.builder().pageNum(1).pageSize(20).build());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("WHERE tenant_id = ?");
    }

    @Test
    void list_rejects_filter_on_non_whitelisted_field() {
        ModelCapabilities caps = ModelCapabilities.builder().filter(true).filterableFields(List.of("name")).build();
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "v_users", "id", caps));
        lenient().when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);

        DynamicQueryRequest req = DynamicQueryRequest.builder()
            .conditions(List.of(QueryCondition.builder().fieldName("ssn").operator(QueryCondition.Operator.EQ).value("x").build()))
            .build();

        assertThatThrownBy(() -> executor.list("u", req))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("non-whitelisted");
    }

    @Test
    void list_rejects_sort_on_non_whitelisted_field() {
        ModelCapabilities caps = ModelCapabilities.builder().sort(true).sortableFields(List.of("name")).build();
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "v_users", "id", caps));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);

        DynamicQueryRequest req = DynamicQueryRequest.builder()
            .pageNum(1).pageSize(10)
            .sortFields(List.of(SortField.builder().fieldName("ssn").direction(SortField.SortDirection.ASC).build()))
            .build();

        assertThatThrownBy(() -> executor.list("u", req))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("Sort on non-whitelisted");
    }

    @Test
    void list_throws_on_in_with_empty_values() {
        ModelCapabilities caps = ModelCapabilities.builder().filter(true).filterableFields(List.of("name")).build();
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "v_users", "id", caps));
        lenient().when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);

        DynamicQueryRequest req = DynamicQueryRequest.builder()
            .conditions(List.of(QueryCondition.builder().fieldName("name").operator(QueryCondition.Operator.IN).build()))
            .build();
        assertThatThrownBy(() -> executor.list("u", req))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("non-empty values");
    }

    @Test
    void list_throws_on_between_wrong_arity() {
        ModelCapabilities caps = ModelCapabilities.builder().filter(true).filterableFields(List.of("name")).build();
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "v_users", "id", caps));
        lenient().when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);

        DynamicQueryRequest req = DynamicQueryRequest.builder()
            .conditions(List.of(QueryCondition.builder()
                .fieldName("name").operator(QueryCondition.Operator.BETWEEN)
                .values(List.of("a")).build()))
            .build();
        assertThatThrownBy(() -> executor.list("u", req))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("BETWEEN");
    }

    @Test
    void get_throws_when_pk_missing() {
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "v_users", null, null));
        assertThatThrownBy(() -> executor.get("u", "1"))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("missing primaryKey/detailKeyField");
    }

    @Test
    void get_throws_when_pk_unsafe_identifier() {
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "v_users", "id; --", null));
        assertThatThrownBy(() -> executor.get("u", "1"))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("unsafe SQL identifier");
    }

    @Test
    void get_returns_first_row_or_null() {
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "v_users", "id", null));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        Map<String, Object> row = Map.of("id", "abc");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));

        assertThat(executor.get("u", "abc")).isEqualTo(row);

        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        assertThat(executor.get("u", "abc")).isNull();
    }

    @Test
    void get_appends_tenant_filter_when_view_has_tenant_id() {
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "v_users", "id", null));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        executor.get("u", "abc");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("AND tenant_id = ?");
    }
}
