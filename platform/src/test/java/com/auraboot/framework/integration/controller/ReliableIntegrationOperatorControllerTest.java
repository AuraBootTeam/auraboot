package com.auraboot.framework.integration.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.DeadLetterDetail;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.DeadLetterSummary;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.ReplayRequest;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.ReplayResult;
import com.auraboot.framework.integration.ReliableIntegrationOperatorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReliableIntegrationOperatorControllerTest {

    private static final long TENANT_ID = 42L;
    private static final String USER_PID = "01M1OPERATOR00000000000000";

    @Mock private ReliableIntegrationOperatorService service;

    private ReliableIntegrationOperatorController controller;
    private MockedStatic<MetaContext> metaContext;

    @BeforeEach
    void setUp() {
        controller = new ReliableIntegrationOperatorController(service);
        metaContext = Mockito.mockStatic(MetaContext.class);
        metaContext.when(MetaContext::getCurrentTenantId).thenReturn(TENANT_ID);
        metaContext.when(MetaContext::getCurrentUserPid).thenReturn(USER_PID);
    }

    @AfterEach
    void tearDown() {
        metaContext.close();
    }

    @Test
    void listUsesCurrentTenantAndBoundedOneBasedPagination() {
        DeadLetterSummary row = new DeadLetterSummary(
                "evt-1", "inventory.issued.v1", "inventory", "work-order/WO-1",
                "corr-1", "open", "consumer unavailable", Instant.EPOCH,
                null, null, 0, 1, 0);
        PageResult<DeadLetterSummary> page = new PageResult<>(List.of(row), 1L, 20L, 1L);
        when(service.list(TENANT_ID, "open", "inventory.issued.v1", "corr-1", 1, 20))
                .thenReturn(page);

        ApiResponse<PageResult<DeadLetterSummary>> response =
                controller.list("open", "inventory.issued.v1", "corr-1", 1, 20);

        assertThat(response.getData().getRecords()).containsExactly(row);
        verify(service).list(TENANT_ID, "open", "inventory.issued.v1", "corr-1", 1, 20);
    }

    @Test
    void detailUsesPublicEventIdInsideCurrentTenant() {
        DeadLetterDetail detail = new DeadLetterDetail(null, List.of(), List.of());
        when(service.detail(TENANT_ID, "evt-1")).thenReturn(detail);

        ApiResponse<DeadLetterDetail> response = controller.detail("evt-1");

        assertThat(response.getData()).isSameAs(detail);
        verify(service).detail(TENANT_ID, "evt-1");
    }

    @Test
    void replayAttributesActorReasonAndExpectedReplayCount() {
        ReplayRequest request = new ReplayRequest("consumer deployed", 2);
        ReplayResult result = new ReplayResult(
                "evt-1", "pending", 3, USER_PID, "consumer deployed", "corr-1", Instant.EPOCH);
        when(service.replay(TENANT_ID, "evt-1", USER_PID, "consumer deployed", 2))
                .thenReturn(result);

        ApiResponse<ReplayResult> response = controller.replay("evt-1", request);

        assertThat(response.getCode()).isEqualTo("0");
        assertThat(response.getData()).isSameAs(result);
        verify(service).replay(TENANT_ID, "evt-1", USER_PID, "consumer deployed", 2);
    }

    @Test
    void replayDoesNotRevealCrossTenantExistence() {
        when(service.replay(TENANT_ID, "evt-other", USER_PID, "retry", 0))
                .thenThrow(new NoSuchElementException("dead letter not found"));

        ApiResponse<ReplayResult> response =
                controller.replay("evt-other", new ReplayRequest("retry", 0));

        assertThat(response.getCode()).isEqualTo("404");
        assertThat(response.getMessage()).isEqualTo("dead letter not found");
    }

    @Test
    void replayRejectsStaleOrAlreadyReplayedRequest() {
        when(service.replay(TENANT_ID, "evt-1", USER_PID, "retry", 1))
                .thenThrow(new IllegalStateException("dead letter changed or is not open"));

        ApiResponse<ReplayResult> response =
                controller.replay("evt-1", new ReplayRequest("retry", 1));

        assertThat(response.getCode()).isEqualTo("409");
        assertThat(response.getMessage()).isEqualTo("dead letter changed or is not open");
    }
}
