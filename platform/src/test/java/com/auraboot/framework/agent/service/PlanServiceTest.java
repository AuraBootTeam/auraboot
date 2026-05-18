package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentPlanStep;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

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
}
