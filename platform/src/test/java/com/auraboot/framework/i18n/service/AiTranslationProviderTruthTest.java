package com.auraboot.framework.i18n.service;

import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.StubLlmProvider;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.i18n.dto.AiTranslateRequest;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.mapper.I18nResourceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic service contracts; database persistence and UI remain separate. */
class AiTranslationProviderTruthTest {
    private final I18nResourceMapper mapper = mock(I18nResourceMapper.class);
    private final LlmProviderFactory factory = mock(LlmProviderFactory.class);
    private final AiTranslationService service = new AiTranslationService(mapper, factory, new ObjectMapper());

    @BeforeEach
    void setUp() {
        MetaContext.setContext(100L, 1L, "translation-test", "translation-test");
        when(mapper.selectMissingKeys(100L, "zh-CN", "ja-JP", 1)).thenReturn(List.of("test.translation"));
        when(mapper.selectByKeyAndLang(100L, "test.translation", "zh-CN"))
                .thenReturn(I18nResource.builder().value("Source wording").build());
    }

    @AfterEach
    void clearContext() { MetaContext.clear(); }

    @Test
    void stubProviderProducesPlaceholderDraftWithoutClaimingRealLlm() {
        assertPlaceholder(StubLlmProvider.PROVIDER_CODE, "nonempty-test-key");
    }

    @Test
    void sentinelCredentialProducesPlaceholderEvenWithOrdinaryProviderCode() {
        assertPlaceholder("anthropic", StubLlmProvider.STUB_API_KEY_SENTINEL);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertPlaceholder(String providerCode, String apiKey) {
        when(factory.resolveConfig(100L, null)).thenReturn(LlmProviderFactory.ProviderConfig.builder()
                .providerCode(providerCode).apiKey(apiKey).build());
        when(mapper.batchInsertIgnore(anyList())).thenReturn(1);
        var result = service.translate(request());
        assertThat(result.isLlmUsed()).isFalse();
        assertThat(result.getGenerated()).isEqualTo(1);
        assertThat(result.getErrors()).isZero();
        assertThat(result.getSkipped()).isZero();
        ArgumentCaptor<List<I18nResource>> inserted = ArgumentCaptor.forClass((Class) List.class);
        verify(mapper).batchInsertIgnore(inserted.capture());
        assertThat(inserted.getValue()).singleElement().satisfies(resource -> {
            assertThat(resource.getTenantId()).isEqualTo(100L);
            assertThat(resource.getI18nKey()).isEqualTo("test.translation");
            assertThat(resource.getValue()).isEqualTo("Source wording");
            assertThat(resource.getLang()).isEqualTo("ja-JP");
            assertThat(resource.getStatus()).isEqualTo("draft");
            assertThat(resource.getSource()).isEqualTo("ai");
        });
        verify(factory, never()).getProvider(any());
    }

    @Test
    void realProviderParseFailureRemainsAnErrorWithoutPlaceholderWrites() throws Exception {
        when(factory.resolveConfig(100L, null)).thenReturn(LlmProviderFactory.ProviderConfig.builder()
                .providerCode("real-test-provider").apiKey("test-key").build());
        LlmProvider provider = mock(LlmProvider.class);
        when(factory.getProvider("real-test-provider")).thenReturn(provider);
        when(provider.chat(any(), any(), any())).thenReturn(LlmChatResponse.builder().content(List.of(
                LlmChatResponse.ContentBlock.builder().type("text").text("invalid JSON").build())).build());
        var result = service.translate(request());
        assertThat(result.isLlmUsed()).isTrue();
        assertThat(result.getErrors()).isEqualTo(1);
        assertThat(result.getGenerated()).isZero();
        verify(mapper, never()).batchInsertIgnore(anyList());
        verify(provider).chat(any(), any(), any());
    }

    private AiTranslateRequest request() {
        return AiTranslateRequest.builder().sourceLocale("zh-CN").targetLocale("ja-JP").maxKeys(1).build();
    }
}
