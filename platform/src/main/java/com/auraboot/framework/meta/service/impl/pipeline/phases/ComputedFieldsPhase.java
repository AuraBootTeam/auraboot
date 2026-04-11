package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.ChangeRecord;
import com.auraboot.framework.meta.dto.FieldChange;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.ChangeTracker;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.CommandExecutorUtils;
import com.auraboot.framework.meta.service.impl.CommandSpelEvaluator;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.RecordSnapshotReader;
import com.auraboot.framework.meta.validation.ComputedFieldDependencyResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;

@Slf4j
@Component
@Order(1100)
@RequiredArgsConstructor
public class ComputedFieldsPhase implements CommandPhase {

    private final MetaModelService metaModelService;
    private final CommandSpelEvaluator spelEvaluator;
    private final DynamicDataMapper dynamicDataMapper;
    private final ChangeTracker changeTracker;
    private final RecordSnapshotReader snapshotReader;

    @Override public String name() { return "computed_fields"; }

    @Override
    public void execute(CommandPipelineContext ctx) {
        executeComputedFieldsPhase(ctx.getExecConfig(), ctx.getPayload(),
                ctx.getTenantId(), ctx.getCommand(), ctx.getRequest(), ctx.getFieldMapResults());
        recordChangeTracking(ctx.getCommand(), ctx.getRequest(),
                ctx.getTenantId(), ctx.getUserId(), ctx.getBeforeSnapshot());
    }

    // ==================== Inlined delegate methods ====================

    @SuppressWarnings("unchecked")
    private void executeComputedFieldsPhase(Map<String, Object> execConfig, Map<String, Object> payload,
                                              Long tenantId, CommandDefinition command,
                                              CommandExecuteRequest request,
                                              Map<String, Object> fieldMapResults) {
        if (execConfig == null || !execConfig.containsKey("computedFields")) {
            return;
        }

        Map<String, String> computedFields = (Map<String, String>) execConfig.get("computedFields");
        if (computedFields == null || computedFields.isEmpty()) {
            return;
        }

        ModelDefinition modelDef = metaModelService.getModelDefinition(command.getModelCode()).orElse(null);
        List<FieldDefinition> fieldDefs = (modelDef != null && modelDef.getFields() != null)
                ? modelDef.getFields() : List.of();
        ComputedFieldDependencyResolver dependencyResolver = new ComputedFieldDependencyResolver();
        List<Map.Entry<String, String>> sortedFields;
        try {
            sortedFields = dependencyResolver.resolveExecutionOrder(computedFields, fieldDefs);
        } catch (Exception e) {
            log.warn("Computed field dependency resolution failed, using insertion order: {}", e.getMessage());
            sortedFields = new ArrayList<>(computedFields.entrySet());
        }

        Map<String, Object> combinedContext = new HashMap<>(payload);
        combinedContext.putAll(fieldMapResults);

        EvaluationContext spelContext = spelEvaluator.buildSpelContext(combinedContext);

        Map<String, Object> computedValues = new HashMap<>();
        for (Map.Entry<String, String> entry : sortedFields) {
            String fieldCode = entry.getKey();
            String expression = entry.getValue();

            try {
                Object result = spelEvaluator.evaluate(expression, spelContext);
                if (result != null) {
                    computedValues.put(fieldCode, result);
                    payload.put(fieldCode, result);
                    log.debug("COMPUTED: {} = {} (expr={})", fieldCode, result, expression);
                }
            } catch (Exception e) {
                log.warn("Failed to compute field '{}' with expression '{}': {}",
                        fieldCode, expression, e.getMessage());
            }
        }

        String recordIdStr = (request != null && StringUtils.hasText(request.getTargetRecordId()))
                ? request.getTargetRecordId()
                : (String) fieldMapResults.get("recordId");
        if (!computedValues.isEmpty() && StringUtils.hasText(recordIdStr)) {
            String tableName = metaModelService.getTableName(command.getModelCode());
            CommandExecutorUtils.validateSqlIdentifier(tableName, "computed field tableName");
            String sql = "SELECT id FROM " + tableName
                    + " WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid}";
            Map<String, Object> lookupParams = Map.of("tenantId", tenantId, "pid", recordIdStr);
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, lookupParams);
            if (rows != null && !rows.isEmpty()) {
                Long dbId = ((Number) rows.get(0).get("id")).longValue();
                Map<String, Object> conditions = Map.of("tenant_id", tenantId, "id", dbId);
                dynamicDataMapper.update(tableName, computedValues, conditions);
                log.debug("COMPUTED: wrote {} fields to {} (pid={})", computedValues.size(), tableName, recordIdStr);
            } else {
                var fallbackEntry = CommandExecutorUtils.resolveRecordIdColumn(recordIdStr);
                Map<String, Object> conditions = Map.of("tenant_id", tenantId, fallbackEntry.getKey(), fallbackEntry.getValue());
                dynamicDataMapper.update(tableName, computedValues, conditions);
            }
        }
    }

    private void recordChangeTracking(CommandDefinition command, CommandExecuteRequest request,
                                       Long tenantId, Long userId, Map<String, Object> beforeSnapshot) {
        try {
            String modelCode = command.getModelCode();
            if (!StringUtils.hasText(modelCode)) {
                return;
            }

            String recordId = request != null ? request.getTargetRecordId() : null;
            String operationType = request != null ? request.getOperationType() : null;

            String operation;
            Map<String, Object> afterSnapshot = null;

            if ("delete".equalsIgnoreCase(operationType)) {
                operation = "delete";
            } else if ("update".equalsIgnoreCase(operationType) && StringUtils.hasText(recordId)) {
                operation = "update";
                afterSnapshot = snapshotReader.readRecordSnapshot(tenantId, modelCode, recordId);
            } else if (beforeSnapshot == null && StringUtils.hasText(recordId)) {
                operation = "create";
                afterSnapshot = snapshotReader.readRecordSnapshot(tenantId, modelCode, recordId);
            } else {
                return;
            }

            List<FieldChange> changes = changeTracker.diff(beforeSnapshot, afterSnapshot, modelCode);
            if (changes.isEmpty() && !"delete".equals(operation)) {
                return;
            }

            ChangeRecord record = ChangeRecord.builder()
                    .modelCode(modelCode)
                    .recordId(recordId != null ? recordId : "unknown")
                    .operation(operation)
                    .changedBy(userId)
                    .commandCode(command.getCode())
                    .changes(changes)
                    .snapshotBefore(beforeSnapshot)
                    .snapshotAfter(afterSnapshot)
                    .build();

            changeTracker.recordChange(record);
        } catch (Exception e) {
            log.warn("Change tracking failed for command {}: {}", command.getCode(), e.getMessage());
        }
    }
}
