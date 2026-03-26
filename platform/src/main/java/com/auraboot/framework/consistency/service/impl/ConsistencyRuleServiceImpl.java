package com.auraboot.framework.consistency.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.consistency.dao.mapper.ConsistencyRuleMapper;
import com.auraboot.framework.consistency.dto.*;
import com.auraboot.framework.consistency.entity.ConsistencyRule;
import com.auraboot.framework.consistency.exception.ConsistencyViolationException;
import com.auraboot.framework.consistency.service.ConsistencyRuleService;
import com.auraboot.framework.meta.dto.PaginationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Implementation of consistency rule service.
 * Core engine for cross-document constraint validation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsistencyRuleServiceImpl implements ConsistencyRuleService {

    private final ConsistencyRuleMapper consistencyRuleMapper;

    // Whitelist pattern: only alphanumeric and underscore allowed for table/column names
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private static final Set<String> VALID_AGG_FUNCS = Set.of("sum", "count", "max", "min", "avg");
    private static final Set<String> VALID_OPERATORS = Set.of("LE", "LT", "EQ", "GE", "GT", "NE");

    @Override
    public PaginationResult<ConsistencyRuleResponse> listRules(String sourceModel, int page, int size) {
        QueryWrapper<ConsistencyRule> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", false);
        if (StringUtils.hasText(sourceModel)) {
            wrapper.eq("source_model", sourceModel);
        }
        wrapper.orderByDesc("created_at");

        Page<ConsistencyRule> pageObj = new Page<>(page, size);
        Page<ConsistencyRule> result = consistencyRuleMapper.selectPage(pageObj, wrapper);

        List<ConsistencyRuleResponse> records = result.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return PaginationResult.of(records, result.getTotal(), page, size);
    }

    @Override
    public ConsistencyRuleResponse getRuleById(Long id) {
        ConsistencyRule rule = consistencyRuleMapper.selectById(id);
        if (rule == null || Boolean.TRUE.equals(rule.getDeletedFlag())) {
            return null;
        }
        return toResponse(rule);
    }

    @Override
    public ConsistencyRuleResponse createRule(ConsistencyRuleRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();

        // Check for duplicate code
        ConsistencyRule existing = consistencyRuleMapper.selectByCode(request.getCode(), tenantId);
        if (existing != null) {
            throw new IllegalArgumentException("Consistency rule code already exists: " + request.getCode());
        }

        ConsistencyRule rule = new ConsistencyRule();
        rule.setPid(UniqueIdGenerator.generate());
        rule.setTenantId(tenantId);
        rule.setCode(request.getCode());
        rule.setName(request.getName());
        rule.setRuleType(request.getRuleType());
        rule.setSeverity(request.getSeverity());
        rule.setSourceModel(request.getSourceModel());
        rule.setSourceField(request.getSourceField());
        rule.setTargetModel(request.getTargetModel());
        rule.setTargetField(request.getTargetField());
        rule.setLinkField(request.getLinkField());
        rule.setAggregation(request.getAggregation());
        rule.setOperator(request.getOperator());
        rule.setMessageTemplate(request.getMessageTemplate());
        rule.setEnabled(request.getEnabled());
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        rule.setDeletedFlag(false);

        consistencyRuleMapper.insert(rule);
        return toResponse(rule);
    }

    @Override
    public ConsistencyRuleResponse updateRule(Long id, ConsistencyRuleRequest request) {
        ConsistencyRule rule = consistencyRuleMapper.selectById(id);
        if (rule == null || Boolean.TRUE.equals(rule.getDeletedFlag())) {
            throw new IllegalArgumentException("Consistency rule not found: " + id);
        }

        rule.setCode(request.getCode());
        rule.setName(request.getName());
        rule.setRuleType(request.getRuleType());
        rule.setSeverity(request.getSeverity());
        rule.setSourceModel(request.getSourceModel());
        rule.setSourceField(request.getSourceField());
        rule.setTargetModel(request.getTargetModel());
        rule.setTargetField(request.getTargetField());
        rule.setLinkField(request.getLinkField());
        rule.setAggregation(request.getAggregation());
        rule.setOperator(request.getOperator());
        rule.setMessageTemplate(request.getMessageTemplate());
        rule.setEnabled(request.getEnabled());
        rule.setUpdatedAt(LocalDateTime.now());

        consistencyRuleMapper.updateById(rule);
        return toResponse(rule);
    }

    @Override
    public boolean deleteRule(Long id) {
        ConsistencyRule rule = consistencyRuleMapper.selectById(id);
        if (rule == null) {
            return false;
        }
        // Use deleteById which triggers MyBatis Plus logic-delete
        // (UPDATE SET deleted_flag=true)
        consistencyRuleMapper.deleteById(id);
        return true;
    }

    @Override
    public List<ConsistencyViolation> validate(String modelCode, String recordId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<ConsistencyRule> rules = consistencyRuleMapper.selectEnabledBySourceModel(modelCode, tenantId);

        List<ConsistencyViolation> violations = new ArrayList<>();
        for (ConsistencyRule rule : rules) {
            ConsistencyViolation violation = evaluateRule(rule, recordId, tenantId);
            if (violation != null) {
                violations.add(violation);
            }
        }
        return violations;
    }

    @Override
    public void validateAndThrow(String sourceModel, String linkFieldValue, Long tenantId) {
        List<ConsistencyRule> rules = consistencyRuleMapper.selectEnabledBySourceModel(sourceModel, tenantId);
        if (rules.isEmpty()) {
            return;
        }

        List<ConsistencyViolation> violations = new ArrayList<>();
        for (ConsistencyRule rule : rules) {
            ConsistencyViolation violation = evaluateRuleByLink(rule, linkFieldValue, tenantId);
            if (violation != null && "error".equals(violation.getSeverity())) {
                violations.add(violation);
            }
        }

        if (!violations.isEmpty()) {
            throw new ConsistencyViolationException(violations);
        }
    }

    /**
     * Evaluate a single rule for a specific record.
     * The recordId is a row_id that identifies the source record;
     * the linkField value is read from this record to find the target.
     */
    private ConsistencyViolation evaluateRule(ConsistencyRule rule, String recordId, Long tenantId) {
        try {
            validateIdentifiers(rule);

            String sourceTable = resolveTableName(rule.getSourceModel());
            String targetTable = resolveTableName(rule.getTargetModel());

            // Get link field value from the source record
            BigDecimal linkVal = consistencyRuleMapper.getTargetFieldValue(
                    rule.getLinkField(), sourceTable, recordId, tenantId);

            if (linkVal == null) {
                // Cannot evaluate — link field not present
                return null;
            }

            String linkValue = linkVal.toPlainString();
            return doEvaluate(rule, sourceTable, targetTable, linkValue, tenantId);
        } catch (Exception e) {
            log.error("Failed to evaluate consistency rule {}: {}", rule.getCode(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Evaluate a rule by link field value directly (used in command pipeline).
     */
    private ConsistencyViolation evaluateRuleByLink(ConsistencyRule rule, String linkFieldValue, Long tenantId) {
        try {
            validateIdentifiers(rule);

            String sourceTable = resolveTableName(rule.getSourceModel());
            String targetTable = resolveTableName(rule.getTargetModel());

            return doEvaluate(rule, sourceTable, targetTable, linkFieldValue, tenantId);
        } catch (Exception e) {
            log.error("Failed to evaluate consistency rule {}: {}", rule.getCode(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Core evaluation logic:
     * 1. Aggregate source field by link
     * 2. Get target field value
     * 3. Compare using operator
     */
    private ConsistencyViolation doEvaluate(
            ConsistencyRule rule,
            String sourceTable,
            String targetTable,
            String linkFieldValue,
            Long tenantId) {

        // Step 1: Aggregate source values
        BigDecimal sourceSum = consistencyRuleMapper.aggregateSourceField(
                rule.getAggregation(),
                rule.getSourceField(),
                sourceTable,
                rule.getLinkField(),
                linkFieldValue,
                tenantId);

        // Step 2: Get target value
        BigDecimal targetValue = consistencyRuleMapper.getTargetFieldValue(
                rule.getTargetField(),
                targetTable,
                linkFieldValue,
                tenantId);

        if (targetValue == null) {
            log.warn("Target record not found for rule {}, linkValue={}", rule.getCode(), linkFieldValue);
            return null;
        }

        // Step 3: Compare
        boolean satisfied = compare(sourceSum, targetValue, rule.getOperator());
        if (satisfied) {
            return null; // Rule passed
        }

        // Build violation
        String message = buildViolationMessage(rule, sourceSum, targetValue);
        ConsistencyViolation violation = new ConsistencyViolation();
        violation.setRuleCode(rule.getCode());
        violation.setRuleName(rule.getName());
        violation.setSeverity(rule.getSeverity());
        violation.setMessage(message);
        violation.setSourceModel(rule.getSourceModel());
        violation.setTargetModel(rule.getTargetModel());
        violation.setSourceAggregatedValue(sourceSum);
        violation.setTargetValue(targetValue);
        return violation;
    }

    /**
     * Compare two values using the specified operator.
     */
    private boolean compare(BigDecimal source, BigDecimal target, String operator) {
        int cmp = source.compareTo(target);
        return switch (operator) {
            case "LE" -> cmp <= 0;
            case "LT" -> cmp < 0;
            case "EQ" -> cmp == 0;
            case "GE" -> cmp >= 0;
            case "GT" -> cmp > 0;
            case "NE" -> cmp != 0;
            default -> throw new IllegalArgumentException("Unsupported operator: " + operator);
        };
    }

    /**
     * Build human-readable violation message.
     */
    private String buildViolationMessage(ConsistencyRule rule, BigDecimal sourceSum, BigDecimal targetValue) {
        String template = rule.getMessageTemplate();
        if (!StringUtils.hasText(template)) {
            template = "Consistency violation: {sourceSum} does not satisfy {operator} {targetValue} " +
                    "(rule: {ruleCode})";
        }
        return template
                .replace("{sourceSum}", sourceSum.toPlainString())
                .replace("{targetValue}", targetValue.toPlainString())
                .replace("{operator}", rule.getOperator())
                .replace("{ruleCode}", rule.getCode())
                .replace("{ruleName}", rule.getName() != null ? rule.getName() : rule.getCode());
    }

    /**
     * Resolve model code to actual database table name.
     * In this system, dynamic data is stored in mt_ prefixed tables.
     */
    private String resolveTableName(String modelCode) {
        return "mt_" + modelCode;
    }

    /**
     * Validate that all identifiers in the rule are safe (prevent SQL injection).
     */
    private void validateIdentifiers(ConsistencyRule rule) {
        validateIdentifier(rule.getSourceField(), "sourceField");
        validateIdentifier(rule.getTargetField(), "targetField");
        validateIdentifier(rule.getLinkField(), "linkField");
        validateIdentifier(rule.getSourceModel(), "sourceModel");
        validateIdentifier(rule.getTargetModel(), "targetModel");

        if (!VALID_AGG_FUNCS.contains(rule.getAggregation())) {
            throw new IllegalArgumentException("Invalid aggregation function: " + rule.getAggregation());
        }
        if (!VALID_OPERATORS.contains(rule.getOperator())) {
            throw new IllegalArgumentException("Invalid operator: " + rule.getOperator());
        }
    }

    private void validateIdentifier(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
        if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " contains invalid characters: " + value);
        }
    }

    @Override
    public List<ConsistencyViolation> validateBatch(String modelCode, List<String> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) return List.of();
        List<ConsistencyViolation> all = new ArrayList<>();
        for (String recordId : recordIds) {
            all.addAll(validate(modelCode, recordId));
        }
        return all;
    }

    @Override
    public List<ConsistencyViolation> validateForPipeline(String modelCode,
            java.util.Map<String, Object> payload,
            java.util.Map<String, Object> fieldMapResults,
            Long tenantId) {
        // Extract recordId from payload or fieldMapResults
        Object recordIdRaw = payload != null ? payload.get("id") : null;
        if (recordIdRaw == null && fieldMapResults != null) recordIdRaw = fieldMapResults.get("id");
        if (recordIdRaw == null) return List.of();
        return validate(modelCode, String.valueOf(recordIdRaw));
    }

    private ConsistencyRuleResponse toResponse(ConsistencyRule rule) {
        ConsistencyRuleResponse resp = new ConsistencyRuleResponse();
        resp.setId(rule.getId());
        resp.setPid(rule.getPid());
        resp.setCode(rule.getCode());
        resp.setName(rule.getName());
        resp.setRuleType(rule.getRuleType());
        resp.setSeverity(rule.getSeverity());
        resp.setSourceModel(rule.getSourceModel());
        resp.setSourceField(rule.getSourceField());
        resp.setTargetModel(rule.getTargetModel());
        resp.setTargetField(rule.getTargetField());
        resp.setLinkField(rule.getLinkField());
        resp.setAggregation(rule.getAggregation());
        resp.setOperator(rule.getOperator());
        resp.setMessageTemplate(rule.getMessageTemplate());
        resp.setEnabled(rule.getEnabled());
        resp.setCreatedAt(rule.getCreatedAt());
        resp.setUpdatedAt(rule.getUpdatedAt());
        return resp;
    }
}
