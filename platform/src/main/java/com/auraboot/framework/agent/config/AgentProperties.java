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
    private Parallel parallel = new Parallel();

    @Data
    public static class Anthropic {
        private String apiKey;
        private String baseUrl = "https://api.anthropic.com";
        private String defaultModel = "claude-sonnet-4-6";
        private int maxTokens = 4096;
    }

    /**
     * ACP P0-5 Parallel Tool Calls knobs.
     *
     * <p>{@code enabled}: kill switch — set false to fall back to fully serial
     * execution without code changes.
     *
     * <p>{@code maxFanout}: when an LLM emits more than this many tool_use
     * blocks in a single turn, the batch is rejected and the failure is
     * reported back to the LLM ("fanout exceeded, retry with fewer tools")
     * rather than silently degraded to serial. This protects DB connection
     * pool / executor from runaway agents.
     *
     * <p>{@code totalTimeoutMs}: upper bound on wall time for the whole batch.
     * Per-tool timeouts come from each ToolDefinition (default 60s).
     */
    @Data
    public static class Parallel {
        private boolean enabled = true;
        private int maxFanout = 5;
        private long totalTimeoutMs = 90_000;
    }
}
