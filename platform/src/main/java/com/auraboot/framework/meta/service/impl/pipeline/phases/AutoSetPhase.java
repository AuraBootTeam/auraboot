package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.ValidationContext;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.ValidationService;
import com.auraboot.framework.meta.service.impl.CommandAutoSetExecutor;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.support.CronExpression;
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
    private final DynamicDataMapper dynamicDataMapper;

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
        executeModelSpecificValidations(ctx);
    }

    /**
     * Model-aware structural validations that cannot be expressed via
     * generic FieldDefinition constraints (cross-field rules, scoped
     * uniqueness). Today only {@code scheduled_task} is covered; extend
     * here if other models need similar treatment.
     */
    private void executeModelSpecificValidations(CommandPipelineContext ctx) {
        if (ctx.getCommand() == null || ctx.getPayload() == null) {
            return;
        }
        String modelCode = ctx.getCommand().getModelCode();
        if (!"scheduled_task".equals(modelCode)) {
            return;
        }

        Map<String, Object> payload = ctx.getPayload();
        String operationType = resolveOperationType(ctx);
        boolean isCreate = "create".equalsIgnoreCase(operationType);
        boolean isUpdate = "update".equalsIgnoreCase(operationType);
        if (!isCreate && !isUpdate) {
            return;
        }

        // G-7: Validate cron expression syntax server-side. Without this
        // check the platform would happily persist garbage like
        // "every-minute-please" and only blow up at the next scheduler tick.
        Object taskTypeRaw = payload.get("task_type");
        Object cronRaw = payload.get("cron_expression");
        if (taskTypeRaw instanceof String taskType
                && "cron".equalsIgnoreCase(taskType)
                && cronRaw instanceof String cron
                && StringUtils.hasText(cron)) {
            try {
                CronExpression.parse(cron);
            } catch (IllegalArgumentException ex) {
                throw new ValidationException(ResponseCode.CommonValidationFailed,
                        "Invalid cron expression: " + ex.getMessage());
            }
        }

        // G-8: Scoped (tenant_id, name) uniqueness for scheduled tasks.
        // The DB carries a unique index as last-line defence; this pre-check
        // surfaces a friendly error instead of a SQL constraint stack trace.
        Object nameRaw = payload.get("name");
        if (nameRaw instanceof String name && StringUtils.hasText(name)) {
            Long tenantId = ctx.getTenantId() != null ? ctx.getTenantId() : MetaContext.getCurrentTenantId();
            String sql = "SELECT COUNT(*) AS cnt FROM ab_scheduled_task"
                    + " WHERE tenant_id = #{params.tenantId} AND name = #{params.name}";
            Map<String, Object> params = new HashMap<>();
            params.put("tenantId", tenantId);
            params.put("name", name);
            if (isUpdate && ctx.getRequest() != null
                    && StringUtils.hasText(ctx.getRequest().getTargetRecordId())) {
                sql += " AND pid <> #{params.excludePid}";
                params.put("excludePid", ctx.getRequest().getTargetRecordId());
            }
            try {
                List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, params);
                if (rows != null && !rows.isEmpty()) {
                    Number cnt = (Number) rows.get(0).get("cnt");
                    if (cnt != null && cnt.longValue() > 0) {
                        throw new ValidationException(ResponseCode.CommonValidationFailed,
                                "Scheduled task name already exists: " + name);
                    }
                }
            } catch (ValidationException ve) {
                throw ve;
            } catch (Exception e) {
                log.warn("Scheduled task uniqueness check failed (name={}): {}", name, e.getMessage());
            }
        }
    }

    private String resolveOperationType(CommandPipelineContext ctx) {
        if (ctx.getRequest() != null && StringUtils.hasText(ctx.getRequest().getOperationType())) {
            return ctx.getRequest().getOperationType();
        }
        Object t = ctx.getExecConfig() != null ? ctx.getExecConfig().get("type") : null;
        return t instanceof String s ? s : null;
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
