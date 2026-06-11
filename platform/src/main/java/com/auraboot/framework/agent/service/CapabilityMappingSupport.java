package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.util.JsonbColumns;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared mapping helpers and risk constants used by {@link CapabilityViewService}
 * (legacy on-the-fly read path), {@link CapabilitySyncService} (write path) and
 * {@link CapabilityGraphService}. Extracted to a package-level component so the
 * three services can share the exact same derivation logic without a circular
 * dependency (CapabilityViewService injects CapabilitySyncService for delegation).
 */
@Component
@RequiredArgsConstructor
public class CapabilityMappingSupport {

    static final Map<String, String> RISK_CONFIRMATION = Map.of(
            "L0", "none",
            "L1", "none",
            "L2", "confirm",
            "L3", "confirm_with_detail",
            "L4", "approval_required"
    );

    /** Risk level mapping for automation action types. */
    static final Map<String, String> AUTOMATION_ACTION_RISK = Map.of(
            "send_notification", "L0",
            "update_record", "L1",
            "create_record", "L2",
            "execute_command", "L2",
            "call_api", "L3",
            "send_webhook", "L3"
    );

    private final ObjectMapper objectMapper;

    String deriveWhenToUse(String cmdType, Map<String, Object> execConfig) {
        if (execConfig == null) return null;
        if ("state_transition".equals(cmdType)) {
            Object fromStates = execConfig.get("fromStates");
            Object toState = execConfig.get("toState");
            Object stateField = execConfig.get("stateField");
            if (fromStates != null && toState != null) {
                return "When " + stateField + " is " + fromStates + " and you need to change it to " + toState;
            }
        }
        return switch (cmdType != null ? cmdType : "") {
            case "create" -> "When you need to create a new record";
            case "update" -> "When you need to modify an existing record";
            case "delete" -> "When you need to permanently remove a record";
            default -> null;
        };
    }

    String deriveWhenNotToUse(String cmdType, String riskLevel) {
        List<String> warnings = new ArrayList<>();
        if ("delete".equals(cmdType)) {
            warnings.add("This operation is irreversible");
        }
        if ("L3".equals(riskLevel) || "L4".equals(riskLevel)) {
            warnings.add("This is a high-risk operation requiring approval");
        }
        return warnings.isEmpty() ? null : String.join(". ", warnings);
    }

    String inferRiskLevel(String cmdType, Map<String, Object> execConfig) {
        if ("query".equals(cmdType)) return "L0";
        if ("delete".equals(cmdType)) return "L4";
        if (execConfig.get("sideEffects") instanceof List<?> list && !list.isEmpty()) return "L2";
        return "L1";
    }

    String deriveAutomationWhenToUse(String triggerType, String modelCode) {
        return switch (triggerType != null ? triggerType : "") {
            case "on_record_create" -> "Triggers when a new record is created on " + modelCode;
            case "on_record_update" -> "Triggers when a record is updated on " + modelCode;
            case "on_field_change" -> "Triggers when a specific field changes on " + modelCode;
            case "on_state_change" -> "Triggers when the state changes on " + modelCode;
            case "scheduled" -> "Triggers on a scheduled interval for " + modelCode;
            case "webhook" -> "Triggers when an external webhook is received for " + modelCode;
            case "on_bpm_event" -> "Triggers when a BPM event occurs for " + modelCode;
            case "on_inactivity" -> "Triggers when a record on " + modelCode + " has been inactive";
            default -> "Automation trigger on " + modelCode;
        };
    }

    /**
     * Compare two risk level strings. Returns positive if a > b.
     */
    int compareRisk(String a, String b) {
        int aLevel = a != null && a.length() == 2 ? Character.getNumericValue(a.charAt(1)) : 0;
        int bLevel = b != null && b.length() == 2 ? Character.getNumericValue(b.charAt(1)) : 0;
        return aLevel - bLevel;
    }

    /**
     * Convert a value read for a JSONB column (String / driver PGobject /
     * already-parsed Map/List) to its JSON text. Delegates to {@link JsonbColumns}
     * so a PGobject is read via toString() rather than serializing its wrapper.
     */
    String stringifyValue(Object value) {
        return JsonbColumns.toJsonText(value, objectMapper);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
