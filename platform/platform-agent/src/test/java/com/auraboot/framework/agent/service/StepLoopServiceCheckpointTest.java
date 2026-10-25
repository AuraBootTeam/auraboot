package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.dto.AgentPlanStep;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.metrics.ParallelToolMetrics;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.runtime.DurableWorkflowCheckpointStore;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("StepLoopService durable workflow checkpoints")
class StepLoopServiceCheckpointTest {

    @Test
    @DisplayName("executePlanSteps records checkpoint history after successful plan persistence")
    void executePlanStepsRecordsCheckpointHistoryAfterPlanPersistence() throws Exception {
        DynamicDataMapper dynamicDataMapper = mock(DynamicDataMapper.class);
        DurableWorkflowCheckpointStore checkpointStore = mock(DurableWorkflowCheckpointStore.class);
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of());
        when(dynamicDataMapper.updateWithJsonb(anyString(), anyMap(), anyMap(), anySet())).thenReturn(1);
        StepLoopService service = new StepLoopService(
                mock(ToolLoopService.class),
                dynamicDataMapper,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                mock(LlmProviderFactory.class),
                mock(AiTraceService.class),
                mock(AgentApprovalGateService.class),
                new AgentProperties(),
                Runnable::run,
                mock(ParallelToolMetrics.class),
                checkpointStore);
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("end_turn")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("step complete")
                                .build()))
                        .build());

        service.executePlanSteps(
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
                false);

        verify(checkpointStore).recordPlanCheckpoint(
                eq(1L),
                eq("run-pid"),
                eq(1),
                anyList(),
                eq("step_completed"));
    }

    private LlmProviderFactory.ProviderConfig providerConfig() {
        LlmProviderFactory.ProviderConfig config = new LlmProviderFactory.ProviderConfig();
        config.setProviderCode("anthropic");
        config.setApiKey("test-key");
        config.setBaseUrl("https://example.invalid");
        config.setMaxTokens(1000);
        return config;
    }
}
