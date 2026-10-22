package com.auraboot.framework.agent.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Secret-free summary of one tool schema exposed to an LLM round.
 */
public record AgentToolManifestItem(
        String toolCode,
        String llmName,
        String toolName,
        String toolType,
        String providerCode,
        String sourceCode,
        String riskLevel,
        String confirmationPolicy,
        boolean requiresApproval,
        boolean requiresConfirmation,
        String schemaHash) {

    public Map<String, Object> toSnapshotMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        putIfNotBlank(out, "toolCode", toolCode);
        putIfNotBlank(out, "llmName", llmName);
        putIfNotBlank(out, "toolName", toolName);
        putIfNotBlank(out, "toolType", toolType);
        putIfNotBlank(out, "providerCode", providerCode);
        putIfNotBlank(out, "sourceCode", sourceCode);
        putIfNotBlank(out, "riskLevel", riskLevel);
        putIfNotBlank(out, "confirmationPolicy", confirmationPolicy);
        out.put("requiresApproval", requiresApproval);
        out.put("requiresConfirmation", requiresConfirmation);
        putIfNotBlank(out, "schemaHash", schemaHash);
        return out;
    }

    private static void putIfNotBlank(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.put(key, value);
        }
    }
}
