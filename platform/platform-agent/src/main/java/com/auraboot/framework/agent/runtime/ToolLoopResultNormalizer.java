package com.auraboot.framework.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical parser for raw ToolLoopService outputs before they are returned to
 * the model or exposed in a turn result.
 */
@Slf4j
public final class ToolLoopResultNormalizer {

    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();

    private ToolLoopResultNormalizer() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalize(ObjectMapper objectMapper,
                                                String rawResult,
                                                String toolName,
                                                Map<String, Object> input) {
        if (rawResult == null || rawResult.isBlank()) {
            return errorResult(toolReturnedErrorFrame(toolName, input, "EmptyToolResult"), 0L);
        }

        String trimmed = rawResult.trim();
        if (trimmed.startsWith("{")) {
            try {
                Map<String, Object> parsed = mapper(objectMapper).readValue(trimmed, Map.class);
                if (!parsed.containsKey("success")) {
                    parsed = new LinkedHashMap<>(parsed);
                    parsed.put("success", !parsed.containsKey("error"));
                }
                if (isReturnedToolError(parsed)) {
                    return errorResult(
                            toolReturnedErrorFrame(toolName, input, "ToolReturnedError"),
                            parsed.get("durationMs"));
                }
                return parsed;
            } catch (Exception e) {
                log.debug("Failed to parse tool loop result as JSON: {}", e.getClass().getSimpleName());
            }
        }

        if (trimmed.startsWith("Error")) {
            return errorResult(toolReturnedErrorFrame(toolName, input, "ToolReturnedError"), 0L);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", trimmed);
        response.put("durationMs", 0L);
        return response;
    }

    private static ObjectMapper mapper(ObjectMapper objectMapper) {
        return objectMapper != null ? objectMapper : FALLBACK_MAPPER;
    }

    private static AgentErrorFrame toolReturnedErrorFrame(String toolName,
                                                          Map<String, Object> input,
                                                          String errorClass) {
        return AgentErrorFrame.of(
                AgentErrorFrame.CATEGORY_TOOL,
                toolName,
                input,
                errorClass,
                true,
                "Tool execution failed.",
                "Use corrected arguments or summarize the failure to the user.");
    }

    private static Map<String, Object> errorResult(AgentErrorFrame errorFrame, Object durationMs) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", errorFrame.userSafeMessage());
        response.put("errorFrame", errorFrame.toSnapshotMap());
        response.put("retryable", errorFrame.retryable());
        response.put("durationMs", durationMs instanceof Number ? durationMs : 0L);
        return response;
    }

    private static boolean isReturnedToolError(Map<String, Object> result) {
        return result != null
                && Boolean.FALSE.equals(result.get("success"))
                && result.get("error") != null
                && result.get("errorFrame") == null
                && !isApprovalRequiredResult(result)
                && !isAuraBotSkillPreviewPending(result);
    }

    private static boolean isApprovalRequiredResult(Map<String, Object> result) {
        return result != null && Boolean.TRUE.equals(result.get("approvalRequired"));
    }

    private static boolean isAuraBotSkillPreviewPending(Map<String, Object> result) {
        if (result == null) {
            return false;
        }
        if (Boolean.TRUE.equals(result.get("_aurabot_skill_pending"))) {
            return true;
        }
        return Boolean.TRUE.equals(result.get("approvalRequired"))
                && result.get("previewToken") instanceof String token
                && !token.isBlank();
    }
}
