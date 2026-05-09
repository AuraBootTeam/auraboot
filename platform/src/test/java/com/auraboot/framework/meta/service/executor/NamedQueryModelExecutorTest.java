package com.auraboot.framework.meta.service.executor;

import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NamedQueryModelExecutorTest {

    @Mock private MetaModelService metaModelService;
    @Mock private ApplicationContext applicationContext;
    @Mock private DynamicDataService dynamicDataService;

    @InjectMocks private NamedQueryModelExecutor executor;

    @BeforeEach
    void setupCtx() {
        when(applicationContext.getBean(DynamicDataService.class)).thenReturn(dynamicDataService);
    }

    private ModelDefinition def(String code, String sourceRef, String pk, ModelCapabilities caps) {
        return ModelDefinition.builder()
            .code(code)
            .sourceType("namedQuery")
            .sourceRef(sourceRef)
            .primaryKey(pk)
            .capabilities(caps)
            .build();
    }

    @Test
    void sourceType_is_namedQuery() {
        assertThat(executor.sourceType()).isEqualTo("namedQuery");
    }

    @Test
    void list_delegates_to_listByQueryCode() {
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "qcode", "id", null));
        DynamicQueryRequest req = DynamicQueryRequest.builder().pageNum(2).pageSize(5).build();
        PaginationResult<Map<String, Object>> result = PaginationResult.of(List.of(Map.of("id", 1)), 1L, 2, 5);
        when(dynamicDataService.listByQueryCode(eq("qcode"), eq(req))).thenReturn(result);

        PaginationResult<Map<String, Object>> r = executor.list("u", req);

        assertThat(r).isSameAs(result);
    }

    @Test
    void list_throws_when_model_not_found() {
        when(metaModelService.getDefinitionByCode("missing")).thenReturn(null);
        assertThatThrownBy(() -> executor.list("missing", DynamicQueryRequest.builder().build()))
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
    void get_uses_primaryKey_when_no_capabilities() {
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "qcode", "uid", null));
        Map<String, Object> row = Map.of("uid", "x", "name", "Alice");
        PaginationResult<Map<String, Object>> page = PaginationResult.of(List.of(row), 1L, 1, 1);
        when(dynamicDataService.listByQueryCode(eq("qcode"), any(DynamicQueryRequest.class))).thenReturn(page);

        Map<String, Object> r = executor.get("u", "x");

        assertThat(r).isEqualTo(row);
        ArgumentCaptor<DynamicQueryRequest> cap = ArgumentCaptor.forClass(DynamicQueryRequest.class);
        org.mockito.Mockito.verify(dynamicDataService).listByQueryCode(eq("qcode"), cap.capture());
        DynamicQueryRequest captured = cap.getValue();
        assertThat(captured.getPageNum()).isEqualTo(1);
        assertThat(captured.getPageSize()).isEqualTo(1);
        assertThat(captured.getConditions()).hasSize(1);
        QueryCondition c = captured.getConditions().get(0);
        assertThat(c.getFieldName()).isEqualTo("uid");
        assertThat(c.getOperator()).isEqualTo(QueryCondition.Operator.EQ);
        assertThat(c.getValue()).isEqualTo("x");
    }

    @Test
    void get_returns_null_when_no_records() {
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "qcode", "uid", null));
        when(dynamicDataService.listByQueryCode(eq("qcode"), any(DynamicQueryRequest.class)))
            .thenReturn(PaginationResult.of(List.of(), 0L, 1, 1));

        assertThat(executor.get("u", "x")).isNull();
    }

    @Test
    void get_returns_null_when_result_is_null() {
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "qcode", "uid", null));
        when(dynamicDataService.listByQueryCode(eq("qcode"), any(DynamicQueryRequest.class))).thenReturn(null);

        assertThat(executor.get("u", "x")).isNull();
    }

    @Test
    void get_throws_when_no_primaryKey_or_detailKey() {
        when(metaModelService.getDefinitionByCode("u")).thenReturn(def("u", "qcode", null, null));
        assertThatThrownBy(() -> executor.get("u", "anything"))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("missing primaryKey/detailKeyField");
    }

    @Test
    void get_throws_when_model_not_found() {
        when(metaModelService.getDefinitionByCode("u")).thenReturn(null);
        assertThatThrownBy(() -> executor.get("u", "1"))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("Model definition not found");
    }
}
