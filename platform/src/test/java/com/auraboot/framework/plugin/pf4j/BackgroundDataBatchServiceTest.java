package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackgroundDataBatchServiceTest {

    private final DynamicDataService dynamicDataService = mock(DynamicDataService.class);
    private final MetaModelService metaModelService = mock(MetaModelService.class);
    private final DynamicDataMapper mapper = mock(DynamicDataMapper.class);
    private final BackgroundDataBatchService service =
            new BackgroundDataBatchService(dynamicDataService, metaModelService, mapper);

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void queryPageForcesPidKeysetAndReturnsCursorOnlyForFullPage() {
        ModelDefinition model = model();
        when(metaModelService.getModelDefinition("job")).thenReturn(Optional.of(model));
        PaginationResult<Map<String, Object>> result = new PaginationResult<>();
        result.setRecords(List.of(Map.of("pid", "p1"), Map.of("pid", "p2")));
        when(dynamicDataService.list(eq("job"), any(DynamicQueryRequest.class)))
                .thenReturn(result);

        BackgroundDataAccessor.BoundedPage page = service.queryPage(
                "job", Map.of("lane", 3), null, 2);

        ArgumentCaptor<DynamicQueryRequest> request =
                ArgumentCaptor.forClass(DynamicQueryRequest.class);
        verify(dynamicDataService).list(eq("job"), request.capture());
        assertThat(request.getValue().getCursor()).isEmpty();
        assertThat(request.getValue().getPageSize()).isEqualTo(2);
        assertThat(request.getValue().getConditions()).singleElement().satisfies(condition -> {
            assertThat(condition.getFieldName()).isEqualTo("lane");
            assertThat(condition.getValue()).isEqualTo(3);
        });
        assertThat(page.records()).hasSize(2);
        assertThat(page.nextCursor()).isEqualTo("p2");
    }

    @Test
    void claimResolvesMetadataAndConvertsInstantsBeforeOneMapperCall() {
        ModelDefinition model = model();
        when(metaModelService.getModelDefinition("job")).thenReturn(Optional.of(model));
        when(mapper.atomicBatchClaimReturning(
                any(), any(), any(), any(), any(), any(), any(), eq(false), eq(2), eq(7L), eq(0L)))
                .thenReturn(new ArrayList<>(List.of(Map.of("pid", "p1"))));
        MetaContext.setContext(7L, 0L, null, "system");
        Instant due = Instant.parse("2026-08-24T00:00:00Z");
        Instant lease = due.plusSeconds(120);
        Map<String, Object> claimValues = new LinkedHashMap<>();
        claimValues.put("status", "publishing");
        claimValues.put("leasedUntil", lease);
        BackgroundDataAccessor.BatchClaimRequest request =
                new BackgroundDataAccessor.BatchClaimRequest(
                        "job", Map.of("lane", 3),
                        Map.of("status", List.of("pending", "retry", "publishing")),
                        Map.of("dueAt", due), claimValues, List.of("dueAt"), 2);

        List<Map<String, Object>> rows = service.claimBatch(7L, request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> upper = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> claims = ArgumentCaptor.forClass(Map.class);
        verify(mapper).atomicBatchClaimReturning(
                eq("mt_job"), eq("pid"), eq(Map.of("lane_no", 3)),
                eq(Map.of("status_code", List.of("pending", "retry", "publishing"))),
                upper.capture(), claims.capture(), eq(List.of("due_at")), eq(false),
                eq(2), eq(7L), eq(0L));
        assertThat(upper.getValue().get("due_at")).isEqualTo(Timestamp.from(due));
        assertThat(claims.getValue().get("status_code")).isEqualTo("publishing");
        assertThat(claims.getValue().get("leased_until")).isEqualTo(Timestamp.from(lease));
        assertThat(rows).extracting(row -> row.get("pid")).containsExactly("p1");
    }

    @Test
    void claimRejectsVirtualAndNonDynamicModelsBeforeSql() {
        ModelDefinition external = model();
        external.setTableName("external_jobs");
        when(metaModelService.getModelDefinition("job")).thenReturn(Optional.of(external));
        BackgroundDataAccessor.BatchClaimRequest request =
                new BackgroundDataAccessor.BatchClaimRequest(
                        "job", Map.of(), Map.of(), Map.of("dueAt", Instant.now()),
                        Map.of("status", "publishing"), List.of(), 1);

        assertThatThrownBy(() -> service.claimBatch(7L, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Aura-managed dynamic table");
    }

    private static ModelDefinition model() {
        return ModelDefinition.builder()
                .code("job")
                .tableName("mt_job")
                .modelType("entity")
                .sourceType("physical")
                .fields(List.of(
                        field("lane", "lane_no", "integer"),
                        field("status", "status_code", "string"),
                        field("dueAt", "due_at", "datetime"),
                        field("leasedUntil", "leased_until", "datetime")))
                .build();
    }

    private static FieldDefinition field(String code, String column, String dataType) {
        return FieldDefinition.builder()
                .code(code)
                .columnName(column)
                .dataType(dataType)
                .build();
    }
}
