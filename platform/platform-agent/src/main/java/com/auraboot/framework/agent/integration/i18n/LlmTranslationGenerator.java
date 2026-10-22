package com.auraboot.framework.agent.integration.i18n;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.StubLlmProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.i18n.service.TranslationGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;

/** Model protocol and response parsing for translation batches. */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmTranslationGenerator implements TranslationGenerator {
    private final LlmProviderFactory llmProviderFactory;
    private final ObjectMapper objectMapper;
    public boolean isAvailable(Long tenantId) {
        try {
            LlmProviderFactory.ProviderConfig config =
                    llmProviderFactory.resolveConfig(tenantId, null);
            return config != null
                    && config.getApiKey() != null
                    && !config.getApiKey().isBlank()
                    // The explicit no-op provider is not a real translator.
                    // Preserve the documented source-placeholder strategy and
                    // never report its fixed response as paid LLM execution.
                    && !StubLlmProvider.PROVIDER_CODE.equals(config.getProviderCode())
                    && !StubLlmProvider.STUB_API_KEY_SENTINEL.equals(config.getApiKey());
        } catch (Exception e) {
            log.debug("LLM availability check failed: {}", e.getMessage());
            return false;
        }
    }

    public Map<String, String> generate(Long tenantId, List<String> keys,
                                         Map<String, String> sourceValues, String targetLocale) throws Exception {
        // Build the input JSON object for the prompt
        Map<String, String> inputMap = new LinkedHashMap<>();
        for (String key : keys) {
            String value = sourceValues.get(key);
            if (value != null) {
                inputMap.put(key, value);
            }
        }

        String inputJson = objectMapper.writeValueAsString(inputMap);
        String targetLanguageName = resolveLanguageName(targetLocale);

        String userPrompt = String.format(
                "Translate the following UI text values to %s (%s). "
                + "Use professional business software style. "
                + "Return ONLY a JSON object with the same keys and translated values. "
                + "No explanation, no markdown, just raw JSON.\n\n%s",
                targetLanguageName, targetLocale, inputJson);

        // Resolve LLM provider config
        LlmProviderFactory.ProviderConfig config = resolveFirstAvailableConfig(tenantId);
        if (config == null) {
            throw new IllegalStateException("No LLM provider configured");
        }

        String effectiveProviderCode = LlmProviderFactory.effectiveProviderCode(null, config);
        LlmProvider provider = llmProviderFactory.getProvider(effectiveProviderCode);
        LlmChatRequest chatRequest = LlmChatRequest.builder()
                .model(config.getDefaultModel())
                .systemPrompt("You are a professional software localization assistant. "
                        + "Translate UI strings accurately and concisely.")
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user")
                        .content(userPrompt)
                        .build()))
                .maxTokens(2048)
                .build();

        LlmChatResponse response = provider.chat(chatRequest, config.getApiKey(), config.getBaseUrl());

        // Extract text content from response
        String responseText = extractTextContent(response);
        if (responseText == null || responseText.isBlank()) {
            throw new IllegalStateException("LLM returned empty response");
        }

        // Parse JSON — strip potential markdown code fences
        String json = stripMarkdownFences(responseText);
        return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
    }

    private LlmProviderFactory.ProviderConfig resolveFirstAvailableConfig(Long tenantId) {
        return llmProviderFactory.resolveConfig(tenantId, null);
    }

    private String extractTextContent(LlmChatResponse response) {
        if (response == null || response.getContent() == null) return null;
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                return block.getText();
            }
        }
        return null;
    }

    private String stripMarkdownFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
            }
        }
        return trimmed;
    }

    private String resolveLanguageName(String locale) {
        return switch (locale) {
            case "ja-JP" -> "Japanese";
            case "ko-KR" -> "Korean";
            case "en-US" -> "English (US)";
            case "zh-CN" -> "Simplified Chinese";
            case "zh-TW" -> "Traditional Chinese";
            case "fr-FR" -> "French";
            case "de-DE" -> "German";
            case "es-ES" -> "Spanish";
            default -> locale;
        };
    }

}
