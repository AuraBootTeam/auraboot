package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.dto.AgentPlanStep;
import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.metrics.ParallelToolMetrics;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.runtime.DurableWorkflowCheckpointStore;
import com.auraboot.framework.agent.runtime.LlmResponseGuard;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("StepLoopService LLM response guard")
class StepLoopServiceLlmResponseGuardTest {

    @Test
    @DisplayName("executeAgentLoop fails clearly when provider returns null")
    void executeAgentLoop_providerReturnsNull_throwsEmptyLlmResponse() throws Exception {
        StepLoopService service = newService();
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.chat(any(LlmChatRequest.class), anyString(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.executeAgentLoop(
                1L,
                "run-pid",
                "task-pid",
                "aurabot",
                "system",
                "user",
                List.of(),
                Map.of("model", "claude-sonnet-4-6"),
                provider,
                providerConfig(),
                null))
                .isInstanceOf(LlmResponseGuard.EmptyLlmResponseException.class)
                .hasMessage("Empty response from LLM during ACP agent loop");
    }

    @Test
    @DisplayName("attemptReplan treats empty provider response as no replan")
    void attemptReplan_providerReturnsEmptyContent_returnsFalse() throws Exception {
        StepLoopService service = newService();
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("end_turn")
                        .content(List.of())
                        .build());

        List<AgentPlanStep> plan = new java.util.ArrayList<>();
        plan.add(new AgentPlanStep(0, "failed step"));
        plan.add(new AgentPlanStep(1, "remaining step"));

        assertThat(service.attemptReplan(
                plan,
                0,
                "failure",
                provider,
                providerConfig(),
                "claude-sonnet-4-6",
                "system",
                List.of(),
                List.of(),
                null))
                .isFalse();
    }

    @Test
    @DisplayName("executePlanSteps propagates plan persistence failures")
    void executePlanSteps_planPersistenceFailure_propagates() throws Exception {
        DynamicDataMapper dynamicDataMapper = mock(DynamicDataMapper.class);
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of());
        when(dynamicDataMapper.updateWithJsonb(anyString(), anyMap(), anyMap(), anySet()))
                .thenThrow(new RuntimeException("database unavailable"));
        StepLoopService service = newService(dynamicDataMapper);
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("end_turn")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("step complete")
                                .build()))
                        .build());

        assertThatThrownBy(() -> service.executePlanSteps(
                new java.util.ArrayList<>(List.of(new AgentPlanStep(0, "Run one step"))),
                0,
                1L,
                "run-pid",
                "task-pid",
                "aurabot",
                "system",
                "user",
                List.of(),
                Map.of("model", "claude-sonnet-4-6"),
                Map.of(),
                provider,
                providerConfig(),
                null,
                false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Plan persistence failed")
                .hasMessageContaining("run-pid")
                .hasRootCauseMessage("database unavailable");
    }

    @Test
    @DisplayName("executePlanSteps clears StepContext when approval pauses the run")
    void executePlanSteps_approvalPending_clearsStepContext() {
        AgentApprovalGateService approvalGate = mock(AgentApprovalGateService.class);
        when(approvalGate.checkAndRequestApproval(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn("approval-pid");
        StepLoopService service = newService(persistentMapper(), approvalGate, mock(ToolLoopService.class));

        AgentPlanStep step = new AgentPlanStep(0, "Needs approval");
        step.setRequiresApproval(true);
        step.setToolCode("dangerous_tool");

        assertThatThrownBy(() -> service.executePlanSteps(
                new java.util.ArrayList<>(List.of(step)),
                0,
                1L,
                "run-pid",
                "task-pid",
                "aurabot",
                "system",
                "user",
                List.of(),
                Map.of("model", "claude-sonnet-4-6"),
                Map.of(),
                mock(LlmProvider.class),
                providerConfig(),
                null,
                false))
                .isInstanceOf(AgentApprovalPendingException.class);

        assertThat(StepContext.getStepIndex())
                .as("StepContext must not leak step index onto the executor thread after suspension")
                .isNull();
    }

    @Test
    @DisplayName("executePlanSteps fails when a step exhausts tool-use rounds without final answer")
    void executePlanSteps_toolUseRoundsExhausted_failsStepInsteadOfCompleting() throws Exception {
        ToolLoopService toolLoopService = mock(ToolLoopService.class);
        when(toolLoopService.executeToolCall(any(), anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any()))
                .thenReturn("{\"success\":true}");
        StepLoopService service = newService(persistentMapper(), mock(AgentApprovalGateService.class), toolLoopService);

        LlmProvider provider = mock(LlmProvider.class);
        when(provider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenReturn(toolUseResponse("tool-use-1", "lookup_customer"));

        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("lookup_customer")
                .description("Lookup customer")
                .inputSchema(Map.of("type", "object"))
                .build();
        AgentPlanStep step = new AgentPlanStep(0, "Lookup the customer");

        assertThatThrownBy(() -> service.executePlanSteps(
                new java.util.ArrayList<>(List.of(step)),
                0,
                1L,
                "run-pid",
                "task-pid",
                "aurabot",
                "system",
                "user",
                List.of(tool),
                Map.of("model", "claude-sonnet-4-6"),
                Map.of(),
                provider,
                providerConfig(),
                null,
                false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tool loop exceeded maximum rounds for plan step 0");

        assertThat(step.getStatus()).isEqualTo(AgentPlanStep.StepStatus.FAILED);
        assertThat(StepContext.getStepIndex()).isNull();
    }

    private StepLoopService newService() {
        DynamicDataMapper dynamicDataMapper = mock(DynamicDataMapper.class);
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of());
        return newService(dynamicDataMapper);
    }

    private StepLoopService newService(DynamicDataMapper dynamicDataMapper) {
        return newService(dynamicDataMapper, mock(AgentApprovalGateService.class), mock(ToolLoopService.class));
    }

    private StepLoopService newService(DynamicDataMapper dynamicDataMapper,
                                       AgentApprovalGateService approvalGate,
                                       ToolLoopService toolLoopService) {
        return new StepLoopService(
                toolLoopService,
                dynamicDataMapper,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                mock(LlmProviderFactory.class),
                mock(AiTraceService.class),
                approvalGate,
                new AgentProperties(),
                Runnable::run,
                mock(ParallelToolMetrics.class),
                mock(DurableWorkflowCheckpointStore.class));
    }

    private DynamicDataMapper persistentMapper() {
        DynamicDataMapper dynamicDataMapper = mock(DynamicDataMapper.class);
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of());
        when(dynamicDataMapper.updateWithJsonb(anyString(), anyMap(), anyMap(), anySet())).thenReturn(1);
        return dynamicDataMapper;
    }

    private LlmChatResponse toolUseResponse(String id, String toolName) {
        return LlmChatResponse.builder()
                .stopReason("tool_use")
                .content(List.of(
                        LlmChatResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id(id)
                                .name(toolName)
                                .input(Map.of("id", "cust-1"))
                                .build()))
                .build();
    }

    private LlmProviderFactory.ProviderConfig providerConfig() {
        return LlmProviderFactory.ProviderConfig.builder()
                .providerCode("anthropic")
                .apiKey("sk-test")
                .baseUrl("https://api.anthropic.com")
                .defaultModel("claude-sonnet-4-6")
                .maxTokens(4096)
                .build();
    }
}
