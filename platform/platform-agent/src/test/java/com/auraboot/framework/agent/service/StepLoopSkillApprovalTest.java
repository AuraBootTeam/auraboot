package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.metrics.ParallelToolMetrics;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.runtime.DurableWorkflowCheckpointStore;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StepLoopSkillApprovalTest {
    @Test void skillPreviewCreatesDurableApprovalWithoutExecutingTheSkill() {
        var tools = mock(ToolLoopService.class);
        var gate = mock(AgentApprovalGateService.class);
        var json = new ObjectMapper();
        var service = new StepLoopService(tools, mock(DynamicDataMapper.class), json,
                mock(LlmProviderFactory.class), mock(AiTraceService.class), gate,
                new AgentProperties(), Runnable::run, mock(ParallelToolMetrics.class),
                mock(DurableWorkflowCheckpointStore.class));
        var input = Map.<String, Object>of("code", "sample", "displayName", "审批台账");
        var def = AgentToolDefinition.builder().name("aurabot__model_create")
                .toolType("AURABOT_SKILL").sourceCode("model:create").build();
        var block = LlmChatResponse.ContentBlock.builder().type("tool_use").id("call-1")
                .name(def.getName()).input(input).build();
        when(tools.executeToolCall(eq(7L), eq("run-1"), eq("task-1"), eq("aurabot"),
                eq(def.getName()), eq(input), anyList(), isNull()))
                .thenReturn("{\"success\":false,\"approvalRequired\":true,\"skillName\":\"model:create\",\"riskLevel\":\"HIGH\",\"previewToken\":\"token-1\",\"preview\":{\"code\":\"sample\"}}");
        when(gate.checkAndRequestApproval(eq(7L), eq("run-1"), eq("task-1"), eq(def.getName()),
                anyString(), eq(input), eq(true))).thenReturn("approval-1");
        var pending = assertThrows(AgentApprovalPendingException.class, () -> service.processToolUseBlocksParallel(
                List.of(block), List.of(def), 7L, "run-1", "task-1", "aurabot", null));
        assertThat(pending.getApprovalPid()).isEqualTo("approval-1");
        assertThat(pending.getInput()).isEqualTo(input);
        verify(tools, never()).confirmAuraBotSkill(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
    @Test void absentApprovalPolicyFailsClosed() {
        var tools = mock(ToolLoopService.class); var gate = mock(AgentApprovalGateService.class);
        when(tools.executeToolCall(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(preview());
        when(gate.checkAndRequestApproval(any(), any(), any(), any(), any(), any(), eq(true)))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("No matching policy"));
        var service = service(tools, gate);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.processToolUseBlocksParallel(List.of(block("call-1", "skill")), List.of(skill()),
                        7L, "run-1", "task-1", "aurabot", null));
        verify(tools, never()).confirmAuraBotSkill(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test void skillPreviewStopsMixedBatchBeforeOtherEffects() {
        var tools = mock(ToolLoopService.class); var gate = mock(AgentApprovalGateService.class);
        when(tools.executeToolCall(any(), any(), any(), any(), eq("skill"), any(), any(), any())).thenReturn(preview());
        when(gate.checkAndRequestApproval(any(), any(), any(), any(), any(), any(), eq(true))).thenReturn("approval-1");
        var other = AgentToolDefinition.builder().name("other").toolType("provider").build();
        assertThrows(AgentApprovalPendingException.class, () -> service(tools, gate).processToolUseBlocksParallel(
                List.of(block("call-other", "other"), block("call-skill", "skill")), List.of(other, skill()),
                7L, "run-1", "task-1", "aurabot", null));
        verify(tools, never()).executeToolCall(any(), any(), any(), any(), eq("other"), any(), any(), any());
    }

    @Test void directLoopSavesPreviewCheckpointAndResumeConfirmsExactSavedInput() throws Exception {
        var tools = mock(ToolLoopService.class); var gate = mock(AgentApprovalGateService.class);
        var mapper = mock(DynamicDataMapper.class);
        when(mapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of());
        when(mapper.updateWithJsonb(anyString(), anyMap(), anyMap(), anySet())).thenReturn(1);
        var service = new StepLoopService(tools, mapper, new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()), mock(LlmProviderFactory.class),
                mock(AiTraceService.class), gate, new AgentProperties(), Runnable::run,
                mock(ParallelToolMetrics.class), mock(DurableWorkflowCheckpointStore.class));
        when(tools.executeToolCall(any(), any(), any(), any(), eq("skill"), any(), any(), any())).thenReturn(preview());
        when(gate.checkAndRequestApproval(any(), any(), any(), any(), any(), any(), eq(true))).thenReturn("approval-1");
        var provider = mock(com.auraboot.framework.agent.provider.LlmProvider.class);
        when(provider.chat(any(), any(), any())).thenReturn(LlmChatResponse.builder().stopReason("tool_use")
                .content(List.of(block("call-skill", "skill"))).build());
        var config = LlmProviderFactory.ProviderConfig.builder().defaultModel("test-model").apiKey("test")
                .baseUrl("https://example.invalid").build();
        var step = new com.auraboot.framework.agent.dto.AgentPlanStep(0, "Execute task directly");
        var plan = new java.util.ArrayList<>(List.of(step));
        assertThrows(AgentApprovalPendingException.class, () -> service.executePlanSteps(plan, 0, 7L,
                "run-1", "task-1", "aurabot", "system", "user", List.of(skill()), Map.of(), provider, config, null, false));
        assertThat(step.getStatus()).isEqualTo(com.auraboot.framework.agent.dto.AgentPlanStep.StepStatus.AWAITING_APPROVAL);
        assertThat(step.getOutput()).containsEntry("approvalPreviewToken", "token-1");
        var original = Map.<String,Object>of("code", "sample");
        assertThat(step.getOutput()).containsEntry("approvalInput", original);
        when(gate.consumeResumeGrant(7L, "task-1", "approval-1", "skill", original)).thenReturn(true);
        when(tools.confirmAuraBotSkill(eq(7L), eq("run-2"), eq("task-1"), eq("aurabot"),
                eq("skill"), eq(original), anyList(), eq("token-1"), isNull())).thenReturn("{\"success\":true}");
        clearInvocations(provider, tools);
        service.executePlanSteps(plan, 0, 7L, "run-2", "task-1", "aurabot", "system", "user",
                List.of(skill()), Map.of(), provider, config, null, true);
        assertThat(step.getStatus()).isEqualTo(com.auraboot.framework.agent.dto.AgentPlanStep.StepStatus.COMPLETED);
        verifyNoInteractions(provider);
        verify(tools, never()).executeToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private StepLoopService service(ToolLoopService tools, AgentApprovalGateService gate) {
        return new StepLoopService(tools, mock(DynamicDataMapper.class), new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()), mock(LlmProviderFactory.class),
                mock(AiTraceService.class), gate, new AgentProperties(), Runnable::run,
                mock(ParallelToolMetrics.class), mock(DurableWorkflowCheckpointStore.class));
    }
    private AgentToolDefinition skill() { return AgentToolDefinition.builder().name("skill").toolType("AURABOT_SKILL").build(); }
    private LlmChatResponse.ContentBlock block(String id, String name) {
        return LlmChatResponse.ContentBlock.builder().type("tool_use").id(id).name(name).input(Map.of("code", "sample")).build();
    }
    private String preview() {
        return "{\"success\":false,\"approvalRequired\":true,\"skillName\":\"model:create\",\"riskLevel\":\"HIGH\",\"previewToken\":\"token-1\"}";
    }
}
