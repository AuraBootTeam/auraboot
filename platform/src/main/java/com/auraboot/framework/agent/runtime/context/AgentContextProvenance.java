package com.auraboot.framework.agent.runtime.context;

import java.util.List;
import java.util.Map;

/**
 * Provenance labels for one context block inserted into an agent prompt.
 */
public record AgentContextProvenance(
        AgentContextSource source,
        String scope,
        String freshness,
        String permission,
        AgentContextSensitivity sensitivity,
        List<String> recordIds,
        Long tenantId,
        String channel,
        boolean readWriteRelevant,
        Map<String, Object> metadata) {

    public AgentContextProvenance {
        source = source == null ? AgentContextSource.PAGE : source;
        scope = hasText(scope) ? scope : "unknown";
        freshness = hasText(freshness) ? freshness : "UNKNOWN";
        permission = hasText(permission) ? permission : "UNKNOWN";
        sensitivity = sensitivity == null ? AgentContextSensitivity.INTERNAL : sensitivity;
        recordIds = recordIds == null ? List.of() : List.copyOf(recordIds);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    String renderLabel() {
        return "context-provenance"
                + " source=" + source
                + " scope=" + scope
                + " freshness=" + freshness
                + " permission=" + permission
                + " sensitivity=" + sensitivity
                + " tenant=" + (tenantId != null ? tenantId : "")
                + " channel=" + (channel != null ? channel : "")
                + " recordIds=" + recordIds
                + " readWriteRelevant=" + readWriteRelevant
                + " metadata=" + metadata;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
