package com.auraboot.framework.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private boolean enabled = true;
    private int maxConcurrentRuns = 5;
    private double defaultCostLimit = 1.0;
    private int memoryMaxChars = 2000;
    private int memoryMaxItems = 20;

    private Anthropic anthropic = new Anthropic();

    @Data
    public static class Anthropic {
        private String apiKey;
        private String baseUrl = "https://api.anthropic.com";
        private String defaultModel = "claude-sonnet-4-6";
        private int maxTokens = 4096;
    }
}
