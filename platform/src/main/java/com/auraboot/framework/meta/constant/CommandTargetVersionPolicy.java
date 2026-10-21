package com.auraboot.framework.meta.constant;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Server-owned target-version modes shared by import validation and the runtime lock phase. */
public enum CommandTargetVersionPolicy {
    DEFAULT,
    CURRENT_STATE,
    UNSUPPORTED;

    private static final Set<String> UNSUPPORTED_MUTATORS =
            Set.of(
                    "autoSetFields",
                    "inputFields",
                    "fieldMapping",
                    "fieldMappings",
                    "computedFields",
                    "preActions",
                    "stateField",
                    "fromStates",
                    "toState",
                    "stateTransitionRules",
                    "cascadeDelete",
                    "bpmTrigger",
                    "sideEffects",
                    "postActions",
                    "processKey");

    public static CommandTargetVersionPolicy parse(JsonNode root) {
        if (root == null || !root.isObject() || !root.has("options")) return DEFAULT;
        JsonNode options = root.get("options");
        require(options.isObject());
        if (!options.has("targetVersionPolicy")) return DEFAULT;
        JsonNode value = options.get("targetVersionPolicy");
        require(value.isTextual());
        if ("current_state".equals(value.textValue())) return CURRENT_STATE;
        require("unsupported".equals(value.textValue()));
        require(root.path("type").isTextual() && "custom".equals(root.path("type").textValue()));
        require(root.path("handler").isTextual() && !root.path("handler").textValue().isBlank());
        if (root.has("casRequired"))
            require(root.get("casRequired").isBoolean() && !root.get("casRequired").booleanValue());
        for (String field : UNSUPPORTED_MUTATORS) require(!root.has(field));
        if (root.has("executionMode"))
            require(
                    root.get("executionMode").isTextual()
                            && "SYNC".equals(root.get("executionMode").textValue()));
        if (root.has("handlerParams")) {
            JsonNode params = root.get("handlerParams");
            require(params.isObject());
            for (String field : Set.of("async", "dslPersistence"))
                if (params.has(field))
                    require(params.get(field).isBoolean() && !params.get(field).booleanValue());
        }
        return UNSUPPORTED;
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid command target version policy");
    }
}
