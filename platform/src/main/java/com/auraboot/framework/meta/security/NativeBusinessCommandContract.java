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
    private static final Set<String> OPERATIONS = Set.of("create", "update", "delete");
    private static final Set<String> CONFIG_KEYS = Set.of("type", "permissions", "inputFields", "handlerParams");
    private static final Set<String> PARAM_KEYS = Set.of("targetRecordAuthorization", "targetRecordAction");

    public NativeBusinessCommandContract { permissions = List.copyOf(permissions); }

    /** LoadPhase supplies the server definition; request operation/payload never defines this policy. */
    public static NativeBusinessCommandContract require(CommandPipelineContext context, ExtensionRegistry extensions) {
        if (context.getCommand() == null || context.getRequest() == null
                || !Objects.equals(context.getCommandCode(), context.getCommand().getCode())
                || context.getAuthorizationVerdict() == null || !context.getAuthorizationVerdict().isAuthorized()) throw denied();
        var contract = describe(context.getCommand(), context.getExecConfig(), context.getRulesByType(), extensions);
        String requested = context.getRequest().getOperationType();
        if (requested != null && !requested.isBlank() && !contract.action().equalsIgnoreCase(requested)) throw denied();
        if (!contract.permissions().contains(context.getAuthorizationVerdict().permissionCode())) throw denied();
        return contract;
    }

    /** Structural inspection shares execution validation but cannot manufacture an actor decision. */
    public static NativeBusinessCommandContract describe(com.auraboot.framework.meta.entity.CommandDefinition command,
            Map<String, Object> config, Map<String, List<com.auraboot.framework.meta.entity.BindingRule>> rules,
            ExtensionRegistry extensions) {
        if (command == null || command.getCode() == null || command.getCode().isBlank()
                || config == null || extensions == null
                || !CONFIG_KEYS.containsAll(config.keySet())
                || !(config.get("type") instanceof String action) || !OPERATIONS.contains(action)
                || !(config.get("handlerParams") instanceof Map<?, ?> params)
                || !params.keySet().equals(PARAM_KEYS)
                || !"business".equals(params.get("targetRecordAuthorization"))
                || !action.equals(params.get("targetRecordAction"))
                || rules == null || rules.values().stream().anyMatch(values -> values == null || !values.isEmpty())
                || extensions.getBusinessResourceOwner(command.getCode()).isPresent()
                || extensions.getCommandHandler(command.getCode()).isPresent()
                || !extensions.getSecondaryCommandHandlers(command.getCode()).isEmpty()) throw denied();
        String model = command.getModelCode();
        if (model == null || !model.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw denied();
        Object rawInputs = config.get("inputFields");
        if (!"delete".equals(action) && (!(rawInputs instanceof List<?> inputs) || inputs.isEmpty())) throw denied();
        if (rawInputs != null) {
            if (!(rawInputs instanceof List<?> inputs) || inputs.size() > 256
                    || new java.util.HashSet<>(inputs).size() != inputs.size()) throw denied();
            for (Object input : inputs) {
                if (!(input instanceof String field)) throw denied();
                if (!field.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw denied();
                if ("create".equals(action) && Set.of("id", "pid", "tenant_id", "created_by", "updated_by",
                        "created_at", "updated_at", "row_version", "deleted_flag").contains(field)) throw denied();
            }
            if ("delete".equals(action) && !inputs.isEmpty()) throw denied();
        }
        if (!(config.get("permissions") instanceof List<?> codes) || codes.isEmpty() || codes.size() > 128
                || new java.util.HashSet<>(codes).size() != codes.size()) throw denied();
        for (Object code : codes) {
            if (!(code instanceof String value) || value.isBlank() || !value.equals(value.strip())
                    || value.length() > 255 || "*".equals(value) || "meta.command.execute".equals(value)
                    || value.startsWith("model.")) throw denied();
        }
        return new NativeBusinessCommandContract(model, action, codes.stream().map(String.class::cast).toList());
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
