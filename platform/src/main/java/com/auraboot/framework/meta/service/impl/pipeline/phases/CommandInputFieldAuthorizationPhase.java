package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.FieldWriteAuthorizationSupport;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;

/** User-supplied fields must be authorized before command permits or cached outcomes are used. */
@Component
@Order(280)
@RequiredArgsConstructor
public class CommandInputFieldAuthorizationPhase implements CommandPhase {
    private final DataPermissionEngine policies;
    private final MetaModelService metadata;
    private final FieldPermissionService fields;

    @Override public String name() { return "input_field_authorization"; }

    @Override public void execute(CommandPipelineContext context) {
        if (context.getCommand() == null || submittedKeys(context).isEmpty()) return;
        // Machine and installed invocations use their separately verified writable-field contracts.
        if (com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                || MetaContext.getCurrentMemberId() == null || MetaContext.getCurrentUserId() == null) return;
        String rootModel = context.getCommand().getModelCode();
        if (StringUtils.hasText(rootModel)) {
            var restricted = policies.getNonWritableFields(MetaContext.getCurrentTenantId(),
                    rootModel, MetaContext.getCurrentUserId());
            if (restricted != null && submittedKeys(context).stream().anyMatch(restricted::contains)) deny();
        }
        var authorization = new FieldWriteAuthorizationSupport(metadata, fields, policies);
        for (var write : mappedInputs(context).entrySet()) {
            if (!authorization.nonWritableInputKeys(write.getKey(), write.getValue()).isEmpty()) deny();
        }
    }

    private Map<String, Set<String>> mappedInputs(CommandPipelineContext context) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        Set<String> submitted = submittedKeys(context);
        var mappings = context.getRulesByType().getOrDefault("field_map", List.of());
        if (!mappings.isEmpty()) {
            for (var rule : mappings) {
                if (StringUtils.hasText(rule.getTargetModel()) && StringUtils.hasText(rule.getTargetField())
                        && StringUtils.hasText(rule.getSourceField()) && submitted.contains(rule.getSourceField()))
                    result.computeIfAbsent(rule.getTargetModel(), ignored -> new LinkedHashSet<>()).add(rule.getTargetField());
            }
            return result;
        }
        String model = context.getCommand().getModelCode();
        if (!StringUtils.hasText(model)) return result;
        Object type = context.getExecConfig().get("type");
        String operation = type instanceof String value ? value.toLowerCase(Locale.ROOT) : "";
        Object input = context.getExecConfig().get("inputFields");
        boolean directWrite = Set.of("create", "update").contains(operation);
        boolean transition = "state_transition".equals(operation);
        boolean legacyWrite = operation.isEmpty() && context.getRequest() != null
                && Set.of("create", "update").contains(Objects.toString(context.getRequest().getOperationType(), "").toLowerCase(Locale.ROOT));
        if (!directWrite && !transition && !legacyWrite) return result;
        Set<String> keys = new LinkedHashSet<>();
        if (input != null) {
            if (!(input instanceof List<?> inputs))
                throw new BusinessException(ResponseCode.BadParam, "Command inputFields must be a field list");
            for (Object key : inputs) {
                if (!(key instanceof String field) || field.isBlank())
                    throw new BusinessException(ResponseCode.BadParam, "Command inputFields contains an invalid field");
                if (submitted.contains(field)) keys.add(field);
            }
        }
        if (directWrite && (input == null || ((List<?>) input).isEmpty())) keys.addAll(submitted);
        Object autoSet = context.getExecConfig().get("autoSetFields");
        if (autoSet instanceof Map<?, ?> defaults) {
            for (Object key : defaults.keySet()) {
                if (key instanceof String field && submitted.contains(field)) keys.add(field);
            }
        }
        if (!keys.isEmpty()) result.put(model, keys);
        return result;
    }

    private void deny() {
        throw new BusinessException(ResponseCode.FORBIDDEN, "Command input contains a non-writable field");
    }

    private Set<String> submittedKeys(CommandPipelineContext context) {
        if (context.submittedInputKeys() != null) return context.submittedInputKeys();
        return context.getPayload() == null ? Set.of() : context.getPayload().keySet();
    }
}
