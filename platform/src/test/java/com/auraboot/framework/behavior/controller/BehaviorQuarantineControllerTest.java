package com.auraboot.framework.behavior.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.BehaviorQuarantineReplayBatchResult;
import com.auraboot.framework.behavior.dto.BehaviorQuarantineReplayResult;
import com.auraboot.framework.behavior.entity.BehaviorQuarantine;
import com.auraboot.framework.behavior.service.BehaviorQuarantineService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.dto.PageResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BehaviorQuarantineControllerTest {

    private static final long TENANT = 42L;

    @Mock
    private BehaviorQuarantineService service;

    private BehaviorQuarantineController controller;
    private MockedStatic<MetaContext> metaContext;

    @BeforeEach
    void setup() {
        controller = new BehaviorQuarantineController(service);
        metaContext = Mockito.mockStatic(MetaContext.class);
        metaContext.when(MetaContext::getCurrentTenantId).thenReturn(TENANT);
    }

    @AfterEach
    void tearDown() {
        metaContext.close();
    }

    @Test
    void list_passesTenantReasonReplayStatusAndApiDatasourcePagination() {
        BehaviorQuarantine row = new BehaviorQuarantine();
        row.setId(100L);
        row.setReason("constraint_violation");
        row.setRawEvent("{\"eventId\":\"evt-1\"}");
        PageResult<BehaviorQuarantine> page = new PageResult<>(List.of(row), 1L, 20L, 1L);

        when(service.list(TENANT, "constraint_violation", "pending", 0, 20)).thenReturn(page);

        ApiResponse<PageResult<BehaviorQuarantine>> response =
                controller.list("constraint_violation", "pending", 0, 20, null, null);

        assertThat(response.getCode()).isEqualTo("0");
        assertThat(response.getData().getRecords()).containsExactly(row);
        verify(service).list(TENANT, "constraint_violation", "pending", 0, 20);
    }

    @Test
    void list_clampsOneBasedPageNumBeforeZeroBasedConversion() {
        PageResult<BehaviorQuarantine> page = new PageResult<>(List.of(), 0L, 20L, 1L);
        when(service.list(TENANT, null, null, 0, 20)).thenReturn(page);

        ApiResponse<PageResult<BehaviorQuarantine>> response =
                controller.list(null, null, 0, 20, Integer.MIN_VALUE, null);

        assertThat(response.getCode()).isEqualTo("0");
        verify(service).list(TENANT, null, null, 0, 20);
    }

    @Test
    void replayOne_usesCurrentTenantAndReturnsReplayResult() {
        BehaviorQuarantineReplayResult result =
                new BehaviorQuarantineReplayResult(100L, "replayed", "evt-1", 901L, null);
        when(service.replayOne(TENANT, 100L)).thenReturn(result);

        ApiResponse<BehaviorQuarantineReplayResult> response = controller.replayOne(100L);

        assertThat(response.getCode()).isEqualTo("0");
        assertThat(response.getData()).isSameAs(result);
        verify(service).replayOne(TENANT, 100L);
    }

    @Test
    void replayPending_usesReasonFilterAndLimit() {
        BehaviorQuarantineReplayBatchResult result = new BehaviorQuarantineReplayBatchResult(
                2, 1, 1, 0, List.of(
                new BehaviorQuarantineReplayResult(100L, "replayed", "evt-1", 901L, null),
                new BehaviorQuarantineReplayResult(101L, "duplicate", "evt-2", 777L, null)));
        when(service.replayPending(TENANT, "constraint_violation", 50)).thenReturn(result);

        ApiResponse<BehaviorQuarantineReplayBatchResult> response =
                controller.replayPending("constraint_violation", 50);

        assertThat(response.getData().replayed()).isEqualTo(1);
        assertThat(response.getData().duplicate()).isEqualTo(1);
        verify(service).replayPending(TENANT, "constraint_violation", 50);
    }
}
