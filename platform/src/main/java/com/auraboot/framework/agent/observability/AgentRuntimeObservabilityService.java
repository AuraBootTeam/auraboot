package com.auraboot.framework.agent.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * Low-cardinality Micrometer signals for the canonical agent runtime.
 */
@Service
public class AgentRuntimeObservabilityService {

    private final MeterRegistry registry;

    public AgentRuntimeObservabilityService(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordToolDiscovery(String source, boolean queryOnly, int returnedCount) {
        Counter.builder("aurabot.agent.tool.discovery.calls")
                .description("Agent tool discovery calls")
                .tag("source", safeTag(source))
                .tag("query_only", String.valueOf(queryOnly))
                .register(registry)
                .increment();
        DistributionSummary.builder("aurabot.agent.tool.discovery.tools")
                .description("Number of tools returned by agent tool discovery")
                .register(registry)
                .record(Math.max(0, returnedCount));
    }

    public void recordToolExecution(String toolType, boolean success, String stage) {
        Counter.builder("aurabot.agent.tool.execution")
                .description("Agent runtime tool execution outcomes")
                .tag("tool_type", safeTag(toolType))
                .tag("outcome", success ? "success" : "failed")
                .tag("stage", safeTag(stage))
                .register(registry)
                .increment();
    }

    public void recordAuthorizationDecision(String kind, String decision) {
        Counter.builder("aurabot.agent.authorization.decision")
                .description("Agent runtime authorization decisions")
                .tag("kind", safeTag(kind))
                .tag("decision", safeTag(decision))
                .register(registry)
                .increment();
    }

    public void recordResultContract(String outputType, String renderHint, String status, boolean emitted) {
        Counter.builder("aurabot.agent.result_contract")
                .description("Agent runtime result-contract emission outcomes")
                .tag("output_type", safeTag(outputType))
                .tag("render_hint", safeTag(renderHint))
                .tag("status", safeTag(status))
                .tag("emitted", String.valueOf(emitted))
                .register(registry)
                .increment();
    }

    public void recordUnsupportedToolType(String toolType) {
        Counter.builder("aurabot.agent.tool.unsupported_type")
                .description("Discovered tools rejected by ToolLoopService because their type is unsupported")
                .tag("tool_type", safeTag(toolType))
                .register(registry)
                .increment();
    }

    private static String safeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.trim();
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }
}
