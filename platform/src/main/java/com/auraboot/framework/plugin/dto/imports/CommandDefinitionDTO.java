package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DTO for importing command definitions from plugin manifest.
 * Supports extended DSL fields: type, autoSetFields, sideEffects,
 * stateTransitionRules, inputFields, fromStates, toState, stateField,
 * permissions, cascadeDelete, computedFields, postActions.
 * These DSL fields are consolidated into executionConfig for storage.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommandDefinitionDTO {

    /**
     * Command code (unique within tenant).
     * Required.
     */
    private String code;

    /**
     * Display name.
     */
    private String displayName;

    /**
     * Localized display names.
     */
    @JsonProperty("displayName:zh-CN")
    private String displayNameZhCN;

    @JsonProperty("displayName:en")
    private String displayNameEn;

    /**
     * Command description.
     */
    private String description;

    /**
     * Primary model code this command operates on.
     * Required.
     */
    private String modelCode;

    /**
     * Input schema defining command parameters.
     */
    private Map<String, Object> inputSchema;

    /**
     * Target models affected by this command.
     */
    private List<String> targetModels;

    /**
     * Execution configuration (structured form).
     */
    private ExecutionConfig executionConfig;

    /**
     * Binding rules for this command.
     */
    private List<BindingRuleDTO> bindingRules;

    /**
     * Extension properties.
     */
    private Map<String, Object> extension;

    /**
     * Captures unknown JSON fields for validation warnings.
     */
    @JsonIgnore
    private Map<String, Object> unknownFields;

    @JsonAnySetter
    public void setUnknownField(String key, Object value) {
        if (unknownFields == null) {
            unknownFields = new HashMap<>();
        }
        unknownFields.put(key, value);
    }

    // ==================== DSL Extended Fields ====================

    /**
     * Command type: CREATE, UPDATE, DELETE, STATE_TRANSITION.
     * Accepts "actionType" as an alias for backward compatibility with legacy plugin JSON format.
     */
    @JsonAlias("actionType")
    private String type;

    /**
     * Auto-set fields on execution.
     * Map of fieldCode → {strategy: AUTO_GENERATE|CURRENT_USER|CURRENT_DATETIME|FIXED_VALUE, value?: ...}
     */
    private Map<String, Map<String, Object>> autoSetFields;

    /**
     * Side effects executed after the main operation.
     */
    private List<SideEffectConfig> sideEffects;

    /**
     * Conditional state transition rules (multi-branch).
     */
    private List<StateTransitionRuleConfig> stateTransitionRules;

    /**
     * Input field codes accepted by this command.
     */
    private List<String> inputFields;

    /**
     * Valid source states for state transitions.
     */
    private List<String> fromStates;

    /**
     * Target state for simple (non-branching) state transitions.
     */
    private String toState;

    /**
     * State field name for state transitions.
     */
    private String stateField;

    /**
     * Required permission codes.
     */
    private List<String> permissions;

    /**
     * Cascade delete configuration for child models.
     */
    private List<CascadeDeleteConfig> cascadeDelete;

    /**
     * Computed fields using SpEL expressions.
     * Map of fieldCode → SpEL expression string.
     */
    private Map<String, String> computedFields;

    /**
     * Post-actions executed after main operation (e.g., CREATE_CHILDREN).
     */
    private List<PostActionConfig> postActions;

    /**
     * Validation rules.
     */
    private ValidationConfig validation;

    /**
     * Preconditions that must be satisfied.
     */
    private List<Map<String, Object>> preconditions;

    /**
     * Declarative BPM trigger: auto-start an approval process when this command executes.
     * Example: {"processKey": "so_approval", "titleTemplate": "SO Approval: ${payload.sl_so_code}"}
     */
    private Map<String, Object> bpmTrigger;

    /**
     * Validate command definition has required fields.
     */
    public boolean isValid() {
        return code != null && !code.isBlank()
                && modelCode != null && !modelCode.isBlank();
    }

    /**
     * Get effective display name.
     */
    public String getEffectiveDisplayName() {
        if (displayNameZhCN != null && !displayNameZhCN.isBlank()) {
            return displayNameZhCN;
        }
        if (displayNameEn != null && !displayNameEn.isBlank()) {
            return displayNameEn;
        }
        return displayName != null ? displayName : code;
    }

    /**
     * Build a consolidated executionConfig map from DSL fields.
     * This merges the structured ExecutionConfig with DSL extended fields
     * into a single map for storage in the executionConfig JSON column.
     */
    @JsonIgnore
    public Map<String, Object> getConsolidatedExecutionConfig() {
        Map<String, Object> config = new HashMap<>();

        // Merge structured ExecutionConfig fields
        if (executionConfig != null) {
            if (executionConfig.executionMode != null) config.put("executionMode", executionConfig.executionMode);
            if (executionConfig.handler != null) config.put("handler", executionConfig.handler);
            if (executionConfig.timeout != null) config.put("timeout", executionConfig.timeout);
            if (executionConfig.retryPolicy != null) config.put("retryPolicy", executionConfig.retryPolicy);
            if (executionConfig.requireIdempotencyKey != null) config.put("requireIdempotencyKey", executionConfig.requireIdempotencyKey);
            if (executionConfig.processKey != null) config.put("processKey", executionConfig.processKey);
            if (executionConfig.options != null) config.put("options", executionConfig.options);
        }

        // Merge DSL extended fields
        if (type != null) config.put("type", type);
        if (autoSetFields != null) config.put("autoSetFields", autoSetFields);
        if (sideEffects != null) config.put("sideEffects", sideEffects);
        if (stateTransitionRules != null) config.put("stateTransitionRules", stateTransitionRules);
        if (inputFields != null) config.put("inputFields", inputFields);
        if (fromStates != null) config.put("fromStates", fromStates);
        if (toState != null) config.put("toState", toState);
        if (stateField != null) config.put("stateField", stateField);
        if (permissions != null) config.put("permissions", permissions);
        if (cascadeDelete != null) config.put("cascadeDelete", cascadeDelete);
        if (computedFields != null) config.put("computedFields", computedFields);
        if (postActions != null) config.put("postActions", postActions);
        if (validation != null) config.put("validation", validation);
        if (preconditions != null) config.put("preconditions", preconditions);
        if (bpmTrigger != null) config.put("bpmTrigger", bpmTrigger);

        return config.isEmpty() ? null : config;
    }

    // ==================== Nested Config Classes ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExecutionConfig {
        private String executionMode;
        private String handler;
        private Long timeout;
        private Map<String, Object> retryPolicy;
        private Boolean requireIdempotencyKey;
        private String processKey;
        private Map<String, Object> options;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SideEffectConfig {
        private String condition;
        private String action;
        private String targetModel;
        private String targetIdField;
        private Map<String, Object> fieldMapping;
        private List<Map<String, Object>> actions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StateTransitionRuleConfig {
        @Deprecated // Use 'guard' instead. Kept for backward compatibility with legacy plugin JSON.
        private String condition;
        private String guard;
        private String toState;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CascadeDeleteConfig {
        private String childModel;
        private String parentField;
        private List<CascadeDeleteConfig> cascadeDelete;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PostActionConfig {
        private String type;
        private String action; // Alias for 'type', kept for consistency with SideEffectConfig
        private String targetModel;
        private String childModel;
        private String parentField;
        private Map<String, Object> fieldMapping;
        private List<Map<String, Object>> records;
        private Integer count;
        private String condition;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationConfig {
        private List<Map<String, Object>> rules;
    }
}
