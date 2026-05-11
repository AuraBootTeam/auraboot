package com.auraboot.framework.mobile.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.mobile.dto.MobileSearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MobileSearchControllerTest {

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private DynamicDataService dynamicDataService;

    @Test
    void searchUsesKeywordSearchAndReturnsMobileHitsContract() {
        MobileSearchController controller = new MobileSearchController(metaModelService, dynamicDataService);
        MetaModelDTO model = MetaModelDTO.builder()
                .code("e2et_order")
                .displayName("E2E Order")
                .build();
        when(metaModelService.searchModels(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(new PageResult<>(List.of(model), 1L, 20L, 1L));
        when(metaModelService.getModelDefinition("e2et_order")).thenReturn(Optional.empty());
        when(dynamicDataService.list(eq("e2et_order"), any(DynamicQueryRequest.class)))
                .thenReturn(PaginationResult.of(List.of(Map.of(
                        "id", 1001L,
                        "pid", "order-pid-1",
                        "e2et_order_no", "visual_order_1",
                        "e2et_order_status", "draft"
                )), 1L, 1, 3));

        ApiResponse<MobileSearchResult> response = controller.search("visual", null, null, 20);

        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals("visual", response.getData().getKeyword());
        assertEquals(1, response.getData().getTotalCount());
        assertFalse(response.getData().getHits().isEmpty());
        MobileSearchResult.SearchHit hit = response.getData().getHits().get(0);
        assertEquals("e2et_order", hit.getModelCode());
        assertEquals("1001", hit.getRecordId());
        assertEquals("order-pid-1", hit.getRecordPid());
        assertEquals("visual_order_1", hit.getDisplayName());
        assertEquals("record", hit.getType());

        ArgumentCaptor<DynamicQueryRequest> requestCaptor = ArgumentCaptor.forClass(DynamicQueryRequest.class);
        verify(dynamicDataService).list(eq("e2et_order"), requestCaptor.capture());
        assertEquals("visual", requestCaptor.getValue().getKeyword());
    }
}
