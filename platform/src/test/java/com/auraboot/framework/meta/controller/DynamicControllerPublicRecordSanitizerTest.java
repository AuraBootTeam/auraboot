package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.DynamicBatchResponse;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicControllerPublicRecordSanitizerTest {

    @Mock
    private DynamicDataService dynamicDataService;

    @Mock
    private MetaModelService metaModelService;

    @Test
    void publicRecordRoutesUseRecordPidTemplates() throws NoSuchMethodException {
        assertMappingUsesRecordPid(DynamicController.class.getMethod("getById", String.class, String.class));
        assertMappingUsesRecordPid(DynamicController.class.getMethod(
                "getRecordCapabilities", String.class, String.class, String.class, String.class));
        assertMappingUsesRecordPid(DynamicController.class.getMethod(
                "update", String.class, String.class, Map.class));
        assertMappingUsesRecordPid(DynamicController.class.getMethod("delete", String.class, String.class));
        assertMappingUsesRecordPid(DynamicController.class.getMethod(
                "getRelationData", String.class, String.class, String.class));
    }

    @Test
    void publicListCursorUsesPidToken() throws NoSuchMethodException {
        Method listMethod = DynamicController.class.getMethod(
                "list",
                String.class,
                Integer.class,
                Integer.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class);

        assertThat(listMethod.getParameterTypes()[9]).isEqualTo(String.class);
    }

    @Test
    void paginationCursorAcceptsPublicPidToken() {
        PaginationResult<Map<String, Object>> result = PaginationResult.ofCursor(
                List.of(Map.of("pid", "rec-next")),
                2L,
                1,
                "rec-next");

        assertThat(result.getNextCursor()).isEqualTo("rec-next");
    }

    @Test
    void listSanitizesPublicRecords() {
        DynamicController controller = controller();
        when(metaModelService.getModelDefinition("order")).thenReturn(Optional.of(model("order")));
        when(dynamicDataService.list(eq("order"), any()))
                .thenReturn(PaginationResult.of(List.of(row(1L, "p1")), 1L, 1, 20));

        ApiResponse<PaginationResult<Map<String, Object>>> response = controller.list(
                "order", 1, 20, null, null, null, null, null, null, null);

        assertPublicRecord(response.getData().getRecords().get(0), "p1");
        assertThat(response.getData().getTotal()).isEqualTo(1L);
    }

    @Test
    void namedQueryListSanitizesPublicRecords() {
        DynamicController controller = controller();
        when(metaModelService.getModelDefinition("order")).thenReturn(Optional.of(model("order")));
        when(dynamicDataService.listByQueryCode(eq("order_query"), any()))
                .thenReturn(PaginationResult.of(List.of(row(2L, "p2")), 1L, 1, 20));

        ApiResponse<PaginationResult<Map<String, Object>>> response = controller.list(
                "order", 1, 20, null, null, null, null, null, "order_query", null);

        assertPublicRecord(response.getData().getRecords().get(0), "p2");
    }

    @Test
    void listResolvesRuntimeRecordInternalIdFilterWithoutExposingRawId() {
        DynamicController controller = controller();
        when(metaModelService.getModelDefinition("child")).thenReturn(Optional.of(model("child")));
        when(dynamicDataService.getById("parent", "parent-pid")).thenReturn(row(42L, "parent-pid"));
        when(dynamicDataService.list(eq("child"), any()))
                .thenReturn(PaginationResult.of(List.of(row(11L, "child-pid")), 1L, 1, 20));

        String filters = """
                [{
                  "fieldName": "parent_id",
                  "operator": "EQ",
                  "value": {
                    "$recordInternalId": {
                      "modelCode": "parent",
                      "recordPid": "parent-pid"
                    }
                  }
                }]
                """;

        ApiResponse<PaginationResult<Map<String, Object>>> response = controller.list(
                "child", 1, 20, null, filters, null, null, null, null, null);

        ArgumentCaptor<DynamicQueryRequest> requestCaptor = ArgumentCaptor.forClass(DynamicQueryRequest.class);
        verify(dynamicDataService).list(eq("child"), requestCaptor.capture());
        QueryCondition condition = requestCaptor.getValue().getConditions().get(0);
        assertThat(condition.getFieldName()).isEqualTo("parent_id");
        assertThat(condition.getValue()).isEqualTo(42L);
        assertPublicRecord(response.getData().getRecords().get(0), "child-pid");
    }

    @Test
    void detailCreateAndUpdateSanitizePublicRecords() {
        DynamicController controller = controller();
        when(metaModelService.getModelDefinition("order")).thenReturn(Optional.of(model("order")));
        when(dynamicDataService.getById("order", "p3")).thenReturn(row(3L, "p3"));
        when(dynamicDataService.create(eq("order"), any())).thenReturn(row(4L, "p4"));
        when(dynamicDataService.update(eq("order"), eq("p5"), any())).thenReturn(row(5L, "p5"));

        assertPublicRecord(controller.getById("order", "p3").getData(), "p3");
        assertPublicRecord(asMap(controller.create("order", Map.of("name", "new")).getData()), "p4");
        assertPublicRecord(controller.update("order", "p5", Map.of("name", "edited")).getData(), "p5");
    }

    @Test
    void batchCreateAndUpdateSanitizePublicRecords() {
        DynamicController controller = controller();
        when(metaModelService.getModelDefinition("order")).thenReturn(Optional.of(model("order")));
        when(dynamicDataService.batchCreate(eq("order"), any())).thenReturn(batch(row(6L, "p6")));
        when(dynamicDataService.batchUpdate(eq("order"), any())).thenReturn(batch(row(7L, "p7")));

        ApiResponse<DynamicBatchResponse> createResponse =
                controller.batchCreate("order", List.of(Map.of("name", "new")));
        ApiResponse<DynamicBatchResponse> updateResponse =
                controller.batchUpdate("order", List.of(Map.of("pid", "p7", "name", "edited")));

        assertPublicRecord(createResponse.getData().getSuccessItems().get(0), "p6");
        assertPublicRecord(updateResponse.getData().getSuccessItems().get(0), "p7");
    }

    @Test
    void queryAndRelationEndpointsSanitizePublicRecords() {
        DynamicController controller = controller();
        when(metaModelService.getModelDefinition("order")).thenReturn(Optional.of(model("order")));
        when(dynamicDataService.executeCustomQuery(eq("order"), eq("recent"), any()))
                .thenReturn(List.of(row(8L, "p8")));
        when(dynamicDataService.getRelationData(eq("order"), eq("p9"), eq("items"), any()))
                .thenReturn(List.of(row(9L, "p9")));

        ApiResponse<List<Map<String, Object>>> queryResponse =
                controller.executeCustomQuery("order", "recent", Map.of());
        ApiResponse<List<Map<String, Object>>> relationResponse =
                controller.getRelationData("order", "p9", "items");

        assertPublicRecord(queryResponse.getData().get(0), "p8");
        assertPublicRecord(relationResponse.getData().get(0), "p9");
    }

    private DynamicController controller() {
        DynamicController controller = new DynamicController();
        ReflectionTestUtils.setField(controller, "dynamicDataService", dynamicDataService);
        ReflectionTestUtils.setField(controller, "metaModelService", metaModelService);
        return controller;
    }

    private static ModelDefinition model(String code) {
        return ModelDefinition.builder().code(code).build();
    }

    private static DynamicBatchResponse batch(Map<String, Object> row) {
        DynamicBatchResponse response = new DynamicBatchResponse();
        response.setTotal(1);
        response.setSuccess(1);
        response.setSuccessItems(new ArrayList<>(List.of(row)));
        return response;
    }

    private static Map<String, Object> row(Long id, String pid) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("pid", pid);
        row.put("tenant_id", 321L);
        row.put("created_by", 7L);
        row.put("updated_by", 8L);
        row.put("name", "record-" + pid);
        return row;
    }

    private static void assertPublicRecord(Map<String, Object> record, String pid) {
        assertThat(record).containsEntry("pid", pid);
        assertThat(record).doesNotContainKeys("id", "tenant_id", "created_by", "updated_by");
    }

    private static void assertMappingUsesRecordPid(Method method) {
        String mapping = routeTemplate(method);
        assertThat(mapping).contains("{recordPid}");
        assertThat(mapping).doesNotContain("{recordId}");
    }

    private static String routeTemplate(Method method) {
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        if (getMapping != null) {
            return String.join(",", getMapping.value());
        }
        PutMapping putMapping = method.getAnnotation(PutMapping.class);
        if (putMapping != null) {
            return String.join(",", putMapping.value());
        }
        DeleteMapping deleteMapping = method.getAnnotation(DeleteMapping.class);
        if (deleteMapping != null) {
            return String.join(",", deleteMapping.value());
        }
        throw new AssertionError("Missing route mapping on " + method.getName());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
