package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentPlanStep;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.StubLlmProvider;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlanService")
class PlanServiceTest {

    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private AgentApprovalGateService approvalGate;
    @Mock private LlmProvider provider;

    private PlanService service;

    @BeforeEach
    void setUp() {
        service = new PlanService(dynamicDataMapper, new ObjectMapper(), approvalGate);
    }

    @Test
    @DisplayName("stub-routed config skips LLM planning")
    void stubRoutedConfigSkipsLlmPlanning() {
        LlmProviderFactory.ProviderConfig stubConfig = LlmProviderFactory.ProviderConfig.builder()
                .providerCode(StubLlmProvider.PROVIDER_CODE)
                .apiKey("stub_key_for_no_llm_paths")
                .baseUrl("stub://local")
                .defaultModel("stub-model")
                .maxTokens(4096)
                .build();

        List<AgentPlanStep> plan = service.generatePlan(
                provider,
                stubConfig,
                "gpt-4o",
                "You are Aurabot.",
                "统计客户信息",
                List.of());

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).getDescription()).isEqualTo("Execute task directly");
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("non-stub planning failure propagates instead of direct execution")
    void nonStubPlanningFailurePropagates() throws Exception {
        LlmProviderFactory.ProviderConfig config = providerConfig();
        when(provider.chat(any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("provider unavailable"));

        assertThatThrownBy(() -> service.generatePlan(
                provider,
                config,
                "gpt-4o",
                "You are Aurabot.",
                "统计客户信息",
                List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Plan generation failed")
                .hasRootCauseMessage("provider unavailable");
    }

    @Test
    @DisplayName("non-stub invalid planning response propagates instead of direct execution")
    void nonStubInvalidPlanningResponsePropagates() throws Exception {
        LlmProviderFactory.ProviderConfig config = providerConfig();
        when(provider.chat(any(), anyString(), anyString()))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("end_turn")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("not-json")
                                .build()))
                        .build());

        assertThatThrownBy(() -> service.generatePlan(
                provider,
                config,
                "gpt-4o",
                "You are Aurabot.",
                "统计客户信息",
                List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Plan generation failed")
                .hasMessageContaining("valid JSON plan");
    }

    @Test
    @DisplayName("plan persistence failure propagates instead of being logged only")
    void planPersistenceFailurePropagates() {
        when(dynamicDataMapper.updateWithJsonb(eq("ab_agent_run"), anyMap(), anyMap(), anySet()))
                .thenThrow(new RuntimeException("database unavailable"));

        assertThatThrownBy(() -> service.persistPlan(
                "run-1",
                List.of(new AgentPlanStep(0, "Step 1")),
                0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Plan persistence failed")
                .hasMessageContaining("run-1")
                .hasRootCauseMessage("database unavailable");
    }

    @Test
    @DisplayName("missing persisted plan propagates instead of direct execution")
    void missingPersistedPlanPropagates() {
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of());

        assertThatThrownBy(() -> service.loadPlanFromRun("run-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Plan load failed")
                .hasMessageContaining("run-1");
    }

    @Test
    @DisplayName("malformed persisted plan propagates instead of direct execution")
    void malformedPersistedPlanPropagates() {
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("execution_plan", "not-json")));

        assertThatThrownBy(() -> service.loadPlanFromRun("run-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Plan load failed")
                .hasMessageContaining("run-1");
    }

    private LlmProviderFactory.ProviderConfig providerConfig() {
        return LlmProviderFactory.ProviderConfig.builder()
                .providerCode("openai")
                .apiKey("test-key")
                .baseUrl("https://example.invalid")
                .defaultModel("gpt-4o")
                .maxTokens(4096)
                .build();
    }
}
