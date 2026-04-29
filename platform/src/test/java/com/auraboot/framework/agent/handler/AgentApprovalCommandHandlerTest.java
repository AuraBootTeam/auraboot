package com.auraboot.framework.agent.handler;

import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.agent.service.AgentApprovalGateService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Agent approval command handler")
class AgentApprovalCommandHandlerTest {

    @Mock private AgentApprovalGateService approvalGateService;
    @Mock private AgentChatPort agentChatPort;
    @Mock private ObjectProvider<AgentApprovalGateService> approvalGateServiceProvider;
    @Mock private ObjectProvider<AgentChatPort> agentChatPortProvider;

    private AgentApprovalCommandHandler handler;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(7L, 11L, "user-11", "approver");
        when(approvalGateServiceProvider.getObject()).thenReturn(approvalGateService);
        lenient().when(agentChatPortProvider.getObject()).thenReturn(agentChatPort);
        handler = new AgentApprovalCommandHandler(approvalGateServiceProvider, agentChatPortProvider);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("approve command delegates to approval service and resumes chat pending tool")
    void approveDelegatesAndExecutesPendingChatTool() throws Exception {
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("pid", "approval-1");
        approval.put("approval_status", "approved");
        when(approvalGateService.isAuthorizedApprover(7L, "approval-1", 11L)).thenReturn(true);
        when(approvalGateService.approve(7L, "approval-1", 11L)).thenReturn(approval);
        when(agentChatPort.executeApprovedPendingTool(7L, "approval-1"))
                .thenReturn(Map.of("handled", true, "success", true));

        Object result = handler.execute(context("acp:approve_request", "approval-1", Map.of()));

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(resultMap).containsEntry("pid", "approval-1")
                .containsEntry("approval_status", "approved");
        assertThat(resultMap.get("toolExecutionResult")).isEqualTo(
                Map.of("handled", true, "success", true));
    }

    @Test
    @DisplayName("approve command rejects unauthorized users before mutating approval")
    void approveRejectsUnauthorizedUser() {
        when(approvalGateService.isAuthorizedApprover(7L, "approval-1", 11L)).thenReturn(false);

        assertThatThrownBy(() -> handler.execute(context("acp:approve_request", "approval-1", Map.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not authorized");

        verify(approvalGateService, never()).approve(anyLong(), anyString(), anyLong());
        verifyNoInteractions(agentChatPort);
    }

    @Test
    @DisplayName("reject command delegates to approval service with explicit reason")
    void rejectDelegatesWithReason() throws Exception {
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("pid", "approval-1");
        approval.put("approval_status", "rejected");
        approval.put("rejection_reason", "Needs more evidence");
        when(approvalGateService.isAuthorizedApprover(7L, "approval-1", 11L)).thenReturn(true);
        when(approvalGateService.reject(7L, "approval-1", 11L, "Needs more evidence"))
                .thenReturn(approval);

        Object result = handler.execute(context(
                "acp:reject_request",
                "approval-1",
                Map.of("rejection_reason", "Needs more evidence")));

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(resultMap).containsEntry("approval_status", "rejected")
                .containsEntry("rejection_reason", "Needs more evidence");
        verifyNoInteractions(agentChatPort);
    }

    private CommandHandlerExtension.CommandContext context(
            String commandType,
            String recordId,
            Map<String, Object> payload) {
        return CommandHandlerExtension.CommandContext.builder()
                .tenantId(7L)
                .namespace("acp")
                .commandType(commandType)
                .modelCode("agent_approval")
                .recordId(recordId)
                .payload(payload)
                .build();
    }
}
