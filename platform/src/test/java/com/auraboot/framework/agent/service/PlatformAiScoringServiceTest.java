package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.PlatformAiScoreRequest;
import com.auraboot.framework.agent.dto.PlatformAiScoreResult;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PlatformAiScoringServiceImpl.
 * Uses Mockito to isolate LLM and DB dependencies — no Spring context needed.
 */
@ExtendWith(MockitoExtension.class)
class PlatformAiScoringServiceTest {

    @Mock
    private LlmProviderFactory llmProviderFactory;

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private DynamicDataMapper dynamicDataMapper;

    @Mock
    private LlmProvider llmProvider;

    private PlatformAiScoringServiceImpl service;

    private static final Long TENANT_ID = 1001L;
    private static final String MODEL_CODE = "test_model";
    private static final String TABLE_NAME = "mt_test_model";
    private static final String SCORE_FIELD = "ai_score";

    @BeforeEach
    void setUp() {
        service = new PlatformAiScoringServiceImpl(
                llmProviderFactory, metaModelService, dynamicDataMapper, new ObjectMapper());
    }

    private LlmProviderFactory.ProviderConfig buildConfig() {
        return LlmProviderFactory.ProviderConfig.builder()
                .providerCode("openai")
                .apiKey("test-key")
                .baseUrl("https://api.openai.com")
                .defaultModel("gpt-4o")
                .maxTokens(4096)
                .build();
    }

    private PlatformAiScoreRequest buildRequest() {
        PlatformAiScoreRequest req = new PlatformAiScoreRequest();
        req.setModelCode(MODEL_CODE);
        req.setScoreField(SCORE_FIELD);
        req.setContextFields(List.of("name", "status"));

        PlatformAiScoreRequest.ScoringDimension dim = new PlatformAiScoreRequest.ScoringDimension();
        dim.setFieldCode("name");
        dim.setDescription("Evaluate name completeness");
        dim.setWeight(50);
        req.setScoringDimensions(List.of(dim));

        req.setBatchSize(10);
        req.setLimit(200);
        return req;
    }

    private LlmChatResponse buildLlmResponse(String json) {
        LlmChatResponse.ContentBlock block = LlmChatResponse.ContentBlock.builder()
                .type("text")
                .text(json)
                .build();
        return LlmChatResponse.builder()
                .content(List.of(block))
                .inputTokens(100)
                .outputTokens(50)
                .stopReason("end_turn")
                .build();
    }

    // =========================================================================
    // Happy path: LLM returns scores, DynamicDataMapper.update is called
    // =========================================================================

    @Test
    void score_shouldCallLlmAndWriteScoresBack() throws Exception {
        // Arrange
        LlmProviderFactory.ProviderConfig config = buildConfig();
        when(llmProviderFactory.resolveConfig(TENANT_ID, null)).thenReturn(config);
        when(llmProviderFactory.getProvider("openai")).thenReturn(llmProvider);
        when(metaModelService.getTableName(MODEL_CODE)).thenReturn(TABLE_NAME);

        Map<String, Object> record = new java.util.HashMap<>();
        record.put("pid", "pid001");
        record.put("name", "Acme Corp");
        record.put("status", "active");
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                .thenReturn(List.of(record));

        when(llmProvider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenReturn(buildLlmResponse("[{\"id\":\"pid001\",\"score\":85}]"));

        // Act
        PlatformAiScoreResult result = service.score(buildRequest(), TENANT_ID);

        // Assert
        assertThat(result.getScoredCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(0);
        assertThat(result.getScores()).containsEntry("pid001", 85);
        assertThat(result.getTotalInputTokens()).isEqualTo(100);
        assertThat(result.getTotalOutputTokens()).isEqualTo(50);
        assertThat(result.getModelCode()).isEqualTo(MODEL_CODE);
        assertThat(result.getScoreField()).isEqualTo(SCORE_FIELD);

        // Verify update was called with correct args
        ArgumentCaptor<Map> updateDataCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map> conditionsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).update(eq(TABLE_NAME), updateDataCaptor.capture(), conditionsCaptor.capture());

        assertThat(updateDataCaptor.getValue()).containsEntry(SCORE_FIELD, 85);
        assertThat(conditionsCaptor.getValue()).containsEntry("pid", "pid001");
        assertThat(conditionsCaptor.getValue()).containsEntry("tenant_id", TENANT_ID);
    }

    // =========================================================================
    // No LLM provider configured → IllegalStateException
    // =========================================================================

    @Test
    void score_shouldThrow_whenNoLlmProvider() {
        when(llmProviderFactory.resolveConfig(TENANT_ID, null)).thenReturn(null);

        assertThatThrownBy(() -> service.score(buildRequest(), TENANT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No LLM provider configured");

        verify(dynamicDataMapper, never()).selectByQuery(anyString(), anyMap());
    }

    // =========================================================================
    // Model not found → IllegalArgumentException
    // =========================================================================

    @Test
    void score_shouldThrow_whenModelNotFound() {
        LlmProviderFactory.ProviderConfig config = buildConfig();
        when(llmProviderFactory.resolveConfig(TENANT_ID, null)).thenReturn(config);
        when(llmProviderFactory.getProvider("openai")).thenReturn(llmProvider);
        when(metaModelService.getTableName(MODEL_CODE)).thenReturn(null);

        assertThatThrownBy(() -> service.score(buildRequest(), TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Model not found");

        verify(dynamicDataMapper, never()).selectByQuery(anyString(), anyMap());
    }

    // =========================================================================
    // Empty records → score 0, LLM never called
    // =========================================================================

    @Test
    void score_shouldHandleEmptyRecords() throws Exception {
        LlmProviderFactory.ProviderConfig config = buildConfig();
        when(llmProviderFactory.resolveConfig(TENANT_ID, null)).thenReturn(config);
        when(llmProviderFactory.getProvider("openai")).thenReturn(llmProvider);
        when(metaModelService.getTableName(MODEL_CODE)).thenReturn(TABLE_NAME);
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of());

        PlatformAiScoreResult result = service.score(buildRequest(), TENANT_ID);

        assertThat(result.getScoredCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(0);
        assertThat(result.getScores()).isEmpty();
        assertThat(result.getTotalInputTokens()).isEqualTo(0);
        assertThat(result.getTotalOutputTokens()).isEqualTo(0);

        // LLM must not be called when there are no records
        verify(llmProvider, never()).chat(any(), anyString(), anyString());
        verify(dynamicDataMapper, never()).update(anyString(), anyMap(), anyMap());
    }
}
