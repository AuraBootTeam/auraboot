package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.i18n.service.I18nService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that the {@code $i18n:aurabot.error.no_llm_provider} sentinel emitted by
 * {@link AuraBotChatService} (streamed to the chat panel when a tenant has no LLM
 * provider configured) resolves to a real catalog value per locale. Without a
 * matching catalog entry the frontend would render the raw sentinel — the exact
 * regression this guards against.
 *
 * <p>{@link I18nService} reads the yaml catalog straight off the classpath, so no
 * Spring context / DB is required.
 */
class AuraBotErrorMessageI18nTest {

    /** Must stay identical to the sentinel emitted in {@link AuraBotChatService}. */
    private static final String KEY = "aurabot.error.no_llm_provider";

    private final I18nService i18nService = new I18nService();

    @Test
    @DisplayName("aurabot.error.no_llm_provider resolves in the base locales")
    void resolvesPerLocale() {
        assertThat(i18nService.getValue("zh-CN", KEY))
                .isEqualTo("未配置 LLM 服务商，请在云配置中添加 API Key。");
        assertThat(i18nService.getValue("en-US", KEY))
                .isEqualTo("No LLM provider configured. Please configure an API key in Cloud Config.");
    }
}
