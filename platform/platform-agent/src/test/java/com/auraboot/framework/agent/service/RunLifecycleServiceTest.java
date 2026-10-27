package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.dto.LlmChatResponse;
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
import static org.mockito.ArgumentMatchers.any;
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

    @Mock private AgentRunTerminalStore terminalStore;
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
                eventPublisher, terminalStore);
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
    @ValueSource(strings = {"max_tokens", "end_turn"})
    void incompleteExtractionDoesNotWriteStructuredMemory(String stopReason) throws Exception {
        var result = extractionFixture(stopReason);
        boolean extracted = service.extractMemoriesViaLlm(
                7L, "run-1", "aurabot", "Summarize", result, "deepseek", "test-model");
        assertThat(extracted).isEqualTo("end_turn".equals(stopReason));
        if ("end_turn".equals(stopReason)) {
            ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
            verify(dynamicDataMapper).insert(eq("ab_agent_memory"), data.capture());
            assertThat(data.getValue()).containsEntry("memory_content", "Extracted fact");
        } else {
            verifyNoInteractions(dynamicDataMapper);
        }
        verify(provider).chat(any(), eq("test-key"), eq("https://example.invalid"));
    }

    @Test
    void incompleteExtractionUsesExistingSummaryFallbackOnce() throws Exception {
        var result = extractionFixture("max_tokens");
        service.saveRunMemory(7L, "run-1", "task-1", result, "aurabot", "Summarize",
                "deepseek", "test-model");
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).insert(eq("ab_agent_memory"), data.capture());
        assertThat(data.getValue()).containsEntry("memory_content", "Verified run result")
                .containsEntry("importance", 3);
        verify(memoryService).deduplicateMemories(7L, "aurabot");
        verify(provider).chat(any(), eq("test-key"), eq("https://example.invalid"));
    }

    private AgentRunService.AgentLoopResult extractionFixture(String stopReason) throws Exception {
        when(providerFactory.resolveConfig(7L, "deepseek"))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder().providerCode("deepseek")
                        .apiKey("test-key").baseUrl("https://example.invalid").build());
        when(providerFactory.getProvider("deepseek")).thenReturn(provider);
        when(provider.chat(any(), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(LlmChatResponse.builder().stopReason(stopReason).content(List.of(
                        LlmChatResponse.ContentBlock.builder().type("text")
                                .text("[{\"type\":\"FACT\",\"content\":\"Extracted fact\",\"importance\":5}]")
                                .build())).build());
        var result = new AgentRunService.AgentLoopResult();
        result.lastResponse = "Verified run result";
        return result;
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    @DisplayName("failed runs always persist a non-blank diagnostic")
    void failedRunsAlwaysPersistDiagnostic(String error) {
        ArgumentCaptor<Map<String, Object>> updateCaptor = ArgumentCaptor.forClass(Map.class);

        service.failRun(7L, "run-1", "task-1", LocalDateTime.now(), error);

        verify(terminalStore).complete(eq(7L), eq("run-1"), eq("task-1"),
                updateCaptor.capture(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any());
        assertThat(updateCaptor.getValue())
                .containsEntry("run_status", "failed")
                .containsEntry("error_message", "Agent execution failed without a diagnostic");
    }
}
