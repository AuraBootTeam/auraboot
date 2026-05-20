package com.auraboot.framework.agent.service;

import com.auraboot.framework.event.AuraEventBus;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentApprovalGateService concurrency")
class AgentApprovalGateServiceConcurrencyTest {

    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private AuraEventBus eventBus;
    @Mock private AgentDispatchHandler dispatchHandler;

    @Test
    @DisplayName("approve uses pending-status compare-and-set update")
    void approveUsesPendingStatusCompareAndSetUpdate() {
        AgentApprovalGateService service = newService();
        when(dynamicDataMapper.selectByQuery(anyString(), any()))
                .thenReturn(List.of(Map.of("pid", "apv-1", "approval_status", "pending")))
                .thenReturn(List.of(pendingApproval()))
                .thenReturn(List.of());
        when(dynamicDataMapper.selectByQueryWithoutTenant(anyString(), any())).thenReturn(List.of());
        when(dynamicDataMapper.update(eq("ab_agent_approval"), any(), any())).thenReturn(1);

        service.approve(1L, "apv-1", 99L, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> conditions = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).update(eq("ab_agent_approval"), any(), conditions.capture());
        assertThat(conditions.getValue())
                .containsEntry("pid", "apv-1")
                .containsEntry("approval_status", "pending");
    }

    @Test
    @DisplayName("approve fails closed when another worker consumes the pending row first")
    void approveFailsClosedWhenConditionalUpdateTouchesNoRows() {
        AgentApprovalGateService service = newService();
        when(dynamicDataMapper.selectByQuery(anyString(), any()))
                .thenReturn(List.of(Map.of("pid", "apv-1", "approval_status", "pending")))
                .thenReturn(List.of(pendingApproval()));
        when(dynamicDataMapper.update(eq("ab_agent_approval"), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.approve(1L, "apv-1", 99L, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Approval already processed");
        verify(eventBus, never()).publishAfterCommit(any());
    }

    private AgentApprovalGateService newService() {
        return new AgentApprovalGateService(
                dynamicDataMapper,
                new ObjectMapper(),
                eventBus,
                dispatchHandler);
    }

    private Map<String, Object> pendingApproval() {
        Map<String, Object> row = new HashMap<>();
        row.put("pid", "apv-1");
        row.put("tenant_id", 1L);
        row.put("run_id", "run-1");
        row.put("task_id", "task-1");
        row.put("request_data", "{}");
        row.put("approval_status", "pending");
        return row;
    }
}
