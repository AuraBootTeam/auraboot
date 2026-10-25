package com.auraboot.framework.agent.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Secret-free manifest of the context window sent to an LLM round.
 */
public record AgentContextManifest(
        int maxTokens,
        int systemPromptChars,
        int systemPromptTokens,
        String systemPromptHash,
        int messageCount,
        int messageChars,
        int messageTokens,
        String messagesHash,
        int toolCount,
        int toolTokens,
        String toolsHash,
        String contextHash) {

    public Map<String, Object> toSnapshotMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxTokens", maxTokens);
        out.put("systemPromptChars", systemPromptChars);
        out.put("systemPromptTokens", systemPromptTokens);
        putIfNotBlank(out, "systemPromptHash", systemPromptHash);
        out.put("messageCount", messageCount);
        out.put("messageChars", messageChars);
        out.put("messageTokens", messageTokens);
        putIfNotBlank(out, "messagesHash", messagesHash);
        out.put("toolCount", toolCount);
        out.put("toolTokens", toolTokens);
        putIfNotBlank(out, "toolsHash", toolsHash);
        putIfNotBlank(out, "contextHash", contextHash);
        return out;
    }

    private static void putIfNotBlank(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.put(key, value);
        }
    }
}
