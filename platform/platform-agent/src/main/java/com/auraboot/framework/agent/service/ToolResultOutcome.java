package com.auraboot.framework.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** Shared interpretation of the existing tool result contract. */
final class ToolResultOutcome {
    private ToolResultOutcome() {}

    static boolean isSuccess(String result, ObjectMapper mapper) {
        if (result == null || result.startsWith("Error")) return false;
        try {
            Object parsed = mapper.readValue(result, Object.class);
            if (parsed instanceof Map<?, ?> map && map.containsKey("success")) {
                return Boolean.TRUE.equals(map.get("success"));
            }
        } catch (JsonProcessingException nonJson) {
            // Plain text remains a supported successful tool response.
        }
        return true;
    }
}
