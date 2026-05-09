package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.dto.DynamicBatchResponse;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicDataAccessorImplTest {

    @Mock private DynamicDataService dynamicDataService;
    @InjectMocks private DynamicDataAccessorImpl accessor;

    @Test
    void getById_delegates() {
        when(dynamicDataService.getById("m", "1")).thenReturn(Map.of("id", "1"));
        assertThat(accessor.getById("m", "1")).containsEntry("id", "1");
    }

    @Test
    void query_builds_EQ_conditions_from_filters_map() {
        when(dynamicDataService.list(eq("m"), any(DynamicQueryRequest.class)))
            .thenReturn(PaginationResult.of(List.of(Map.of("a", 1)), 1L, 1, 10000));

        List<Map<String, Object>> rows = accessor.query("m", Map.of("status", "open"));
        assertThat(rows).hasSize(1);

        ArgumentCaptor<DynamicQueryRequest> cap = ArgumentCaptor.forClass(DynamicQueryRequest.class);
        verify(dynamicDataService).list(eq("m"), cap.capture());
        DynamicQueryRequest req = cap.getValue();
        assertThat(req.getPageNum()).isEqualTo(1);
        assertThat(req.getPageSize()).isEqualTo(10000);
        assertThat(req.getConditions()).hasSize(1);
        QueryCondition c = req.getConditions().get(0);
        assertThat(c.getFieldName()).isEqualTo("status");
        assertThat(c.getOperator()).isEqualTo(QueryCondition.Operator.EQ);
        assertThat(c.getValue()).isEqualTo("open");
    }

    @Test
    void query_with_null_filters_uses_empty_conditions() {
        when(dynamicDataService.list(eq("m"), any(DynamicQueryRequest.class)))
            .thenReturn(PaginationResult.of(List.of(), 0L, 1, 10000));

        accessor.query("m", null);

        ArgumentCaptor<DynamicQueryRequest> cap = ArgumentCaptor.forClass(DynamicQueryRequest.class);
        verify(dynamicDataService).list(eq("m"), cap.capture());
        assertThat(cap.getValue().getConditions()).isEmpty();
    }

    @Test
    void query_returns_empty_list_when_records_null() {
        PaginationResult<Map<String, Object>> r = new PaginationResult<>();
        r.setRecords(null);
        when(dynamicDataService.list(eq("m"), any())).thenReturn(r);
        assertThat(accessor.query("m", Map.of())).isEmpty();
    }

    @Test
    void create_delegates() {
        when(dynamicDataService.create(eq("m"), any())).thenReturn(Map.of("id", "1"));
        assertThat(accessor.create("m", Map.of("a", 1))).containsEntry("id", "1");
    }

    @Test
    void update_delegates() {
        when(dynamicDataService.update("m", "1", Map.of("a", 2))).thenReturn(Map.of("id", "1", "a", 2));
        assertThat(accessor.update("m", "1", Map.of("a", 2))).containsEntry("a", 2);
    }

    @Test
    void batchCreate_returns_successItems_when_present() {
        DynamicBatchResponse resp = new DynamicBatchResponse();
        resp.setSuccessItems(List.of(Map.of("id", "a"), Map.of("id", "b")));
        when(dynamicDataService.batchCreate(eq("m"), any())).thenReturn(resp);

        List<Map<String, Object>> r = accessor.batchCreate("m", List.of(Map.of("a", 1)));
        assertThat(r).hasSize(2);
    }

    @Test
    void batchCreate_falls_back_to_input_when_response_null() {
        when(dynamicDataService.batchCreate(eq("m"), any())).thenReturn(null);
        List<Map<String, Object>> input = List.of(Map.of("a", 1));
        assertThat(accessor.batchCreate("m", input)).isSameAs(input);
    }

    @Test
    void batchCreate_returns_empty_list_when_response_null_and_input_null() {
        when(dynamicDataService.batchCreate(eq("m"), any())).thenReturn(null);
        assertThat(accessor.batchCreate("m", null)).isEmpty();
    }

    @Test
    void delete_delegates() {
        accessor.delete("m", "1");
        verify(dynamicDataService).delete("m", "1");
    }
}
