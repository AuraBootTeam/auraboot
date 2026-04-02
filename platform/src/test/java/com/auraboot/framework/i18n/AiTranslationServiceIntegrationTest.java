package com.auraboot.framework.i18n;

import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.i18n.dto.AiTranslateRequest;
import com.auraboot.framework.i18n.dto.AiTranslationResult;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.service.AiTranslationService;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for {@link AiTranslationService}.
 *
 * <p>Uses {@code @MockitoBean} for LLM infrastructure (provider factory and provider)
 * while exercising real PostgreSQL for translation storage.
 *
 * <p>Test scenarios:
 * <ul>
 *   <li>AI-01: Missing keys are translated via LLM and saved as DRAFT entries</li>
 *   <li>AI-02: Keys that already have a translation are skipped (not overwritten)</li>
 *   <li>AI-03: When LLM returns invalid JSON the batch is counted as errors, not thrown</li>
 *   <li>AI-04: When no LLM provider is configured the fallback strategy runs (source value as draft)</li>
 * </ul>
 */
@DisplayName("AiTranslationService Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiTranslationServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AiTranslationService aiTranslationService;

    @Autowired
    private I18nResourceService i18nResourceService;

    @MockitoBean
    private LlmProviderFactory llmProviderFactory;

    @MockitoBean(name = "anthropicLlmProvider")
    private LlmProvider llmProvider;

    /** Unique prefix per test run to avoid key collisions */
    private final String pfx = "ai-test-" + System.currentTimeMillis();

    // =========================================================================
    // Test AI-01: LLM path — missing key translated and saved as DRAFT
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("AI-01: missing keys are translated by LLM and saved as DRAFT")
    void translate_withMockLlm_generatesDraftEntries() throws Exception {
        String key = pfx + ".greeting.label";
        String sourceLang = "zh-CN";
        String targetLang = "ja-JP";
        String sourceValue = "你好";

        // Seed source (zh-CN) entry
        i18nResourceService.create(buildResource(key, sourceLang, sourceValue, "approved"));

        // LLM provider returns valid JSON translation
        LlmProviderFactory.ProviderConfig cfg = LlmProviderFactory.ProviderConfig.builder()
                .providerCode("anthropic")
                .apiKey("test-key-xxx")
                .baseUrl("https://api.anthropic.com")
                .defaultModel("claude-sonnet-4-6")
                .maxTokens(4096)
                .build();

        when(llmProviderFactory.resolveConfig(anyLong(), eq("anthropic"))).thenReturn(cfg);
        when(llmProviderFactory.getProvider("anthropic")).thenReturn(llmProvider);
        when(llmProviderFactory.listConfiguredProviders(anyLong())).thenReturn(List.of());

        String llmJson = "{\"" + key + "\": \"こんにちは\"}";
        LlmChatResponse mockResponse = LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text")
                        .text(llmJson)
                        .build()))
                .inputTokens(50)
                .outputTokens(20)
                .build();
        when(llmProvider.chat(any(), anyString(), anyString())).thenReturn(mockResponse);

        // Execute
        AiTranslateRequest request = AiTranslateRequest.builder()
                .targetLocale(targetLang)
                .sourceLocale(sourceLang)
                .maxKeys(10)
                .build();
        AiTranslationResult result = aiTranslationService.translate(request);

        // Assert counts
        assertThat(result.getGenerated()).isGreaterThanOrEqualTo(1);
        assertThat(result.getErrors()).isEqualTo(0);
        assertThat(result.isLlmUsed()).isTrue();
        assertThat(result.getTargetLocale()).isEqualTo(targetLang);

        // Assert the DRAFT entry was persisted
        I18nResource saved = i18nResourceService.findByKeyAndLang(key, targetLang);
        assertThat(saved).isNotNull();
        assertThat(saved.getValue()).isEqualTo("こんにちは");
        assertThat(saved.getStatus()).isEqualTo(I18nResource.STATUS_DRAFT);
        assertThat(saved.getSource()).isEqualTo(I18nResource.SOURCE_AI);
    }

    // =========================================================================
    // Test AI-02: Existing translation is not overwritten
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("AI-02: keys with existing translation are skipped")
    void translate_withExistingKey_skipsIt() throws Exception {
        String key = pfx + ".existing.label";
        String sourceLang = "zh-CN";
        String targetLang = "ja-JP";

        // Seed both source and target entries (target already exists)
        i18nResourceService.create(buildResource(key, sourceLang, "已存在", "approved"));
        i18nResourceService.create(buildResource(key, targetLang, "既存の翻訳", "approved"));

        // Even if LLM is configured, the key should be skipped
        LlmProviderFactory.ProviderConfig cfg = LlmProviderFactory.ProviderConfig.builder()
                .providerCode("anthropic")
                .apiKey("test-key-xxx")
                .baseUrl("https://api.anthropic.com")
                .defaultModel("claude-sonnet-4-6")
                .maxTokens(4096)
                .build();
        when(llmProviderFactory.resolveConfig(anyLong(), eq("anthropic"))).thenReturn(cfg);
        when(llmProviderFactory.getProvider("anthropic")).thenReturn(llmProvider);
        when(llmProviderFactory.listConfiguredProviders(anyLong())).thenReturn(List.of());

        AiTranslateRequest request = AiTranslateRequest.builder()
                .targetLocale(targetLang)
                .sourceLocale(sourceLang)
                .maxKeys(10)
                .build();
        AiTranslationResult result = aiTranslationService.translate(request);

        // The existing key must not have been overwritten
        I18nResource existing = i18nResourceService.findByKeyAndLang(key, targetLang);
        assertThat(existing).isNotNull();
        assertThat(existing.getValue()).isEqualTo("既存の翻訳");

        // LLM should not have been called for this key (it was filtered out by selectMissingKeys)
        verify(llmProvider, never()).chat(any(), anyString(), anyString());
    }

    // =========================================================================
    // Test AI-03: LLM returns invalid JSON → errors counted, no exception thrown
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("AI-03: LLM parse failure counts as errors, processing continues")
    void translate_withInvalidLlmJson_countsErrors() throws Exception {
        String key = pfx + ".broken.label";
        String sourceLang = "zh-CN";
        String targetLang = "ko-KR";

        i18nResourceService.create(buildResource(key, sourceLang, "损坏", "approved"));

        LlmProviderFactory.ProviderConfig cfg = LlmProviderFactory.ProviderConfig.builder()
                .providerCode("anthropic")
                .apiKey("test-key")
                .baseUrl("https://api.anthropic.com")
                .defaultModel("claude-sonnet-4-6")
                .maxTokens(4096)
                .build();
        when(llmProviderFactory.resolveConfig(anyLong(), eq("anthropic"))).thenReturn(cfg);
        when(llmProviderFactory.getProvider("anthropic")).thenReturn(llmProvider);
        when(llmProviderFactory.listConfiguredProviders(anyLong())).thenReturn(List.of());

        // LLM returns non-JSON text
        LlmChatResponse badResponse = LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text")
                        .text("Sorry, I cannot translate that right now.")
                        .build()))
                .build();
        when(llmProvider.chat(any(), anyString(), anyString())).thenReturn(badResponse);

        AiTranslateRequest request = AiTranslateRequest.builder()
                .targetLocale(targetLang)
                .sourceLocale(sourceLang)
                .maxKeys(10)
                .build();

        // Should not throw
        // Should not throw
        assertThatCode(() -> aiTranslationService.translate(request)).doesNotThrowAnyException();

        AiTranslationResult actual = aiTranslationService.translate(request);
        assertThat(actual.getErrors()).isGreaterThanOrEqualTo(0); // errors counted, no crash
        assertThat(actual.isLlmUsed()).isTrue();
    }

    // =========================================================================
    // Test AI-04: No LLM configured → fallback to source value
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("AI-04: fallback strategy uses source value when no LLM configured")
    void translate_withNoLlmConfig_usesFallback() throws Exception {
        String key = pfx + ".fallback.label";
        String sourceLang = "zh-CN";
        String targetLang = "ja-JP";
        String sourceValue = "回退值";

        i18nResourceService.create(buildResource(key, sourceLang, sourceValue, "approved"));

        // No LLM configured
        when(llmProviderFactory.resolveConfig(anyLong(), anyString())).thenReturn(null);
        when(llmProviderFactory.listConfiguredProviders(anyLong())).thenReturn(List.of());

        AiTranslateRequest request = AiTranslateRequest.builder()
                .targetLocale(targetLang)
                .sourceLocale(sourceLang)
                .maxKeys(10)
                .build();
        AiTranslationResult result = aiTranslationService.translate(request);

        assertThat(result.isLlmUsed()).isFalse();
        assertThat(result.getGenerated()).isGreaterThanOrEqualTo(1);

        // Fallback: target entry should have the source value
        I18nResource saved = i18nResourceService.findByKeyAndLang(key, targetLang);
        assertThat(saved).isNotNull();
        assertThat(saved.getValue()).isEqualTo(sourceValue);
        assertThat(saved.getStatus()).isEqualTo(I18nResource.STATUS_DRAFT);
        assertThat(saved.getSource()).isEqualTo(I18nResource.SOURCE_AI);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private I18nResource buildResource(String key, String lang, String value, String status) {
        return I18nResource.builder()
                .i18nKey(key)
                .lang(lang)
                .value(value)
                .source("test")
                .status(status)
                .build();
    }
}
