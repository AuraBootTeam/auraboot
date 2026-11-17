package com.auraboot.framework.meta.security;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The native writer owns one named root; it cannot inherit authority for arbitrary handlers. */
public record NativeBusinessCommandContract(String model, String action, List<String> permissions) {
    private static final Set<String> OPERATIONS = Set.of("create", "update", "delete", "state_transition");
    private static final Set<String> CONFIG_KEYS = Set.of("type", "permissions", "inputFields", "handlerParams");
    private static final Set<String> TRANSITION_KEYS = Set.of("type", "permissions", "inputFields", "handlerParams",
            "options", "stateField", "fromStates", "toState", "autoSetFields");
    private static final Set<String> SYSTEM_FIELDS = Set.of("id", "pid", "tenant_id", "created_by", "updated_by",
            "created_at", "updated_at", "row_version", "deleted_flag");
    private static final Set<String> PARAM_KEYS = Set.of("targetRecordAuthorization", "targetRecordAction");

    public NativeBusinessCommandContract { permissions = List.copyOf(permissions); }

    /** LoadPhase supplies the server definition; request operation/payload never defines this policy. */
    public static NativeBusinessCommandContract require(CommandPipelineContext context, ExtensionRegistry extensions) {
        if (context.getCommand() == null || context.getRequest() == null
                || !Objects.equals(context.getCommandCode(), context.getCommand().getCode())
                || context.getAuthorizationVerdict() == null || !context.getAuthorizationVerdict().isAuthorized()) throw denied();
        var contract = describe(context.getCommand(), context.getExecConfig(), context.getRulesByType(), extensions);
        String requested = context.getRequest().getOperationType();
        boolean transition = declaresTransition(context);
        if (requested != null && !requested.isBlank() && !contract.action().equalsIgnoreCase(requested)
                && !(transition && "state_transition".equalsIgnoreCase(requested))) throw denied();
        // An intent transition has no client-editable data, including aliases of the target.
        if (transition && !(context.submittedInputKeys() == null
                ? context.getPayload() == null || context.getPayload().isEmpty() : context.submittedInputKeys().isEmpty())) throw denied();
        if (!contract.permissions().contains(context.getAuthorizationVerdict().permissionCode())) throw denied();
        return contract;
    }

    /** Structural inspection shares execution validation but cannot manufacture an actor decision. */
    public static NativeBusinessCommandContract describe(com.auraboot.framework.meta.entity.CommandDefinition command,
            Map<String, Object> config, Map<String, List<com.auraboot.framework.meta.entity.BindingRule>> rules,
            ExtensionRegistry extensions) {
        if (command == null || command.getCode() == null || command.getCode().isBlank()
                || config == null || extensions == null
                || !(config.get("type") instanceof String action) || !OPERATIONS.contains(action)
                || !("state_transition".equals(action) ? TRANSITION_KEYS : CONFIG_KEYS).containsAll(config.keySet())
                || !(config.get("handlerParams") instanceof Map<?, ?> params)
                || !params.keySet().equals(PARAM_KEYS)
                || !"business".equals(params.get("targetRecordAuthorization"))
                || !("state_transition".equals(action) ? "update" : action).equals(params.get("targetRecordAction"))
                || rules == null || rules.values().stream().anyMatch(values -> values == null || !values.isEmpty())
                || extensions.getBusinessResourceOwner(command.getCode()).isPresent()
                || extensions.getCommandHandler(command.getCode()).isPresent()
                || !extensions.getSecondaryCommandHandlers(command.getCode()).isEmpty()) throw denied();
        String model = command.getModelCode();
        if (model == null || !model.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw denied();
        Object rawInputs = config.get("inputFields");
        boolean transition = "state_transition".equals(action);
        if (transition) validateTransition(config);
        if (!"delete".equals(action) && !transition && (!(rawInputs instanceof List<?> inputs) || inputs.isEmpty())) throw denied();
        if (rawInputs != null) {
            if (!(rawInputs instanceof List<?> inputs) || inputs.size() > 256
                    || new java.util.HashSet<>(inputs).size() != inputs.size()) throw denied();
            for (Object input : inputs) {
                if (!(input instanceof String field)) throw denied();
                if (!field.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw denied();
                if ("create".equals(action) && SYSTEM_FIELDS.contains(field)) throw denied();
            }
            if (("delete".equals(action) || transition) && !inputs.isEmpty()) throw denied();
        }
        if (!(config.get("permissions") instanceof List<?> codes) || codes.isEmpty() || codes.size() > 128
                || new java.util.HashSet<>(codes).size() != codes.size()) throw denied();
        for (Object code : codes) {
            if (!(code instanceof String value) || value.isBlank() || !value.equals(value.strip())
                    || value.length() > 255 || "*".equals(value) || "meta.command.execute".equals(value)
                    || value.startsWith("model.")) throw denied();
        }
        return new NativeBusinessCommandContract(model, transition ? "update" : action, codes.stream().map(String.class::cast).toList());
    }

    private static void validateTransition(Map<String, Object> config) {
        if (!Map.of("targetVersionPolicy", "current_state").equals(config.get("options"))
                || !(config.get("stateField") instanceof String field) || !businessField(field)
                || !(config.get("toState") instanceof String target) || target.isBlank() || !target.equals(target.strip())
                || !(config.get("fromStates") instanceof List<?> states) || states.isEmpty() || states.size() > 128
                || new java.util.HashSet<>(states).size() != states.size() || states.contains(target)) throw denied();
        for (Object state : states)
            if (!(state instanceof String value) || value.isBlank() || !value.equals(value.strip())) throw denied();
        Object raw = config.get("autoSetFields");
        if (raw == null) return;
        if (!(raw instanceof Map<?, ?> automatic) || automatic.size() > 32) throw denied();
        for (var entry : automatic.entrySet()) {
            if (!(entry.getKey() instanceof String code) || !businessField(code) || field.equals(code)
                    || !(entry.getValue() instanceof Map<?, ?> rule)) throw denied();
            if (Map.of("strategy", "current_datetime").equals(rule)) continue;
            if (!rule.keySet().equals(Set.of("strategy", "value")) || !"fixed_value".equals(rule.get("strategy"))
                    || !(rule.get("value") instanceof String || rule.get("value") instanceof Number
                        || rule.get("value") instanceof Boolean)) throw denied();
        }
    }

    private static boolean businessField(String value) {
        return value.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") && !SYSTEM_FIELDS.contains(value);
    }

    public static boolean declaresTransition(CommandPipelineContext context) {
        if (context == null || context.getExecConfig() == null || !"state_transition".equals(context.getExecConfig().get("type"))) return false;
        Object params = context.getExecConfig().get("handlerParams");
        return params instanceof Map<?, ?> values && "business".equals(values.get("targetRecordAuthorization"));
    }

    /** Before cache lookup allow the completed intent; a fresh write still requires a from-state. */
    public static void validateTransitionState(Map<String, Object> config, Map<String, Object> record, boolean replayAllowed) {
        Object state = record == null ? null : record.get(config.get("stateField"));
        if (!(state instanceof String value) || (!((List<?>) config.get("fromStates")).contains(value)
                && !(replayAllowed && value.equals(config.get("toState")))))
            throw new com.auraboot.framework.exception.ValidationException(ResponseCode.CommonValidationFailed,
                    "$i18n:command.transition.not_allowed");
    }

    public static boolean declaresCreation(CommandPipelineContext context) {
        if (context == null || context.getExecConfig() == null
                || !"create".equals(context.getExecConfig().get("type"))) return false;
        Object params = context.getExecConfig().get("handlerParams");
        return params instanceof Map<?, ?> values && "business".equals(values.get("targetRecordAuthorization"));
    }

    private static BusinessException denied() {
        return new BusinessException(ResponseCode.FORBIDDEN, "Native business command exceeds its single-record contract");
    }
}
