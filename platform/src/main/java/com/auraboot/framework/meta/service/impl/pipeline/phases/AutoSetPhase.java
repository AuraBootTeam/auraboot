package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.ValidationContext;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.ValidationService;
import com.auraboot.framework.meta.service.impl.CommandAutoSetExecutor;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;

@Slf4j
@Component
@Order(900)
@RequiredArgsConstructor
public class AutoSetPhase implements CommandPhase {

    private final CommandAutoSetExecutor autoSetExecutor;
    private final MetaModelService metaModelService;
    private final ValidationService validationService;

    @Override
    public String name() {
        return "auto_set";
    }

    @Override
    public boolean shouldSkip(CommandPipelineContext ctx) {
        return ctx.isHasPluginHandler() && !ctx.isPluginRequiresDslPersistence();
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        autoSetExecutor.executeAutoSetPhase(ctx.getExecConfig(), ctx.getPayload(),
                ctx.getTenantId(), ctx.getUserId(), ctx.getCommand());
        executeCommandFieldValidationPhase(ctx.getExecConfig(), ctx.getPayload(),
                ctx.getCommand(), ctx.getRequest());
    }

    // ==================== Inlined delegate method ====================

    @SuppressWarnings("unchecked")
    private void executeCommandFieldValidationPhase(Map<String, Object> execConfig,
                                                     Map<String, Object> payload,
                                                     CommandDefinition command,
                                                     CommandExecuteRequest request) {
        if (execConfig == null || !StringUtils.hasText(command.getModelCode())) {
            return;
        }

        ModelDefinition modelDef = metaModelService.getModelDefinition(command.getModelCode()).orElse(null);
        if (modelDef == null || modelDef.getFields() == null || modelDef.getFields().isEmpty()) {
            return;
        }

        Set<String> fieldsToValidate = new LinkedHashSet<>();
        Object inputFieldsObj = execConfig.get("inputFields");
        if (inputFieldsObj instanceof List<?> inputFields) {
            for (Object inputField : inputFields) {
                if (inputField instanceof String fieldCode && StringUtils.hasText(fieldCode)) {
                    fieldsToValidate.add(fieldCode);
                }
            }
        }

        String stateField = (String) execConfig.get("stateField");
        if (StringUtils.hasText(stateField)) {
            fieldsToValidate.add(stateField);
        }

        if (fieldsToValidate.isEmpty()) {
            return;
        }

        String operationType = request != null ? request.getOperationType() : null;
        if (!StringUtils.hasText(operationType) && execConfig.get("type") != null) {
            operationType = String.valueOf(execConfig.get("type"));
        }
        boolean isStateTransition = "state_transition".equalsIgnoreCase(operationType)
                || "state_transition".equalsIgnoreCase(String.valueOf(execConfig.get("type")));
        boolean isUpdateLike = "update".equalsIgnoreCase(operationType)
                || "delete".equalsIgnoreCase(operationType)
                || isStateTransition;

        ValidationContext context = isUpdateLike
                ? ValidationContext.UPDATE
                : ValidationContext.CREATE;

        List<String> errors = new ArrayList<>();
        Map<String, FieldDefinition> fieldMap = new HashMap<>();
        for (FieldDefinition field : modelDef.getFields()) {
            fieldMap.put(field.getCode(), field);
        }

        for (String fieldCode : fieldsToValidate) {
            FieldDefinition fieldDefinition = fieldMap.get(fieldCode);
            if (fieldDefinition == null) {
                continue;
            }
            boolean payloadHasField = payload.containsKey(fieldCode);
            Object value = payload.get(fieldCode);

            if (isStateTransition
                    && fieldCode.equals(stateField)
                    && !payloadHasField
                    && execConfig.get("toState") != null) {
                value = execConfig.get("toState");
                payloadHasField = true;
            }

            if (isUpdateLike && !payloadHasField) {
                continue;
            }

            ValidationContext fieldContext = (isUpdateLike && payloadHasField)
                    ? ValidationContext.CREATE
                    : context;
            var result = validationService.validateField(fieldDefinition, value, fieldContext);
            if (!result.isValid() && result.getErrors() != null) {
                errors.addAll(result.getErrors());
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, String.join("; ", errors));
        }
    }
}
