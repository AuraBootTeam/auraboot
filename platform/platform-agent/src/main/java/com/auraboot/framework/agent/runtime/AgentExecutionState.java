package com.auraboot.framework.agent.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Secret-free runtime state snapshot for one agent execution round.
 */
public record AgentExecutionState(
        String schemaVersion,
        String executionKind,
        String turnId,
        String runPid,
        String taskPid,
        Long tenantId,
        Long userId,
        String agentCode,
        String sessionId,
        String providerCode,
        String model,
        int round,
        String toolChoice,
        AgentContextManifest context,
        List<AgentToolManifestItem> tools,
        Map<String, Object> pending,
        String stateHash) {

    public static final String SCHEMA_VERSION = "agent-runtime-state/v1";

    public AgentExecutionState {
        schemaVersion = isBlank(schemaVersion) ? SCHEMA_VERSION : schemaVersion;
        executionKind = isBlank(executionKind) ? "chat_turn" : executionKind;
        tools = tools == null ? List.of() : List.copyOf(tools);
        pending = pending == null ? Map.of() : Map.copyOf(pending);
    }

    public Map<String, Object> toSnapshotMap() {
        return toSnapshotMap(true);
    }

    public Map<String, Object> toSnapshotMap(boolean includeStateHash) {
        Map<String, Object> out = new LinkedHashMap<>();
        putIfNotBlank(out, "schemaVersion", schemaVersion);
        putIfNotBlank(out, "executionKind", executionKind);
        putIfNotBlank(out, "turnId", turnId);
        putIfNotBlank(out, "runPid", runPid);
        putIfNotBlank(out, "taskPid", taskPid);
        putIfNotNull(out, "tenantId", tenantId);
        putIfNotNull(out, "userId", userId);
        putIfNotBlank(out, "agentCode", agentCode);
        putIfNotBlank(out, "sessionId", sessionId);
        putIfNotBlank(out, "providerCode", providerCode);
        putIfNotBlank(out, "model", model);
        out.put("round", round);
        putIfNotBlank(out, "toolChoice", toolChoice);
        if (context != null) {
            out.put("context", context.toSnapshotMap());
        }
        List<Map<String, Object>> toolSnapshots = new ArrayList<>();
        for (AgentToolManifestItem tool : tools) {
            if (tool != null) {
                toolSnapshots.add(tool.toSnapshotMap());
            }
        }
        out.put("tools", toolSnapshots);
        if (!pending.isEmpty()) {
            out.put("pending", pending);
        }
        if (includeStateHash) {
            putIfNotBlank(out, "stateHash", stateHash);
        }
        return out;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void putIfNotBlank(Map<String, Object> out, String key, String value) {
        if (!isBlank(value)) {
            out.put(key, value);
        }
    }

    private static void putIfNotNull(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }
}
