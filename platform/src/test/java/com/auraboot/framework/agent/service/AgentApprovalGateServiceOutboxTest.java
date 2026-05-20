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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentApprovalGateService approval notification outbox")
class AgentApprovalGateServiceOutboxTest {

    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private AuraEventBus eventBus;
    @Mock private AgentDispatchHandler dispatchHandler;
    @Mock private ApprovalNotificationOutbox approvalNotificationOutbox;

    @Test
    @DisplayName("checkAndRequestApproval enqueues one durable notification per approver rule")
    void checkAndRequestApprovalEnqueuesNotificationsForApproverRules() {
        AgentApprovalGateService service = new AgentApprovalGateService(
                dynamicDataMapper,
                new ObjectMapper(),
                eventBus,
                dispatchHandler,
                approvalNotificationOutbox);
        when(dynamicDataMapper.selectByQuery(anyString(), any()))
                .thenReturn(List.of(policyRow()))
                .thenReturn(List.of());
        when(dynamicDataMapper.insert(eq("ab_agent_approval"), any())).thenReturn(1);

        String approvalPid = service.checkAndRequestApproval(
                1L,
                "run-1",
                "task-1",
                "cmd_update_customer",
                "Update customer",
                Map.of("recordId", "C-1"),
                true);

        assertThat(approvalPid).isNotBlank();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(approvalNotificationOutbox).enqueue(
                eq(1L), eq(approvalPid), eq("user"), eq("99"), eq("inbox"), payloadCaptor.capture());
        verify(approvalNotificationOutbox).enqueue(
                eq(1L), eq(approvalPid), eq("role"), eq("SALES_MANAGER"), eq("inbox"), any());
        assertThat(payloadCaptor.getValue())
                .containsEntry("approvalPid", approvalPid)
                .containsEntry("runId", "run-1")
                .containsEntry("taskId", "task-1")
                .containsEntry("toolCode", "cmd_update_customer");
    }

    private Map<String, Object> policyRow() {
        return Map.of(
                "pid", "policy-1",
                "trigger_rules", "[{\"type\":\"tool_call\",\"pattern\":\"cmd_.*\"}]",
                "approver_rules", """
                        [
                          {"type":"USER","userId":99},
                          {"type":"ROLE","roleCode":"SALES_MANAGER"}
                        ]
                        """,
                "timeout_hours", 24,
                "timeout_action", "reject");
    }
}
