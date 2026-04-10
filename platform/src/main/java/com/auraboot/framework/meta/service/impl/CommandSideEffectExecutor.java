package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.entity.payload.DocumentFlowConfig;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DocumentFlowService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles side effect operations for the command execution pipeline.
 * Side effects create/update related records based on conditions in executionConfig.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandSideEffectExecutor {

    private final DynamicDataMapper dynamicDataMapper;
    private final DynamicDataService dynamicDataService;
    private final MetaModelService metaModelService;
    private final CommandSpelEvaluator spelEvaluator;
    private final DocumentFlowService documentFlowService;
    private final ObjectMapper objectMapper;

    /**
     * Execute side effect phase based on executionConfig.sideEffects configuration.
     */
    @SuppressWarnings("unchecked")
    public void executeSideEffectPhase(Map<String, Object> execConfig, Map<String, Object> payload,
                                Long tenantId, Long userId, CommandDefinition command,
                                CommandExecuteRequest request,
                                Map<String, Object> fieldMapResults) {
        if (execConfig == null || !execConfig.containsKey("sideEffects")) {
            return;
        }

        List<Map<String, Object>> sideEffects = (List<Map<String, Object>>) execConfig.get("sideEffects");
        if (sideEffects == null || sideEffects.isEmpty()) {
            return;
        }

        // Build context for variable resolution (merge fieldMapResults so ${recordId} works for CREATE)
        Map<String, Object> currentRecord = buildCurrentRecordContext(payload, tenantId, command, request, fieldMapResults);

        for (Map<String, Object> effect : sideEffects) {
            String condition = (String) effect.get("condition");

            // Evaluate condition (if present)
            if (condition != null && !condition.isEmpty()) {
                EvaluationContext spelContext = spelEvaluator.buildSpelContext(payload);
                try {
                    Boolean result = spelEvaluator.evaluate(condition, spelContext, Boolean.class);
                    if (result == null || !result) {
                        continue; // Skip this side effect
                    }
                } catch (Exception e) {
                    log.warn("Failed to evaluate sideEffect condition '{}': {}", condition, e.getMessage());
                    continue;
                }
            }

            // Support both flat format (action/targetModel/fieldMapping) and nested format (actions[])
            String flatAction = (String) effect.get("action");
            if (flatAction == null) {
                flatAction = (String) effect.get("type");
            }
            if (flatAction != null) {
                // Flat format: single action per effect
                String targetModel = (String) effect.get("targetModel");
                if (targetModel == null) targetModel = (String) effect.get("modelCode");
                Map<String, Object> fieldMapping = (Map<String, Object>) effect.get("fieldMapping");
                if (fieldMapping == null) fieldMapping = (Map<String, Object>) effect.get("updateFields");
                if (fieldMapping == null) fieldMapping = (Map<String, Object>) effect.get("fields");
                if (targetModel == null) continue;
                switch (flatAction) {
                    case "create_record" -> executeSideEffectCreate(targetModel, fieldMapping,
                            currentRecord, tenantId, userId);
                    case "update_record" -> {
                        String targetIdField = (String) effect.get("targetIdField");
                        if (targetIdField == null) targetIdField = (String) effect.get("targetRecordField");
                        if (targetIdField == null) targetIdField = (String) effect.get("recordIdField");
                        executeSideEffectUpdate(targetModel, targetIdField, fieldMapping,
                                currentRecord, tenantId);
                    }
                    case "batch_create_record", "batch_create_records" -> {
                        String sourceField = (String) effect.get("sourceField");
                        executeBatchCreate(targetModel, sourceField, fieldMapping,
                                currentRecord, tenantId, userId);
                    }
                    case "batch_update_record", "batch_update_records" -> {
                        String sourceField = (String) effect.get("sourceField");
                        String targetIdField = (String) effect.get("targetIdField");
                        if (targetIdField == null) targetIdField = (String) effect.get("targetRecordField");
                        if (targetIdField == null) targetIdField = (String) effect.get("recordIdField");
                        executeBatchUpdate(targetModel, sourceField, targetIdField, fieldMapping,
                                currentRecord, tenantId);
                    }
                    case "aggregate" -> executeSideEffectAggregate(targetModel, fieldMapping,
                            currentRecord, tenantId, effect);
                    case "document_flow" -> {
                        String sourceRecordId = (String) currentRecord.get("id");
                        String modelCode = command.getModelCode();
                        executeDocumentFlow(modelCode, sourceRecordId, tenantId, effect);
                    }
                    default -> log.warn("Unknown sideEffect action: {}", flatAction);
                }
            } else {
                // Nested format: actions[] array per effect
                List<Map<String, Object>> actions = (List<Map<String, Object>>) effect.get("actions");
                if (actions == null) continue;
                for (Map<String, Object> actionDef : actions) {
                    String actionType = (String) actionDef.get("type");
                    if (actionType == null) actionType = (String) actionDef.get("action");
                    String targetModel = (String) actionDef.get("modelCode");
                    if (targetModel == null) targetModel = (String) actionDef.get("targetModel");
                    Map<String, Object> fieldMapping = (Map<String, Object>) actionDef.get("fields");
                    if (fieldMapping == null) fieldMapping = (Map<String, Object>) actionDef.get("fieldMapping");
                    if (actionType == null || targetModel == null) continue;
                    switch (actionType) {
                        case "create_record" -> executeSideEffectCreate(targetModel, fieldMapping,
                                currentRecord, tenantId, userId);
                        case "update_record" -> {
                            String targetIdField = (String) actionDef.get("recordIdField");
                            if (targetIdField == null) targetIdField = (String) actionDef.get("targetIdField");
                            executeSideEffectUpdate(targetModel, targetIdField, fieldMapping,
                                    currentRecord, tenantId);
                        }
                        case "batch_create_record", "batch_create_records" -> {
                            String sourceField = (String) actionDef.get("sourceField");
                            executeBatchCreate(targetModel, sourceField, fieldMapping,
                                    currentRecord, tenantId, userId);
                        }
                        case "batch_update_record", "batch_update_records" -> {
                            String sourceField = (String) actionDef.get("sourceField");
                            String targetIdField = (String) actionDef.get("targetIdField");
                            if (targetIdField == null) targetIdField = (String) actionDef.get("recordIdField");
                            executeBatchUpdate(targetModel, sourceField, targetIdField, fieldMapping,
                                    currentRecord, tenantId);
                        }
                        case "aggregate" -> executeSideEffectAggregate(targetModel, fieldMapping,
                                currentRecord, tenantId, actionDef);
                        case "document_flow" -> {
                            String sourceRecordId = (String) currentRecord.get("id");
                            String modelCode = command.getModelCode();
                            executeDocumentFlow(modelCode, sourceRecordId, tenantId, actionDef);
                        }
                        default -> log.warn("Unknown sideEffect action: {}", actionType);
                    }
                }
            }
        }
    }

    /**
     * Create a new record as a side effect.
     * Also used by PostActionExecutor for CREATE_RECORD post actions.
     */
    void executeSideEffectCreate(String targetModel, Map<String, Object> fieldMapping,
                                  Map<String, Object> currentRecord,
                                  Long tenantId, Long userId) {
        if (fieldMapping == null) return;

        Map<String, Object> data = resolveFieldMapping(fieldMapping, currentRecord);
        data.put("tenant_id", tenantId);

        try {
            dynamicDataService.create(targetModel, data);
            log.info("SIDE_EFFECT CREATE_RECORD: created record in {}", targetModel);
        } catch (Exception e) {
            log.error("SIDE_EFFECT CREATE_RECORD failed for {}: {}", targetModel, e.getMessage());
            throw new BusinessException(ResponseCode.BadParam,
                    "Side effect failed: create " + targetModel + ": " + e.getMessage());
        }
    }

    /**
     * Execute a DOCUMENT_FLOW side effect: create downstream document(s) from the source record.
     * The effect config must contain a "documentFlow" object that maps to {@link DocumentFlowConfig}.
     *
     * <p>Example sideEffect config:</p>
     * <pre>{@code
     * {
     *   "action": "document_flow",
     *   "documentFlow": {
     *     "targetModelCode": "inv_outbound_order",
     *     "fieldMapping": {
     *       "ioo_source_order": "${recordId}",
     *       "ioo_status": "'pending'"
     *     },
     *     "lineMapping": {
     *       "sourceLineModel": "sales_order_line",
     *       "sourceForeignKey": "sol_order_id",
     *       "targetLineModel": "inv_outbound_order_line",
     *       "targetForeignKey": "iool_order_id",
     *       "fieldMapping": {
     *         "iool_product": "${line.sol_product}",
     *         "iool_qty": "${line.sol_qty}"
     *       }
     *     }
     *   }
     * }
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    private void executeDocumentFlow(String sourceModelCode, String sourceRecordId,
                                      Long tenantId, Map<String, Object> effectConfig) {
        Object flowConfigObj = effectConfig.get("documentFlow");
        if (flowConfigObj == null) {
            log.warn("DOCUMENT_FLOW sideEffect missing 'documentFlow' config");
            return;
        }

        DocumentFlowConfig flowConfig;
        try {
            flowConfig = objectMapper.convertValue(flowConfigObj, DocumentFlowConfig.class);
        } catch (Exception e) {
            log.error("DOCUMENT_FLOW: failed to parse documentFlow config: {}", e.getMessage());
            throw new BusinessException(ResponseCode.BadParam,
                    "Document flow config parse error: " + e.getMessage());
        }

        String createdId = documentFlowService.executeFlow(sourceModelCode, sourceRecordId, tenantId, flowConfig);
        log.info("DOCUMENT_FLOW: created {} #{} from {} #{}",
                flowConfig.getTargetModelCode(), createdId, sourceModelCode, sourceRecordId);
    }

    private void executeSideEffectUpdate(String targetModel, String targetIdField,
                                          Map<String, Object> fieldMapping,
                                          Map<String, Object> currentRecord,
                                          Long tenantId) {
        if (fieldMapping == null) return;

        // Resolve target record ID from current record
        Object targetRecordIdObj = null;
        if (targetIdField != null && currentRecord != null) {
            targetRecordIdObj = currentRecord.get(targetIdField);
        }

        if (targetRecordIdObj == null) {
            log.warn("SIDE_EFFECT UPDATE_RECORD: cannot resolve target record ID from field '{}'", targetIdField);
            return;
        }

        try {
            String tableName = metaModelService.getTableName(targetModel);
            String recordIdVal = targetRecordIdObj.toString();
            var idEntry = CommandExecutorUtils.resolveRecordIdColumn(recordIdVal);
            Map<String, Object> targetRecord = loadTargetRecord(tableName, idEntry.getKey(), idEntry.getValue(), tenantId);
            Map<String, Object> evaluationContext = new HashMap<>();
            if (targetRecord != null) {
                evaluationContext.putAll(targetRecord);
            }
            if (currentRecord != null) {
                evaluationContext.putAll(currentRecord);
            }
            Map<String, Object> data = resolveFieldMapping(fieldMapping, evaluationContext);
            Map<String, Object> conditions = Map.of("tenant_id", tenantId, idEntry.getKey(), idEntry.getValue());
            int updated = dynamicDataMapper.update(tableName, data, conditions);
            if (updated == 0) {
                log.warn("SIDE_EFFECT UPDATE_RECORD: no record found to update in {} with {}={}", targetModel, idEntry.getKey(), recordIdVal);
            }
            log.info("SIDE_EFFECT UPDATE_RECORD: updated {} record(s) in {} ({}={})", updated, targetModel, idEntry.getKey(), recordIdVal);
        } catch (Exception e) {
            log.error("SIDE_EFFECT UPDATE_RECORD failed for {}: {}",
                    targetModel, e.getMessage());
            throw new BusinessException(ResponseCode.BadParam,
                    "Side effect failed: update " + targetModel + ": " + e.getMessage());
        }
    }

    private Map<String, Object> loadTargetRecord(String tableName, String idColumn, Object recordId, Long tenantId) {
        CommandExecutorUtils.validateSqlIdentifier(tableName, "SIDE_EFFECT UPDATE_RECORD table");
        CommandExecutorUtils.validateSqlIdentifier(idColumn, "SIDE_EFFECT UPDATE_RECORD id column");
        String sql = "SELECT * FROM " + tableName
                + " WHERE tenant_id = #{params.tenantId} AND " + idColumn + " = #{params.recordId} LIMIT 1";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, Map.of(
                "tenantId", tenantId,
                "recordId", recordId
        ));
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    /**
     * Resolve field mapping values. Supports:
     * - "$current.fieldName" -> resolve from current record
     * - "${fieldName}" -> template format resolution
     * - Plain values -> use as-is
     */
    Map<String, Object> resolveFieldMapping(Map<String, Object> fieldMapping,
                                              Map<String, Object> currentRecord) {
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : fieldMapping.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String strValue) {
                if (strValue.startsWith("$current.")) {
                    // Legacy format: $current.fieldName
                    String fieldName = strValue.substring("$current.".length());
                    value = currentRecord != null ? currentRecord.get(fieldName) : null;
                } else if (strValue.startsWith("${") && strValue.endsWith("}")) {
                    // Template format: ${fieldName}, ${recordId}, or ${SpEL expression}
                    String inner = strValue.substring(2, strValue.length() - 1).trim();
                    if ("recordId".equals(inner)) {
                        // Special: recordId resolves to the current record's id
                        value = currentRecord != null ? currentRecord.get("id") : null;
                    } else if (inner.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
                        // Simple field reference
                        value = currentRecord != null ? currentRecord.get(inner) : null;
                    } else {
                        // SpEL expression (arithmetic, function calls, etc.)
                        value = evaluateSpelExpression(inner, currentRecord);
                    }
                }
            }
            resolved.put(entry.getKey(), value);
        }
        return resolved;
    }

    /**
     * Evaluate a SpEL expression against the current record context.
     * Used for arithmetic expressions in field mappings like ${cc_contract_amount + cc_change_amount}.
     */
    private Object evaluateSpelExpression(String expression, Map<String, Object> context) {
        if (context == null) return null;
        try {
            EvaluationContext ctx = spelEvaluator.buildSpelContext(context);
            return spelEvaluator.evaluate(expression, ctx);
        } catch (Exception e) {
            log.warn("SpEL evaluation failed for sideEffect field mapping '{}': {}", expression, e.getMessage());
            return null;
        }
    }

    private static final Set<String> SUPPORTED_AGGREGATE_FUNCTIONS = Set.of("sum", "count", "avg", "max", "min");

    /**
     * Execute an AGGREGATE side effect: aggregate a child model's field and write the result to the parent.
     * Config keys: childModel, childField, parentField, parentFk, function (default SUM).
     * Supported functions: SUM, COUNT, AVG, MAX, MIN.
     */
    @SuppressWarnings("unchecked")
    void executeSideEffectAggregate(String targetModel, Map<String, Object> fieldMapping,
                                     Map<String, Object> currentRecord, Long tenantId,
                                     Map<String, Object> effectConfig) {
        String childModel = (String) effectConfig.get("childModel");
        String childField = (String) effectConfig.get("childField");
        String parentField = (String) effectConfig.get("parentField");
        String parentFk = (String) effectConfig.get("parentFk");
        String function = (String) effectConfig.getOrDefault("function", "sum");

        if (childModel == null || childField == null || parentField == null || parentFk == null) {
            log.warn("AGGREGATE sideEffect missing required config fields (childModel, childField, parentField, parentFk)");
            return;
        }

        if (!SUPPORTED_AGGREGATE_FUNCTIONS.contains(function)) {
            log.warn("AGGREGATE sideEffect: unsupported function '{}', supported: {}", function, SUPPORTED_AGGREGATE_FUNCTIONS);
            return;
        }

        // Get parent ID from current record via the foreign key field
        Object parentIdObj = currentRecord != null ? currentRecord.get(parentFk) : null;
        if (parentIdObj == null) {
            log.warn("AGGREGATE sideEffect: parentFk '{}' not found in current record", parentFk);
            return;
        }
        String parentId = parentIdObj.toString();

        // Query all child records matching the parent FK
        String childTable = metaModelService.getTableName(childModel);
        CommandExecutorUtils.validateSqlIdentifier(childField, "AGGREGATE childField");
        CommandExecutorUtils.validateSqlIdentifier(parentFk, "AGGREGATE parentFk");
        CommandExecutorUtils.validateSqlIdentifier(parentField, "AGGREGATE parentField");

        String sql = "SELECT " + childField + " FROM " + childTable
                + " WHERE " + parentFk + " = #{params.parentId} AND tenant_id = #{params.tenantId}";

        // Optional childFilter: static SQL condition appended to the WHERE clause
        String childFilter = (String) effectConfig.get("childFilter");
        if (childFilter != null && !childFilter.isBlank()) {
            CommandExecutorUtils.validateSqlFragment(childFilter, "AGGREGATE childFilter");
            sql += " AND " + childFilter;
        }

        List<Map<String, Object>> children = dynamicDataMapper.selectByQuery(
                sql, Map.of("parentId", parentId, "tenantId", tenantId));

        // Collect non-null numeric values from child rows
        List<BigDecimal> values = new ArrayList<>();
        if (children == null) {
            children = List.of();
        }
        for (Map<String, Object> child : children) {
            if (child == null) continue;
            Object val = child.get(childField);
            if (val instanceof BigDecimal bd) {
                values.add(bd);
            } else if (val instanceof Number n) {
                values.add(BigDecimal.valueOf(n.doubleValue()));
            } else if (val instanceof String s) {
                try {
                    values.add(new BigDecimal(s));
                } catch (NumberFormatException ignored) {
                    // skip non-numeric strings
                }
            }
            // null values are silently skipped
        }

        // Compute aggregate result based on function
        BigDecimal result = computeAggregate(function, values);

        // Update parent record
        String parentTable = metaModelService.getTableName(targetModel);
        var idEntry = CommandExecutorUtils.resolveRecordIdColumn(parentId);
        dynamicDataMapper.update(parentTable, Map.of(parentField, result),
                Map.of("tenant_id", tenantId, idEntry.getKey(), idEntry.getValue()));

        log.info("AGGREGATE {}({}.{}) where {}={} = {} -> {}.{}",
                function, childModel, childField, parentFk, parentId, result, targetModel, parentField);
    }

    /**
     * Compute the aggregate result for the given function over a list of BigDecimal values.
     */
    static BigDecimal computeAggregate(String function, List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }

        return switch (function) {
            case "sum" -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case "count" -> new BigDecimal(values.size());
            case "avg" -> {
                BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                yield sum.divide(new BigDecimal(values.size()), 4, RoundingMode.HALF_UP);
            }
            case "max" -> values.stream().reduce(BigDecimal::max).orElse(BigDecimal.ZERO);
            case "min" -> values.stream().reduce(BigDecimal::min).orElse(BigDecimal.ZERO);
            default -> BigDecimal.ZERO; // unreachable due to earlier validation
        };
    }

    /**
     * Create multiple records from an array field in the payload.
     * Iterates over the array in sourceField, creating one record per item.
     * fieldMapping supports ${item.xxx} to reference fields from each array item,
     * plus ${recordId} and ${fieldName} for current record context.
     */
    @SuppressWarnings("unchecked")
    void executeBatchCreate(String targetModel, String sourceField,
                             Map<String, Object> fieldMapping,
                             Map<String, Object> currentRecord,
                             Long tenantId, Long userId) {
        if (fieldMapping == null || sourceField == null) {
            log.warn("BATCH_CREATE_RECORD: missing sourceField or fieldMapping");
            return;
        }

        List<Map<String, Object>> items = resolveSourceArray(sourceField, currentRecord);
        if (items == null || items.isEmpty()) {
            log.info("BATCH_CREATE_RECORD: sourceField '{}' is empty or not an array, skipping", sourceField);
            return;
        }

        int created = 0;
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            Map<String, Object> data = resolveItemFieldMapping(fieldMapping, currentRecord, item);
            data.put("tenant_id", tenantId);

            try {
                dynamicDataService.create(targetModel, data);
                created++;
            } catch (Exception e) {
                log.error("BATCH_CREATE_RECORD failed for {} at index {}: {}", targetModel, i, e.getMessage());
                throw new BusinessException(ResponseCode.BadParam,
                        "Batch create failed for " + targetModel + " at index " + i + ": " + e.getMessage());
            }
        }

        log.info("BATCH_CREATE_RECORD: created {} record(s) in {} from sourceField '{}'",
                created, targetModel, sourceField);
    }

    /**
     * Update multiple records matching criteria from an array field in the payload.
     * Iterates over the array in sourceField; for each item, finds the target record
     * by item[targetIdField] and applies fieldMapping.
     */
    @SuppressWarnings("unchecked")
    void executeBatchUpdate(String targetModel, String sourceField, String targetIdField,
                             Map<String, Object> fieldMapping,
                             Map<String, Object> currentRecord, Long tenantId) {
        if (fieldMapping == null || sourceField == null) {
            log.warn("BATCH_UPDATE_RECORD: missing sourceField or fieldMapping");
            return;
        }
        if (targetIdField == null) {
            log.warn("BATCH_UPDATE_RECORD: missing targetIdField");
            return;
        }

        List<Map<String, Object>> items = resolveSourceArray(sourceField, currentRecord);
        if (items == null || items.isEmpty()) {
            log.info("BATCH_UPDATE_RECORD: sourceField '{}' is empty or not an array, skipping", sourceField);
            return;
        }

        String tableName = metaModelService.getTableName(targetModel);
        int totalUpdated = 0;

        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);

            // Resolve target record ID from the item
            Object targetRecordIdObj = item.get(targetIdField);
            if (targetRecordIdObj == null) {
                log.warn("BATCH_UPDATE_RECORD: item at index {} has no value for targetIdField '{}'", i, targetIdField);
                continue;
            }

            Map<String, Object> data = resolveItemFieldMapping(fieldMapping, currentRecord, item);

            try {
                String recordIdVal = targetRecordIdObj.toString();
                var idEntry = CommandExecutorUtils.resolveRecordIdColumn(recordIdVal);
                Map<String, Object> conditions = Map.of("tenant_id", tenantId, idEntry.getKey(), idEntry.getValue());
                int count = dynamicDataMapper.update(tableName, data, conditions);
                if (count == 0) {
                    log.warn("BATCH_UPDATE_RECORD: no record found in {} with {}={} at index {}",
                            targetModel, idEntry.getKey(), recordIdVal, i);
                }
                totalUpdated += count;
            } catch (Exception e) {
                log.error("BATCH_UPDATE_RECORD failed for {} at index {}: {}", targetModel, i, e.getMessage());
                throw new BusinessException(ResponseCode.BadParam,
                        "Batch update failed for " + targetModel + " at index " + i + ": " + e.getMessage());
            }
        }

        log.info("BATCH_UPDATE_RECORD: updated {} record(s) in {} from sourceField '{}'",
                totalUpdated, targetModel, sourceField);
    }

    /**
     * Extract the source array from the current record by sourceField name.
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> resolveSourceArray(String sourceField, Map<String, Object> currentRecord) {
        if (currentRecord == null) return null;

        Object sourceObj = currentRecord.get(sourceField);
        if (sourceObj instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                } else {
                    log.warn("BATCH sideEffect: sourceField '{}' contains non-map element: {}",
                            sourceField, element);
                }
            }
            return result;
        }
        return null;
    }

    /**
     * Resolve field mapping with support for ${item.xxx} references.
     * Supports:
     * - "${item.fieldName}" -> resolve from the current array item
     * - "${recordId}" -> current record's id
     * - "${fieldName}" / "$current.fieldName" -> resolve from current record
     * - Plain values -> use as-is
     */
    Map<String, Object> resolveItemFieldMapping(Map<String, Object> fieldMapping,
                                                  Map<String, Object> currentRecord,
                                                  Map<String, Object> item) {
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : fieldMapping.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String strValue) {
                if (strValue.startsWith("${item.") && strValue.endsWith("}")) {
                    // Item reference: ${item.fieldName}
                    String fieldName = strValue.substring("${item.".length(), strValue.length() - 1);
                    value = item != null ? item.get(fieldName) : null;
                } else if (strValue.startsWith("$current.")) {
                    // Legacy format: $current.fieldName
                    String fieldName = strValue.substring("$current.".length());
                    value = currentRecord != null ? currentRecord.get(fieldName) : null;
                } else if (strValue.startsWith("${") && strValue.endsWith("}")) {
                    // Template format: ${fieldName} or ${recordId}
                    String fieldName = strValue.substring(2, strValue.length() - 1);
                    if ("recordId".equals(fieldName)) {
                        value = currentRecord != null ? currentRecord.get("id") : null;
                    } else {
                        value = currentRecord != null ? currentRecord.get(fieldName) : null;
                    }
                }
            }
            resolved.put(entry.getKey(), value);
        }
        return resolved;
    }

    /**
     * Build a context map representing the current record.
     * Combines payload with existing record data from DB.
     * For CREATE commands, merges fieldMapResults so ${recordId} resolves to the newly created record's pid.
     */
    Map<String, Object> buildCurrentRecordContext(Map<String, Object> payload,
                                                    Long tenantId, CommandDefinition command,
                                                    CommandExecuteRequest request) {
        return buildCurrentRecordContext(payload, tenantId, command, request, null);
    }

    Map<String, Object> buildCurrentRecordContext(Map<String, Object> payload,
                                                    Long tenantId, CommandDefinition command,
                                                    CommandExecuteRequest request,
                                                    Map<String, Object> fieldMapResults) {
        Map<String, Object> context = new HashMap<>(payload);

        // Merge fieldMapResults (contains recordId from FIELD_MAP INSERT for CREATE commands)
        if (fieldMapResults != null) {
            // Only merge recordId and other metadata, not _inserted/_updated counters
            Object fmRecordId = fieldMapResults.get("recordId");
            if (fmRecordId != null && !context.containsKey("id")) {
                context.put("id", coerceRecordId(fmRecordId));
            }
        }

        // Merge with existing record data
        if (request != null && StringUtils.hasText(request.getTargetRecordId())) {
            Map<String, Object> existingRecord = readRecordSnapshot(tenantId, command.getModelCode(),
                    request.getTargetRecordId());
            if (existingRecord != null) {
                // Existing record values as base, payload overrides
                Map<String, Object> merged = new HashMap<>(existingRecord);
                merged.putAll(payload);
                context = merged;
            }
            // Always set id from target record
            context.put("id", coerceRecordId(request.getTargetRecordId()));
        }

        return context;
    }

    private Object coerceRecordId(Object recordId) {
        if (recordId instanceof String recordIdStr) {
            try {
                return Long.parseLong(recordIdStr);
            } catch (NumberFormatException ignored) {
                return recordIdStr;
            }
        }
        return recordId;
    }

    /**
     * Read a full record snapshot from a dynamic table.
     */
    private Map<String, Object> readRecordSnapshot(Long tenantId, String modelCode, String recordId) {
        try {
            String tableName = metaModelService.getTableName(modelCode);
            var idEntry = CommandExecutorUtils.resolveRecordIdColumn(recordId);
            String sql = "SELECT * FROM " + tableName
                    + " WHERE tenant_id = #{params.tenantId} AND " + idEntry.getKey() + " = #{params.recordId}";
            Map<String, Object> params = Map.of("tenantId", tenantId, "recordId", idEntry.getValue());
            List<Map<String, Object>> result = dynamicDataMapper.selectByQuery(sql, params);
            if (result != null && !result.isEmpty()) {
                return result.get(0);
            }
        } catch (Exception e) {
            log.debug("Failed to read record snapshot: {}", e.getMessage());
        }
        return null;
    }

}
