package com.auraboot.framework.decision.rule;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable contract for a module consuming a published decision.
 */
public record DecisionBinding(
        String decisionCode,
        DecisionVersionPolicy versionPolicy,
        Integer versionNo,
        String versionTag,
        Instant asOf,
        List<InputMapping> inputMappings,
        List<OutputMapping> outputMappings,
        FallbackPolicy fallbackPolicy,
        Integer timeoutMs,
        TraceMode traceMode,
        Boolean enabled,
        RuleValueSource routingKeySource,
        RuleValueSource tenantSegmentSource
) {
    public DecisionBinding {
        versionPolicy = versionPolicy == null ? DecisionVersionPolicy.LATEST_PUBLISHED : versionPolicy;
        inputMappings = inputMappings == null ? List.of() : List.copyOf(inputMappings);
        outputMappings = outputMappings == null ? List.of() : List.copyOf(outputMappings);
        fallbackPolicy = fallbackPolicy == null ? FallbackPolicy.failClosed() : fallbackPolicy;
        traceMode = traceMode == null ? TraceMode.SAMPLED : traceMode;
        timeoutMs = timeoutMs == null ? 200 : Math.max(1, timeoutMs);
    }

    public boolean active() {
        return enabled == null || enabled;
    }

    public record InputMapping(String input, RuleValueSource source) {}

    public record OutputMapping(String output, RuleMappingTarget target) {}

    public enum TraceMode {
        NONE,
        SAMPLED,
        ALWAYS
    }

    public enum FallbackMode {
        FAIL_CLOSED,
        FAIL_OPEN,
        DEFAULT_VALUE
    }

    public record FallbackPolicy(
            FallbackMode mode,
            Map<String, Object> defaultOutputs,
            String reason
    ) {
        public FallbackPolicy {
            mode = mode == null ? FallbackMode.FAIL_CLOSED : mode;
            defaultOutputs = defaultOutputs == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(defaultOutputs));
        }

        public static FallbackPolicy failClosed() {
            return new FallbackPolicy(FallbackMode.FAIL_CLOSED, Map.of(), "Decision evaluation failed");
        }

        public static FallbackPolicy failOpen() {
            return new FallbackPolicy(FallbackMode.FAIL_OPEN, Map.of(), "Decision evaluation failed");
        }

        public static FallbackPolicy defaultValue(Map<String, Object> outputs) {
            return new FallbackPolicy(FallbackMode.DEFAULT_VALUE, outputs, "Decision evaluation failed");
        }
    }
}
