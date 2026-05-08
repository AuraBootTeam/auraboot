package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.CommandExecutorUtils;
import com.auraboot.framework.meta.service.impl.CommandFieldMapExecutor;
import com.auraboot.framework.meta.service.impl.CommandSpelEvaluator;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.RecordSnapshotReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * Assert phase: binding-rule assertions, preconditions, and validation rules.
 */
@Slf4j
@Component
@Order(700)
@RequiredArgsConstructor
public class AssertPhase implements CommandPhase {

    private final CommandSpelEvaluator spelEvaluator;
    private final RecordSnapshotReader snapshotReader;
    private final MetaModelService metaModelService;
    private final DynamicDataMapper dynamicDataMapper;
    private final CommandFieldMapExecutor fieldMapExecutor;

    private static final int MAX_PRECONDITION_EXPRESSION_LENGTH = 500;
    private static final java.util.regex.Pattern DANGEROUS_SPEL_PATTERN = java.util.regex.Pattern.compile(
            "T\\s*\\(|new\\s+|getClass|Runtime|exec\\s*\\(|ProcessBuilder|System\\.|Thread\\.",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "assert";
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        var assertRules = ctx.getRulesByType().getOrDefault("assert", Collections.emptyList());
        executeAssertPhase(assertRules, ctx.getPayload());
        executePreconditionsPhase(ctx.getExecConfig(), ctx.getPayload(),
                ctx.getTenantId(), ctx.getCommand(), ctx.getRequest());
        executeValidationPhase(ctx.getExecConfig(), ctx.getPayload(),
                ctx.getTenantId(), ctx.getCommand(), ctx.getRequest());
    }

    // ==================== Inlined delegate methods ====================

    private void executeAssertPhase(List<BindingRule> assertRules, Map<String, Object> payload) {
        for (BindingRule rule : assertRules) {
            if (!StringUtils.hasText(rule.getExpression())) {
                continue;
            }
            EvaluationContext context = spelEvaluator.buildSpelContext(payload);
            Boolean result = spelEvaluator.evaluate(rule.getExpression(), context, Boolean.class);
            if (result == null || !result) {
                String errorMsg = "Assertion failed: " + rule.getExpression();
                throw new ValidationException(ResponseCode.CommonValidationFailed, errorMsg);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void executePreconditionsPhase(Map<String, Object> execConfig, Map<String, Object> payload,
                                            Long tenantId, CommandDefinition command,
                                            CommandExecuteRequest request) {
        if (execConfig == null || !execConfig.containsKey("preconditions")) {
            return;
        }

        List<Map<String, Object>> preconditions = (List<Map<String, Object>>) execConfig.get("preconditions");
        if (preconditions == null) return;

        Map<String, Object> spelPayload = null;

        for (Map<String, Object> precond : preconditions) {
            String message = (String) precond.getOrDefault("message:zh-CN",
                    precond.getOrDefault("message:en", "Precondition failed"));

            // SpEL expression mode
            String expression = (String) precond.get("expression");
            if (StringUtils.hasText(expression)) {
                if (spelPayload == null) {
                    spelPayload = buildPreconditionPayload(payload, tenantId, command, request);
                }
                boolean passed = evaluateSpelPrecondition(expression, spelPayload);
                if (!passed) {
                    throw new ValidationException(ResponseCode.CommonValidationFailed, message);
                }
                continue;
            }

            // Field-operator mode
            String field = (String) precond.get("field");
            String operator = (String) precond.get("operator");
            Object expectedValue = precond.get("value");

            if (field == null || operator == null) continue;

            Object actualValue = payload.get(field);
            if (actualValue == null && request != null && StringUtils.hasText(request.getTargetRecordId())) {
                Map<String, Object> record = snapshotReader.readRecordSnapshot(tenantId, command.getModelCode(), request.getTargetRecordId());
                if (record != null) {
                    actualValue = record.get(field);
                }
            }

            boolean passed = evaluatePrecondition(operator, actualValue, expectedValue);
            if (!passed) {
                throw new ValidationException(ResponseCode.CommonValidationFailed, message);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void executeValidationPhase(Map<String, Object> execConfig, Map<String, Object> payload,
                                         Long tenantId, CommandDefinition command,
                                         CommandExecuteRequest request) {
        if (execConfig == null || !execConfig.containsKey("validation")) {
            return;
        }

        Map<String, Object> validation = (Map<String, Object>) execConfig.get("validation");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) validation.get("rules");
        if (rules == null) return;

        // For UNIQUE_COMPOSITE, merge FIXED_VALUE autoSetFields into a validation payload
        Map<String, Object> validationPayload = new HashMap<>(payload);
        Map<String, Object> autoSetFields = (Map<String, Object>) execConfig.get("autoSetFields");
        if (autoSetFields != null) {
            for (Map.Entry<String, Object> entry : autoSetFields.entrySet()) {
                if (!validationPayload.containsKey(entry.getKey())) {
                    Map<String, Object> config = (Map<String, Object>) entry.getValue();
                    String strat = (String) config.get("strategy");
                    if ("fixed_value".equals(strat) || "default_value".equals(strat)) {
                        validationPayload.put(entry.getKey(), config.get("value"));
                    }
                }
            }
        }

        for (Map<String, Object> rule : rules) {
            String ruleType = (String) rule.get("type");
            switch (ruleType != null ? ruleType : "") {
                case "has_children" -> validateHasChildren(rule, tenantId, request);
                case "unique_composite" -> validateUniqueComposite(rule, validationPayload, tenantId, command, request);
            }
        }
    }

    // ==================== Helper methods ====================

    private boolean evaluatePrecondition(String operator, Object actual, Object expected) {
        return switch (operator.toUpperCase()) {
            case "EQ" -> Objects.equals(String.valueOf(actual), String.valueOf(expected));
            case "NEQ" -> !Objects.equals(String.valueOf(actual), String.valueOf(expected));
            case "IN" -> {
                if (expected instanceof List<?> list) {
                    yield list.stream().anyMatch(v -> Objects.equals(String.valueOf(actual), String.valueOf(v)));
                }
                yield false;
            }
            case "NOT_IN" -> {
                if (expected instanceof List<?> list) {
                    yield list.stream().noneMatch(v -> Objects.equals(String.valueOf(actual), String.valueOf(v)));
                }
                yield true;
            }
            case "NOT_NULL" -> actual != null;
            case "NULL" -> actual == null;
            case "GT", "GE", "LT", "LE" -> compareNumeric(operator.toUpperCase(), actual, expected);
            case "CONTAINS" -> actual != null && String.valueOf(actual).contains(String.valueOf(expected));
            case "NOT_CONTAINS" -> actual == null || !String.valueOf(actual).contains(String.valueOf(expected));
            default -> {
                log.warn("Unknown precondition operator: {}, failing safe", operator);
                yield false;
            }
        };
    }

    private boolean compareNumeric(String op, Object actual, Object expected) {
        if (actual == null || expected == null) return false;
        try {
            double a = Double.parseDouble(String.valueOf(actual));
            double e = Double.parseDouble(String.valueOf(expected));
            return switch (op) {
                case "GT" -> a > e;
                case "GE" -> a >= e;
                case "LT" -> a < e;
                case "LE" -> a <= e;
                default -> false;
            };
        } catch (NumberFormatException ex) {
            int cmp = String.valueOf(actual).compareTo(String.valueOf(expected));
            return switch (op) {
                case "GT" -> cmp > 0;
                case "GE" -> cmp >= 0;
                case "LT" -> cmp < 0;
                case "LE" -> cmp <= 0;
                default -> false;
            };
        }
    }

    private Map<String, Object> buildPreconditionPayload(Map<String, Object> payload,
                                                          Long tenantId, CommandDefinition command,
                                                          CommandExecuteRequest request) {
        Map<String, Object> merged = new HashMap<>(payload);
        if (request != null && StringUtils.hasText(request.getTargetRecordId())) {
            Map<String, Object> record = snapshotReader.readRecordSnapshot(tenantId, command.getModelCode(), request.getTargetRecordId());
            if (record != null) {
                Map<String, Object> result = new HashMap<>(record);
                result.putAll(payload);
                return result;
            }
        }
        return merged;
    }

    private boolean evaluateSpelPrecondition(String expression, Map<String, Object> payload) {
        if (expression.length() > MAX_PRECONDITION_EXPRESSION_LENGTH) {
            log.error("Rejected precondition SpEL expression exceeding max length {}: length={}",
                    MAX_PRECONDITION_EXPRESSION_LENGTH, expression.length());
            return false;
        }
        if (DANGEROUS_SPEL_PATTERN.matcher(expression).find()) {
            log.error("Rejected dangerous precondition SpEL expression: '{}'", expression);
            return false;
        }
        try {
            var context = spelEvaluator.buildSpelContext(payload);
            Boolean result = spelEvaluator.evaluate(expression, context, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Failed to evaluate precondition expression '{}': {}", expression, e.getMessage());
            return false;
        }
    }

    private void validateHasChildren(Map<String, Object> rule, Long tenantId, CommandExecuteRequest request) {
        String childModel = (String) rule.get("childModel");
        String parentField = (String) rule.get("parentField");
        Integer minCount = rule.get("minCount") != null ? ((Number) rule.get("minCount")).intValue() : 1;
        String message = (String) rule.getOrDefault("message:zh-CN",
                rule.getOrDefault("message:en", "Validation failed: child records required"));

        if (childModel == null || parentField == null || request == null || request.getTargetRecordId() == null) {
            return;
        }

        CommandExecutorUtils.validateSqlIdentifier(parentField, "HAS_CHILDREN parentField");

        try {
            String tableName = metaModelService.getTableName(childModel);
            String recordIdStr = request.getTargetRecordId();

            boolean hasDeletedFlag = snapshotReader.hasColumn(tableName, "deleted_flag");
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) as cnt FROM " + tableName
                    + " WHERE " + parentField + " = #{params.parentId}");
            if (hasDeletedFlag) {
                sql.append(" AND deleted_flag = FALSE");
            }

            Map<String, Object> params = new HashMap<>();
            params.put("parentId", recordIdStr);

            List<Map<String, Object>> result = dynamicDataMapper.selectByQuery(sql.toString(), params);
            long count = 0;
            if (result != null && !result.isEmpty()) {
                Object cnt = result.get(0).get("cnt");
                if (cnt instanceof Number) {
                    count = ((Number) cnt).longValue();
                }
            }
            if (count < minCount) {
                throw new ValidationException(ResponseCode.CommonValidationFailed, message);
            }
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("HAS_CHILDREN validation failed: {}", e.getMessage());
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    message + " (validation query error: " + e.getMessage() + ")");
        }
    }

    @SuppressWarnings("unchecked")
    private void validateUniqueComposite(Map<String, Object> rule, Map<String, Object> payload,
                                          Long tenantId, CommandDefinition command,
                                          CommandExecuteRequest request) {
        List<String> fields = (List<String>) rule.get("fields");
        String message = (String) rule.getOrDefault("message:zh-CN",
                rule.getOrDefault("message:en", "Duplicate record exists"));

        if (fields == null || fields.isEmpty() || command.getModelCode() == null) return;

        for (String f : fields) {
            CommandExecutorUtils.validateSqlIdentifier(f, "UNIQUE_COMPOSITE field");
        }

        try {
            String tableName = metaModelService.getTableName(command.getModelCode());
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) as cnt FROM " + tableName
                    + " WHERE 1=1");
            Map<String, Object> params = new HashMap<>();

            ModelDefinition modelDef = metaModelService.getModelDefinition(command.getModelCode()).orElse(null);

            boolean hasNonNullField = false;
            for (int i = 0; i < fields.size(); i++) {
                String fieldCode = fields.get(i);
                Object value = payload.get(fieldCode);
                String paramKey = "f" + i;
                if (value == null) {
                    sql.append(" AND (").append(fieldCode).append(" IS NULL)");
                } else {
                    if (modelDef != null && modelDef.getFields() != null) {
                        for (FieldDefinition fd : modelDef.getFields()) {
                            if (fieldCode.equals(fd.getCode()) && fd.getDataType() != null) {
                                value = fieldMapExecutor.convertFieldValue(fd.getDataType(), value);
                                break;
                            }
                        }
                    }
                    sql.append(" AND ").append(fieldCode).append(" = #{params.").append(paramKey).append("}");
                    params.put(paramKey, value);
                    hasNonNullField = true;
                }
            }

            if (snapshotReader.hasColumn(tableName, "deleted_flag")) {
                sql.append(" AND deleted_flag = FALSE");
            }

            if (request != null && StringUtils.hasText(request.getTargetRecordId())) {
                var excludeEntry = CommandExecutorUtils.resolveRecordIdColumn(request.getTargetRecordId());
                sql.append(" AND ").append(excludeEntry.getKey()).append(" != #{params.excludeId}");
                params.put("excludeId", excludeEntry.getValue());
            }

            if (!hasNonNullField) {
                log.debug("UNIQUE_COMPOSITE: all fields are NULL, skipping validation");
                return;
            }

            List<Map<String, Object>> result = dynamicDataMapper.selectByQuery(sql.toString(), params);
            long count = 0;
            if (result != null && !result.isEmpty()) {
                Object cnt = result.get(0).get("cnt");
                if (cnt instanceof Number) {
                    count = ((Number) cnt).longValue();
                }
            }

            if (count > 0) {
                throw new ValidationException(ResponseCode.CommonValidationFailed, message);
            }
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("UNIQUE_COMPOSITE validation failed unexpectedly: {}", e.getMessage(), e);
            throw new BusinessException(ResponseCode.BadParam,
                    "Uniqueness validation failed: " + e.getMessage());
        }
    }
}
