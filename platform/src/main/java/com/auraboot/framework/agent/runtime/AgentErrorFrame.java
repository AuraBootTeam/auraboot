package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.util.CanonicalJsonHasher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compact, secret-free error context for model recovery and runtime audit.
 */
public record AgentErrorFrame(
        String schemaVersion,
        String category,
        String toolName,
        String argsHash,
        String errorClass,
        boolean retryable,
        String userSafeMessage,
        String modelRecoveryHint) {

    public static final String SCHEMA_VERSION = "agent-error-frame/v1";
    public static final String CATEGORY_PROVIDER = "provider";
    public static final String CATEGORY_TOOL = "tool";
    public static final String CATEGORY_VALIDATION = "validation";

    public AgentErrorFrame {
        schemaVersion = isBlank(schemaVersion) ? SCHEMA_VERSION : schemaVersion;
        category = isBlank(category) ? CATEGORY_TOOL : category;
        errorClass = isBlank(errorClass) ? "UnknownError" : errorClass;
        userSafeMessage = isBlank(userSafeMessage) ? "Agent execution failed." : userSafeMessage;
        modelRecoveryHint = isBlank(modelRecoveryHint) ? "Summarize the failure to the user." : modelRecoveryHint;
    }

    public static AgentErrorFrame of(String category, String toolName, Map<String, Object> args,
                                     String errorClass, boolean retryable,
                                     String userSafeMessage, String modelRecoveryHint) {
        return new AgentErrorFrame(
                SCHEMA_VERSION,
                category,
                blankToNull(toolName),
                CanonicalJsonHasher.sha256Canonical(args == null ? Map.of() : args),
                errorClass,
                retryable,
                userSafeMessage,
                modelRecoveryHint);
    }

    public Map<String, Object> toSnapshotMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        putIfNotBlank(out, "schemaVersion", schemaVersion);
        putIfNotBlank(out, "category", category);
        putIfNotBlank(out, "toolName", toolName);
        putIfNotBlank(out, "argsHash", argsHash);
        putIfNotBlank(out, "errorClass", errorClass);
        out.put("retryable", retryable);
        putIfNotBlank(out, "userSafeMessage", userSafeMessage);
        putIfNotBlank(out, "modelRecoveryHint", modelRecoveryHint);
        return out;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void putIfNotBlank(Map<String, Object> out, String key, String value) {
        if (!isBlank(value)) {
            out.put(key, value);
        }
    }
}
