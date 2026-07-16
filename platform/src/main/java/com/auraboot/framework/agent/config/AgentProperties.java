package com.auraboot.framework.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

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
    private Llm llm = new Llm();

    @Data
    public static class Anthropic {
        private String apiKey;
        private String baseUrl = "https://api.anthropic.com";
        private String defaultModel = "claude-sonnet-4-6";
        private int maxTokens = 4096;
    }

    /**
     * CAP-04 — LLM routing knobs bound under {@code agent.llm.*}.
     *
     * <p>Note: the availability-fallback ({@code agent.llm.provider-fallback}) and
     * stub-mode ({@code agent.llm.stub-mode}) flags are read directly via {@code @Value}
     * in {@link com.auraboot.framework.agent.provider.LlmProviderFactory}; only the
     * model-tier routing map lives here (it needs Spring's relaxed map binding).
     */
    @Data
    public static class Llm {
        /**
         * Config-driven logical-tier → {@code provider:model} routing map.
         *
         * <p>Maps a logical cost/capability tier to a concrete target, e.g.
         * <pre>
         * agent:
         *   llm:
         *     model-routing:
         *       cheap: deepseek:deepseek-chat
         *       smart: anthropic:claude-sonnet-4-6
         * </pre>
         *
         * <p>Key = logical tier (e.g. {@code cheap} / {@code smart}); value =
         * {@code provider:model} (the model part may be omitted to route the
         * provider while keeping its default model). Empty (default) = no
         * routing, so {@code resolveByTier} behaves exactly like the default
         * provider resolution — routing is strictly opt-in.
         */
        private Map<String, String> modelRouting = new LinkedHashMap<>();
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
