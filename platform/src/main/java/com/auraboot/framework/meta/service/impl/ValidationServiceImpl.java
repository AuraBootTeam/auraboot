package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.service.ValidationService;
import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.exception.MetaServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.DataBindingMethodResolver;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 数据验证服务实现
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValidationServiceImpl extends BaseMetaService implements ValidationService {

    private final DynamicDataMapper dynamicDataMapper;
    private final SpelExpressionParser spelParser = new SpelExpressionParser();

    @Override
    public ValidationResult validateData(ModelDefinition modelDefinition, Map<String, Object> data, ValidationContext context) {
        if (modelDefinition == null) {
            throw new MetaServiceException("Model definition cannot be null");
        }
        if (data == null) {
            data = Collections.emptyMap();
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> validFields = new ArrayList<>();

        // 验证每个字段
        if (modelDefinition.getFields() != null) {
            for (FieldDefinition field : modelDefinition.getFields()) {
                FieldValidationResult fieldResult = validateField(field, data.get(field.getCode()), context);
                
                if (fieldResult.isValid()) {
                    validFields.add(field.getCode());
                } else {
                    errors.addAll(fieldResult.getErrors());
                }
                
                warnings.addAll(fieldResult.getWarnings());
            }
        }

        // 验证必填字段
        validateRequiredFields(modelDefinition, data, context, errors);

        // 验证唯一性约束
        ValidationResult uniquenessResult = validateUniqueness(modelDefinition, data, context);
        errors.addAll(uniquenessResult.getErrors());
        warnings.addAll(uniquenessResult.getWarnings());

        // 验证关联关系
        ValidationResult relationResult = validateRelations(modelDefinition, data, context);
        errors.addAll(relationResult.getErrors());
        warnings.addAll(relationResult.getWarnings());

        return ValidationResult.builder()
                .valid(errors.isEmpty())
                .errors(errors)
                .warnings(warnings)
                .validFields(validFields)
                .build();
    }

    @Override
    public FieldValidationResult validateField(FieldDefinition fieldDefinition, Object value, ValidationContext context) {
        if (fieldDefinition == null) {
            return FieldValidationResult.invalid("Field definition cannot be null");
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 空值检查
        if (value == null || (value instanceof String && ((String) value).trim().isEmpty())) {
            if (fieldDefinition.isRequired() && context != ValidationContext.UPDATE) {
                errors.add("Field '" + fieldDefinition.getName() + "' is required");
            }
            return FieldValidationResult.builder()
                    .valid(errors.isEmpty())
                    .errors(errors)
                    .warnings(warnings)
                    .build();
        }

        // 数据类型验证
        validateDataType(fieldDefinition, value, errors);

        // 长度验证
        validateLength(fieldDefinition, value, errors, warnings);

        // 格式验证
        validateFormat(fieldDefinition, value, errors);

        // 范围验证
        validateRange(fieldDefinition, value, errors);

        // 自定义验证规则
        if (fieldDefinition.getValidationRules() != null) {
            for (ValidationRule rule : fieldDefinition.getValidationRules()) {
                validateCustomRule(rule, value, errors, warnings);
            }
        }

        return FieldValidationResult.builder()
                .valid(errors.isEmpty())
                .errors(errors)
                .warnings(warnings)
                .build();
    }

    @Override
    public ValidationResult validateBusinessRules(ModelDefinition modelDefinition, Map<String, Object> data, 
                                                BusinessRuleSet businessRules) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (businessRules != null && businessRules.getRules() != null) {
            for (BusinessRule rule : businessRules.getRules()) {
                try {
                    boolean ruleResult = evaluateBusinessRule(rule, data);
                    if (!ruleResult) {
                        if (rule.getSeverity() == BusinessRule.Severity.ERROR) {
                            errors.add(rule.getMessage());
                        } else {
                            warnings.add(rule.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Error evaluating business rule: {}", rule.getName(), e);
                    warnings.add("Business rule evaluation failed: " + rule.getName());
                }
            }
        }

        return ValidationResult.builder()
                .valid(errors.isEmpty())
                .errors(errors)
                .warnings(warnings)
                .validFields(Collections.emptyList())
                .build();
    }

    @Override
    public ValidationResult validateUniqueness(ModelDefinition modelDefinition, Map<String, Object> data,
                                             ValidationContext context) {
        List<String> errors = new ArrayList<>();

        if (modelDefinition.getFields() == null || modelDefinition.getTableName() == null) {
            return ValidationResult.builder()
                    .valid(true)
                    .errors(errors)
                    .warnings(Collections.emptyList())
                    .validFields(Collections.emptyList())
                    .build();
        }

        Long tenantId = MetaContext.getCurrentTenantId();

        for (FieldDefinition field : modelDefinition.getFields()) {
            if (!Boolean.TRUE.equals(field.getUnique())) {
                continue;
            }

            Object value = data.get(field.getCode());
            if (value == null) {
                continue;
            }

            // Query database to check if value already exists
            SqlSafetyUtils.validateIdentifier(modelDefinition.getTableName(), "unique check tableName");
            SqlSafetyUtils.validateIdentifier(field.getColumnName(), "unique check columnName");
            String sql = "SELECT COUNT(*) as cnt FROM " + modelDefinition.getTableName()
                    + " WHERE " + field.getColumnName() + " = #{params.value}"
                    + " AND tenant_id = #{params.tenantId}";
            Map<String, Object> params = new HashMap<>();
            params.put("value", value);
            params.put("tenantId", tenantId);

            // For UPDATE context, exclude the current record
            if (context == ValidationContext.UPDATE && data.containsKey("id")) {
                sql += " AND id != #{params.excludeId}";
                params.put("excludeId", data.get("id"));
            }

            try {
                List<Map<String, Object>> results = dynamicDataMapper.selectByQuery(sql, params);
                if (!results.isEmpty()) {
                    long count = ((Number) results.get(0).get("cnt")).longValue();
                    if (count > 0) {
                        errors.add("Field '" + field.getName() + "' value '" + value + "' already exists");
                    }
                }
            } catch (Exception e) {
                log.warn("Uniqueness check failed for field {}: {}", field.getCode(), e.getMessage());
            }
        }

        return ValidationResult.builder()
                .valid(errors.isEmpty())
                .errors(errors)
                .warnings(Collections.emptyList())
                .validFields(Collections.emptyList())
                .build();
    }

    @Override
    public ValidationResult validateRelations(ModelDefinition modelDefinition, Map<String, Object> data,
                                            ValidationContext context) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (modelDefinition.getRelations() != null) {
            Long tenantId = MetaContext.getCurrentTenantId();

            for (RelationDefinition relation : modelDefinition.getRelations()) {
                Object relationValue = data.get(relation.getSourceField());

                // Check required relation has a value
                if (relationValue == null && relation.isRequired() && context != ValidationContext.UPDATE) {
                    errors.add("Required relation '" + relation.getName() + "' (field: " + relation.getSourceField() + ") is missing");
                    continue;
                }

                // Verify referenced record exists in target table
                if (relationValue != null && relation.getTargetTable() != null && relation.getTargetField() != null) {
                    SqlSafetyUtils.validateIdentifier(relation.getTargetTable(), "relation targetTable");
                    SqlSafetyUtils.validateIdentifier(relation.getTargetField(), "relation targetField");
                    String sql = "SELECT COUNT(*) as cnt FROM " + relation.getTargetTable()
                            + " WHERE " + relation.getTargetField() + " = #{params.refValue}"
                            + " AND tenant_id = #{params.tenantId}";
                    Map<String, Object> params = new HashMap<>();
                    params.put("refValue", relationValue);
                    params.put("tenantId", tenantId);

                    try {
                        List<Map<String, Object>> results = dynamicDataMapper.selectByQuery(sql, params);
                        if (!results.isEmpty()) {
                            long count = ((Number) results.get(0).get("cnt")).longValue();
                            if (count == 0) {
                                errors.add("Related record not found for relation '" + relation.getName()
                                        + "': " + relation.getTargetTable() + "." + relation.getTargetField()
                                        + " = " + relationValue);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Relation validation failed for {}: {}", relation.getName(), e.getMessage());
                        warnings.add("Could not verify relation '" + relation.getName() + "': " + e.getMessage());
                    }
                }
            }
        }

        return ValidationResult.builder()
                .valid(errors.isEmpty())
                .errors(errors)
                .warnings(warnings)
                .validFields(Collections.emptyList())
                .build();
    }

    @Override
    public ValidationResult validateDataIntegrity(ModelDefinition modelDefinition, Map<String, Object> data) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 验证数据完整性
        if (data == null || data.isEmpty()) {
            errors.add("Data cannot be empty");
            return ValidationResult.builder()
                    .valid(false)
                    .errors(errors)
                    .warnings(warnings)
                    .validFields(Collections.emptyList())
                    .build();
        }

        // 验证是否包含未定义的字段
        Set<String> definedFields = new HashSet<>();
        if (modelDefinition.getFields() != null) {
            modelDefinition.getFields().forEach(field -> definedFields.add(field.getCode()));
        }

        for (String fieldName : data.keySet()) {
            if (!definedFields.contains(fieldName) && !isSystemField(fieldName)) {
                warnings.add("Undefined field: " + fieldName);
            }
        }

        return ValidationResult.builder()
                .valid(errors.isEmpty())
                .errors(errors)
                .warnings(warnings)
                .validFields(Collections.emptyList())
                .build();
    }

    @Override
    public ValidationResult validateImmutability(ModelDefinition modelDefinition, Map<String, Object> data,
                                                 Map<String, Object> existingRecord) {
        List<String> errors = new ArrayList<>();

        if (modelDefinition == null || modelDefinition.getFields() == null
                || data == null || data.isEmpty() || existingRecord == null) {
            return ValidationResult.builder()
                    .valid(true)
                    .errors(errors)
                    .warnings(new ArrayList<>())
                    .validFields(Collections.emptyList())
                    .build();
        }

        for (FieldDefinition field : modelDefinition.getFields()) {
            String code = field.getCode();
            // Only fields actually being written can violate an immutability lock.
            if (code == null || !data.containsKey(code)) {
                continue;
            }
            // Re-submitting the same value is not a change. This matters because the
            // "read a row, change a few fields, write the whole thing back" shape is
            // common; without it, every full-row write would trip every lock.
            if (!valueChanges(existingRecord.get(code), data.get(code))) {
                continue;
            }

            String label = field.getName() != null ? field.getName() : code;

            if (field.isImmutable()) {
                errors.add("Field '" + label + "' is immutable and cannot be changed");
                continue;
            }

            FieldDefinition.ImmutableWhen when = field.getImmutableWhen();
            if (when == null || when.getField() == null || when.getIn() == null || when.getIn().isEmpty()) {
                continue;
            }
            // The lock is decided by the record's CURRENT state, not by whatever the
            // caller is trying to set the state to in this same payload.
            Object currentState = existingRecord.get(when.getField());
            if (currentState == null) {
                continue;
            }
            String rendered = String.valueOf(currentState);
            // Case-insensitive on purpose. An invariant that silently fails to engage because the
            // declaration says "APPROVED" and the column holds "approved" is worse than one that
            // engages slightly too eagerly: it reads as configured while protecting nothing. Two
            // states of the same model differing only in case would be a modelling error anyway.
            boolean locked = when.getIn().stream().anyMatch(v -> v != null && v.equalsIgnoreCase(rendered));
            if (locked) {
                errors.add("Field '" + label + "' cannot be changed while "
                        + when.getField() + " is '" + rendered + "'");
            }
        }

        return ValidationResult.builder()
                .valid(errors.isEmpty())
                .errors(errors)
                .warnings(new ArrayList<>())
                .validFields(Collections.emptyList())
                .build();
    }

    /**
     * Whether {@code incoming} actually differs from what is stored.
     *
     * <p>Types routinely differ across the JDBC boundary — a DATE column reads back as
     * {@code java.sql.Date} while the payload carries {@code "2026-07-24"}, an integer
     * column reads back as {@code Integer} while JSON supplies {@code Long}. Comparing the
     * rendered form before declaring a change keeps an unchanged round-trip from tripping
     * a lock it never touched.</p>
     */
    static boolean valueChanges(Object current, Object incoming) {
        if (current == null && incoming == null) {
            return false;
        }
        if (current == null || incoming == null) {
            return true;
        }
        if (current.equals(incoming)) {
            return false;
        }
        return !String.valueOf(current).equals(String.valueOf(incoming));
    }

    // 私有辅助方法
    private void validateRequiredFields(ModelDefinition modelDefinition, Map<String, Object> data,
                                      ValidationContext context, List<String> errors) {
        if (modelDefinition.getFields() == null) {
            return;
        }

        for (FieldDefinition field : modelDefinition.getFields()) {
            if (field.isRequired() && (context != ValidationContext.UPDATE || data.containsKey(field.getCode()))) {
                Object value = data.get(field.getCode());
                if (value == null || (value instanceof String && ((String) value).trim().isEmpty())) {
                    errors.add("Required field '" + field.getName() + "' is missing");
                }
            }
        }
    }

    private void validateDataType(FieldDefinition fieldDefinition, Object value, List<String> errors) {
        // Use the data type directly from field definition if dataTypeMapping is not available
        String dataType = fieldDefinition.getDataType();
        if (dataType == null) {
            return;
        }

        String javaType = dataType.toLowerCase();
        
        try {
            switch (javaType) {
                case "string":
                case "text":
                    if (!(value instanceof String)) {
                        errors.add("Field '" + fieldDefinition.getName() + "' must be a string");
                    }
                    break;
                case "integer":
                case "int":
                    if (!(value instanceof Integer) && !isNumeric(value.toString())) {
                        errors.add("Field '" + fieldDefinition.getName() + "' must be an integer");
                    }
                    break;
                case "long":
                    if (!(value instanceof Long) && !isNumeric(value.toString())) {
                        errors.add("Field '" + fieldDefinition.getName() + "' must be a long");
                    }
                    break;
                case "double":
                case "decimal":
                case "bigdecimal":
                    if (!(value instanceof Number) && !isNumeric(value.toString())) {
                        errors.add("Field '" + fieldDefinition.getName() + "' must be a number");
                    }
                    break;
                case "boolean":
                    if (!(value instanceof Boolean) && !isBooleanString(value.toString())) {
                        errors.add("Field '" + fieldDefinition.getName() + "' must be a boolean");
                    }
                    break;
                case "date":
                case "localdate":
                    if (!(value instanceof LocalDate)) {
                        errors.add("Field '" + fieldDefinition.getName() + "' must be a valid date (LocalDate)");
                    }
                    break;
                case "datetime":
                case "timestamp":
                case "localdatetime":
                    if (!(value instanceof Instant)) {
                        errors.add("Field '" + fieldDefinition.getName() + "' must be a valid datetime (Instant)");
                    }
                    break;
            }
        } catch (Exception e) {
            errors.add("Data type validation failed for field '" + fieldDefinition.getName() + "'");
        }
    }

    private void validateLength(FieldDefinition fieldDefinition, Object value, List<String> errors, List<String> warnings) {
        if (!(value instanceof String)) {
            return;
        }

        String stringValue = (String) value;
        
        if (fieldDefinition.getMaxLength() != null && stringValue.length() > fieldDefinition.getMaxLength()) {
            errors.add("Field '" + fieldDefinition.getName() + "' exceeds maximum length of " + fieldDefinition.getMaxLength());
        }
        
        if (fieldDefinition.getMinLength() != null && stringValue.length() < fieldDefinition.getMinLength()) {
            errors.add("Field '" + fieldDefinition.getName() + "' is below minimum length of " + fieldDefinition.getMinLength());
        }
    }

    private void validateFormat(FieldDefinition fieldDefinition, Object value, List<String> errors) {
        if (!(value instanceof String) || fieldDefinition.getFormat() == null) {
            return;
        }

        String stringValue = (String) value;
        String format = fieldDefinition.getFormat();

        // Skip date/time format patterns (e.g. YYYY-MM-DD, HH:mm:ss) — these are not regex
        String dataType = fieldDefinition.getDataType();
        if (dataType != null && (dataType.equals("date") || dataType.equals("datetime") || dataType.equals("time"))) {
            return;
        }

        try {
            Pattern pattern = Pattern.compile(format);
            if (!matchesWithTimeout(pattern, stringValue)) {
                errors.add("Field '" + fieldDefinition.getName() + "' does not match required format");
            }
        } catch (RegexTimeoutException e) {
            // A runaway match (likely ReDoS: an admin-configured catastrophic-backtracking
            // regex + crafted input) is bounded and treated as a validation failure instead
            // of hanging the request thread.
            log.warn("Regex match timed out for field {} (possible ReDoS), pattern={}",
                    fieldDefinition.getCode(), format);
            errors.add("Field '" + fieldDefinition.getName()
                    + "' could not be validated (format pattern is too complex)");
        } catch (Exception e) {
            log.warn("Invalid regex pattern for field {}: {}", fieldDefinition.getCode(), format);
        }
    }

    /** Max wall-clock a single field-format regex match may run before being aborted. */
    private static final long REGEX_MATCH_TIMEOUT_MS = 200L;

    /**
     * Match {@code input} against {@code pattern} with a hard time bound to defend against
     * ReDoS (catastrophic backtracking). The input is wrapped in a {@link TimeoutCharSequence}
     * whose {@code charAt} throws once the deadline passes; regex backtracking reads chars
     * repeatedly, so a runaway match is interrupted within the bound. Package-private for
     * direct unit testing of the boundary.
     */
    static boolean matchesWithTimeout(Pattern pattern, String input) {
        long deadlineNanos = System.nanoTime() + REGEX_MATCH_TIMEOUT_MS * 1_000_000L;
        return pattern.matcher(new TimeoutCharSequence(input, deadlineNanos)).matches();
    }

    /** Signals that a regex match exceeded {@link #REGEX_MATCH_TIMEOUT_MS}. */
    static final class RegexTimeoutException extends RuntimeException {
        RegexTimeoutException() {
            super("regex match timed out");
        }
    }

    /** CharSequence that aborts (via {@link RegexTimeoutException}) once a deadline passes. */
    private static final class TimeoutCharSequence implements CharSequence {
        private final CharSequence inner;
        private final long deadlineNanos;

        TimeoutCharSequence(CharSequence inner, long deadlineNanos) {
            this.inner = inner;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadlineNanos) {
                throw new RegexTimeoutException();
            }
            return inner.charAt(index);
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new TimeoutCharSequence(inner.subSequence(start, end), deadlineNanos);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }

    private void validateRange(FieldDefinition fieldDefinition, Object value, List<String> errors) {
        if (!(value instanceof Number)) {
            return;
        }

        BigDecimal numValue = new BigDecimal(value.toString());
        
        if (fieldDefinition.getMinValue() != null) {
            BigDecimal minValue = new BigDecimal(fieldDefinition.getMinValue().toString());
            if (numValue.compareTo(minValue) < 0) {
                errors.add("Field '" + fieldDefinition.getName() + "' is below minimum value of " + fieldDefinition.getMinValue());
            }
        }
        
        if (fieldDefinition.getMaxValue() != null) {
            BigDecimal maxValue = new BigDecimal(fieldDefinition.getMaxValue().toString());
            if (numValue.compareTo(maxValue) > 0) {
                errors.add("Field '" + fieldDefinition.getName() + "' exceeds maximum value of " + fieldDefinition.getMaxValue());
            }
        }
    }

    private void validateCustomRule(ValidationRule rule, Object value, List<String> errors, List<String> warnings) {
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) {
            return;
        }

        try {
            String expression = rule.getExpression();
            if (expression == null || expression.isBlank()) {
                return;
            }

            // Evaluate using SpEL
            SimpleEvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding()
                    .withMethodResolvers(DataBindingMethodResolver.forInstanceMethodInvocation())
                    .build();
            ctx.setVariable("value", value);
            if (rule.getParameters() != null) {
                rule.getParameters().forEach(ctx::setVariable);
            }

            Expression expr = spelParser.parseExpression(expression);
            Boolean result = expr.getValue(ctx, Boolean.class);

            if (!Boolean.TRUE.equals(result)) {
                String errorMessage = rule.getErrorMessage() != null
                        ? rule.getErrorMessage()
                        : "Custom validation rule '" + rule.getName() + "' failed";
                errors.add(errorMessage);
            }
        } catch (Exception e) {
            log.warn("Custom validation rule execution failed: {}", rule.getName(), e);
            warnings.add("Custom validation rule failed: " + rule.getName());
        }
    }

    private boolean evaluateBusinessRule(BusinessRule rule, Map<String, Object> data) {
        if (rule == null || !rule.isEnabled()) {
            return true;
        }

        String expression = rule.getExpression();
        if (expression == null || expression.isBlank()) {
            return true;
        }

        try {
            SimpleEvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding()
                    .withMethodResolvers(DataBindingMethodResolver.forInstanceMethodInvocation())
                    .build();
            // Set all data fields as variables accessible in the expression
            if (data != null) {
                data.forEach(ctx::setVariable);
            }

            Expression expr = spelParser.parseExpression(expression);
            Boolean result = expr.getValue(ctx, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Business rule evaluation failed for '{}': {}", rule.getName(), e.getMessage());
            return false;
        }
    }

    private boolean isSystemField(String fieldName) {
        return SystemFieldConstants.VALIDATION_SYSTEM.contains(fieldName);
    }

    private boolean isNumeric(String str) {
        try {
            Double.parseDouble(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isBooleanString(String str) {
        return "true".equalsIgnoreCase(str) || "false".equalsIgnoreCase(str) ||
               "1".equals(str) || "0".equals(str);
    }

    // ==================== 增强验证方法实现 ====================

    @Override
    public ValidationResult validateData(ModelDefinition modelDefinition, Map<String, Object> data,
                                        ValidationContext context, ValidationMode mode) {
        // 首先执行基础验证
        ValidationResult result = validateData(modelDefinition, data, context);

        // 根据模式进行额外处理
        if (mode == ValidationMode.TENANT_ISOLATED || mode == ValidationMode.STRICT) {
            // 验证租户隔离
            try {
                validateTenantIsolation(data);
            } catch (com.auraboot.framework.meta.exception.ValidationException e) {
                result.getErrors().add(e.getMessage());
                result.setValid(false);
            }
        }

        // STRICT模式下，验证失败抛出异常
        if (mode == ValidationMode.STRICT && !result.getValid()) {
            throw new com.auraboot.framework.meta.exception.ValidationException(
                "Validation failed: " + String.join(", ", result.getErrors()));
        }

        return result;
    }

    @Override
    public void validateTenantIsolation(Map<String, Object> data) {
        // 检查租户上下文是否存在
        if (!com.auraboot.framework.application.tenant.MetaContext.exists()) {
            throw new com.auraboot.framework.meta.exception.ValidationException(
                "Tenant context is required for data validation");
        }

        Long tenantId = com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new com.auraboot.framework.meta.exception.ValidationException(
                "Tenant ID is required in the current context");
        }

        // 如果数据中包含tenant_id字段，验证其与当前租户一致
        if (data.containsKey("tenant_id")) {
            Object dataTenantId = data.get("tenant_id");
            if (dataTenantId != null && !tenantId.equals(dataTenantId)) {
                throw new com.auraboot.framework.meta.exception.ValidationException(
                    "Data tenant_id does not match current tenant context");
            }
        }
    }
}
