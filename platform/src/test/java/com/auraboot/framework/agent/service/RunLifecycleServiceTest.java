package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunLifecycleService")
class RunLifecycleServiceTest {

    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private AgentMemoryService memoryService;
    @Mock private AgentObservationService observationService;
    @Mock private LlmProviderFactory providerFactory;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private LlmProvider provider;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private RunLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new RunLifecycleService(
                dynamicDataMapper,
                new ObjectMapper(),
                memoryService,
                observationService,
                providerFactory,
                jdbcTemplate,
                eventPublisher);
    }

    @Test
    @DisplayName("stub-routed memory extraction skips LLM extraction")
    void stubRoutedMemoryExtractionSkipsLlmExtraction() {
        LlmProviderFactory.ProviderConfig stubConfig = LlmProviderFactory.ProviderConfig.builder()
                .providerCode("stub")
                .apiKey("stub_key_for_no_llm_paths")
                .baseUrl("stub://local")
                .defaultModel("stub-model")
                .maxTokens(4096)
                .build();
        when(providerFactory.resolveConfig(7L, "anthropic")).thenReturn(stubConfig);
        AgentRunService.AgentLoopResult result = new AgentRunService.AgentLoopResult();
        result.lastResponse = "[stub response]";

        boolean extracted = service.extractMemoriesViaLlm(
                7L, "run-1", "aurabot", "统计客户信息", result, "anthropic", "gpt-4o");

        assertThat(extracted).isFalse();
        verify(providerFactory, never()).getProvider(anyString());
        verifyNoInteractions(provider);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    @DisplayName("failed runs always persist a non-blank diagnostic")
    void failedRunsAlwaysPersistDiagnostic(String error) {
        when(dynamicDataMapper.selectByQuery(anyString(), eq(Map.of("pid", "task-1"))))
                .thenReturn(List.of());
        ArgumentCaptor<Map<String, Object>> updateCaptor = ArgumentCaptor.forClass(Map.class);

        service.failRun(7L, "run-1", "task-1", LocalDateTime.now(), error);

        verify(dynamicDataMapper).update(
                eq("ab_agent_run"),
                updateCaptor.capture(),
                eq(Map.of("pid", "run-1")));
        assertThat(updateCaptor.getValue())
                .containsEntry("run_status", "failed")
                .containsEntry("error_message", "Agent execution failed without a diagnostic");
    }
}
