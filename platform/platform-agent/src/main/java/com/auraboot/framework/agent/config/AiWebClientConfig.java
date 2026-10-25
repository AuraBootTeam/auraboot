package com.auraboot.framework.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient bean for AI/LLM provider HTTP calls.
 * Used by AnthropicLlmProvider and OpenAiCompatibleLlmProvider.
 */
@Configuration
public class AiWebClientConfig {

    @Bean("aiWebClient")
    public WebClient aiWebClient() {
        return WebClient.builder()
                .codecs(config -> config.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }
}
