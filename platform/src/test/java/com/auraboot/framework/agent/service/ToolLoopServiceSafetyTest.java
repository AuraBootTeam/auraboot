package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ToolLoopService safety gates")
class ToolLoopServiceSafetyTest {

    @Mock private ActionRecorder actionRecorder;
    @Mock private AgentApprovalGateService approvalGate;
    @Mock private ToolAclChecker toolAclChecker;
    @Mock private AiTraceService aiTraceService;
    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private CommandExecutor commandExecutor;
    @Mock private NamedQueryService namedQueryService;
    @Mock private ToolProviderRegistry toolProviderRegistry;
    @Mock private ResultContractEmitter resultContractEmitter;

    private ToolLoopService service;

    @BeforeEach
    void setup() {
        service = new ToolLoopService(
                actionRecorder,
                approvalGate,
                toolAclChecker,
                aiTraceService,
                dynamicDataMapper,
                commandExecutor,
                namedQueryService,
                new ObjectMapper(),
                toolProviderRegistry,
                resultContractEmitter);
        when(toolAclChecker.check(anyLong(), nullable(String.class), nullable(String.class), anyString(), anyString()))
                .thenReturn(ToolAclChecker.Decision.builder()
                        .allowed(true)
                        .matchedPriority(-1)
                        .reason("test_allow")
                        .build());
    }

    @AfterEach
    void cleanup() {
        BifContext.clear();
    }

    @Test
    @DisplayName("L2 confirmation-required command does not execute before user confirmation")
    void confirmationRequiredCommandDoesNotExecute() {
        Map<String, Object> input = Map.of("name", "Draft comparison");
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("cmd:pe:create_procurement_comparison_draft")
                .description("Create draft")
                .toolType("dsl_command")
                .sourceCode("pe:create_procurement_comparison_draft")
                .riskLevel("L2")
                .confirmationPolicy("confirm")
                .requiresConfirmation(true)
                .build();

        String result = service.executeToolCall(1L, "run-1", "task-1", "agent",
                tool.getName(), input, List.of(tool), null);

        assertThat(result).contains("requires user confirmation").contains("No data was changed");
        verify(resultContractEmitter).emitConfirmationRequired(eq(tool.getName()), same(tool), eq(input), eq(0L));
        verifyNoInteractions(commandExecutor);
        verify(actionRecorder, never()).recordAction(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("R3 alias in BIF risk escalates colon command tool to approval")
    void r3AliasEscalatesColonCommandToApproval() {
        BifContext.setCurrentBif(BusinessIntentFrame.builder()
                .intent("submit")
                .object("pe_procurement_comparison")
                .riskLevel("r3")
                .actionability("execute")
                .confidence(ConfidenceScore.of(0.9, 0.9))
                .build());
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("cmd:pe:submit_procurement_comparison")
                .description("Submit review")
                .toolType("dsl_command")
                .sourceCode("pe:submit_procurement_comparison")
                .riskLevel("L1")
                .requiresApproval(false)
                .build();
        when(approvalGate.checkAndRequestApproval(eq(1L), eq("run-2"), eq("task-2"), eq(tool.getName()),
                eq(tool.getDescription()), anyMap(), eq(true)))
                .thenReturn("approval-1");

        String result = service.executeToolCall(1L, "run-2", "task-2", "agent",
                tool.getName(), Map.of("pid", "pc-1"), List.of(tool), null);

        assertThat(result).contains("\"approvalRequired\":true").contains("\"approvalPid\":\"approval-1\"");
        verifyNoInteractions(commandExecutor);
    }

    @Test
    @DisplayName("approval-required command does not execute when no policy can create an approval")
    void approvalRequiredCommandWithoutPolicyDoesNotExecute() {
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("cmd:pe:submit_procurement_comparison")
                .description("Submit review")
                .toolType("dsl_command")
                .sourceCode("pe:submit_procurement_comparison")
                .riskLevel("L3")
                .requiresApproval(true)
                .build();
        when(approvalGate.checkAndRequestApproval(eq(1L), eq("run-3"), eq("task-3"), eq(tool.getName()),
                eq(tool.getDescription()), anyMap(), eq(true)))
                .thenReturn(null);

        String result = service.executeToolCall(1L, "run-3", "task-3", "agent",
                tool.getName(), Map.of("pid", "pc-1"), List.of(tool), null);

        assertThat(result).contains("approval policy").contains("No data was changed");
        verifyNoInteractions(commandExecutor);
    }

    @Test
    @DisplayName("approved state transition command passes target record id")
    void approvedStateTransitionCommandPassesTargetRecordId() {
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("cmd_pe_submit_procurement_comparison")
                .description("Submit review")
                .toolType("dsl_command")
                .sourceCode("pe:submit_procurement_comparison")
                .riskLevel("L3")
                .requiresApproval(false)
                .build();
        when(commandExecutor.execute(eq("pe:submit_procurement_comparison"), any(CommandExecuteRequest.class)))
                .thenReturn(CommandExecuteResult.builder()
                        .data(Map.of("pid", "PC-1", "pe_pc_status", "review_required"))
                        .build());

        String result = service.executeToolCall(1L, "run-4", "task-4", "agent",
                tool.getName(), Map.of("recordId", "PC-1"), List.of(tool), null);

        org.mockito.ArgumentCaptor<CommandExecuteRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(commandExecutor).execute(eq("pe:submit_procurement_comparison"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getTargetRecordId()).isEqualTo("PC-1");
        assertThat(result).contains("\"success\":true").contains("review_required");
    }
}
