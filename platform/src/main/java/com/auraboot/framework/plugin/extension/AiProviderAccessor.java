package com.auraboot.framework.plugin.extension;

import java.util.List;
import java.util.Map;

/**
 * Plugin-safe facade for platform-managed LLM providers.
 *
 * <p>Plugins should use this accessor instead of depending on Spring beans such
 * as LlmProviderFactory directly. The platform remains responsible for tenant
 * resolution, CloudConfig/llm_config lookup, secret handling, and provider
 * protocol adaptation.
 */
public interface AiProviderAccessor {

    String SETTINGS_KEY = "__aiProviderAccessor";

    ChatResponse chat(ChatRequest request) throws Exception;

    record ChatRequest(
            String useCase,
            String providerProfileCode,
            String providerCode,
            String modelName,
            String systemPrompt,
            List<Message> messages,
            int maxTokens,
            Map<String, Object> metadata
    ) {
    }

    record Message(String role, String content) {
        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }

    record ChatResponse(
            String providerCode,
            String modelName,
            String text,
            int inputTokens,
            int outputTokens,
            int totalTokens,
            String rawResponseJson
    ) {
    }
}
