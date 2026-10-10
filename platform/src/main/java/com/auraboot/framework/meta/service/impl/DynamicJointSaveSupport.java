package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.exception.MetaServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Coordinates master/detail saves and scoped replacement of child records. */
@Slf4j
@RequiredArgsConstructor
final class DynamicJointSaveSupport extends BaseMetaService {
    private final MetaModelService metadataService;

    private final DynamicDataMapper dynamicDataMapper;

    private final CreateOperation createOperation;

    private final UpdateOperation updateOperation;

    private final GetModelDefinitionOperation getModelDefinitionOperation;

    private final AssertWritableOperation assertWritableOperation;

    @FunctionalInterface
    interface CreateOperation { Map<String, Object> execute(String modelCode, Map<String, Object> data); }

    @FunctionalInterface
    interface UpdateOperation { Map<String, Object> execute(String modelCode, String recordId, Map<String, Object> inputData); }

    @FunctionalInterface
    interface GetModelDefinitionOperation { ModelDefinition execute(String modelCode); }

    @FunctionalInterface
    interface AssertWritableOperation { void execute(String modelCode); }

    private Map<String, Object> create(String modelCode, Map<String, Object> data) { return createOperation.execute(modelCode,data); }

    private Map<String, Object> update(String modelCode, String recordId, Map<String, Object> inputData) { return updateOperation.execute(modelCode,recordId,inputData); }

    private ModelDefinition getModelDefinition(String modelCode) { return getModelDefinitionOperation.execute(modelCode); }

    private void assertWritable(String modelCode) { assertWritableOperation.execute(modelCode); }

    JointSubTableSaveResponse saveWithRelations(String modelCode, JointSubTableSaveRequest request){
        validateModelCode(modelCode);
        assertWritable(modelCode);
        if (request == null || request.getMasterData() == null) {
            throw new MetaServiceException("Request and master data cannot be null");
        }

        long startTime = System.currentTimeMillis();
        logOperation("saveWithRelations", modelCode, request.getMasterData().keySet());

        ModelDefinition masterModel = getModelDefinition(modelCode);
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        String pkField = primaryKey.getCode();

        List<String> errors = new ArrayList<>();
        Map<String, Integer> subTableCounts = new HashMap<>();
        Map<String, List<Map<String, Object>>> savedRecords = new HashMap<>();
        Map<String, List<JointSubTableSaveResponse.SubTableError>> subTableErrors = new HashMap<>();

        try {
            // Step 1: Determine if this is create or update
            Object existingPkValue = request.getMasterData().get(pkField);
            boolean isUpdate = existingPkValue != null && !existingPkValue.toString().trim().isEmpty();
            JointSubTableSaveResponse.OperationType opType;
            Map<String, Object> savedMaster;
            String masterId;

            // Step 2: Save master record
            if (isUpdate) {
                opType = JointSubTableSaveResponse.OperationType.UPDATE;
                masterId = existingPkValue.toString();
                savedMaster = update(modelCode, masterId, request.getMasterData());
                log.info("Updated master record: model={}, id={}", logSafe(modelCode), logSafe(masterId));
            } else {
                opType = JointSubTableSaveResponse.OperationType.CREATE;
                savedMaster = create(modelCode, request.getMasterData());
                masterId = savedMaster.get(pkField).toString();
                log.info("Created master record: model={}, id={}", logSafe(modelCode), logSafe(masterId));
            }

            // Step 3: Process each sub-table
            if (request.getTables() != null && !request.getTables().isEmpty()) {
                for (Map.Entry<String, List<Map<String, Object>>> entry : request.getTables().entrySet()) {
                    String tableKey = entry.getKey();
                    List<Map<String, Object>> childRows = entry.getValue();

                    if (childRows == null) {
                        continue;
                    }

                    // Resolve relation name
                    String relationName = tableKey;
                    if (request.getRelationMappings() != null && request.getRelationMappings().containsKey(tableKey)) {
                        relationName = request.getRelationMappings().get(tableKey);
                    }

                    try {
                        // Find relation definition
                        RelationDefinition relation = findRelationByName(masterModel, relationName);
                        if (relation == null) {
                            errors.add("Relation '" + relationName + "' not found in model " + modelCode);
                            continue;
                        }

                        // Get target model
                        String targetModelCode = relation.getTargetModel();
                        ModelDefinition targetModel = getModelDefinition(targetModelCode);

                        // Delete existing child records if replace mode
                        if (Boolean.TRUE.equals(request.getReplaceExisting()) && isUpdate) {
                            ModelMutationGuard.assertMutable(targetModel, "replaced");
                            deleteExistingChildRecords(relation, masterId);
                        }

                        // Save child records
                        List<Map<String, Object>> savedChildren = new ArrayList<>();
                        List<JointSubTableSaveResponse.SubTableError> rowErrors = new ArrayList<>();
                        int successCount = 0;

                        for (int i = 0; i < childRows.size(); i++) {
                            Map<String, Object> childData = new HashMap<>(childRows.get(i));

                            try {
                                // Inject foreign key
                                String fkField = relation.getTargetField();
                                childData.put(fkField, masterId);

                                // Create child record
                                Map<String, Object> savedChild = create(targetModelCode, childData);
                                savedChildren.add(savedChild);
                                successCount++;
                        } catch (Exception e) {
                            log.warn("Failed to save child record at index {} for relation {}: {}",
                                    i, logSafe(relationName), logSafe(e.getMessage()), e);
                                rowErrors.add(JointSubTableSaveResponse.SubTableError.builder()
                                        .rowIndex(i)
                                        .message(e.getMessage())
                                        .data(childData)
                                        .build());
                            }
                        }

                        subTableCounts.put(relationName, successCount);
                        savedRecords.put(relationName, savedChildren);

                        if (!rowErrors.isEmpty()) {
                            subTableErrors.put(relationName, rowErrors);
                            errors.add("Sub-table '" + relationName + "' had " + rowErrors.size() + " errors");
                        }

                        log.info("Saved {} records for relation: {}", successCount, logSafe(relationName));

                    } catch (Exception e) {
                        log.error("Failed to process sub-table {}: {}", logSafe(tableKey), logSafe(e.getMessage()), e);
                        errors.add("Sub-table '" + tableKey + "': " + e.getMessage());
                    }
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            return JointSubTableSaveResponse.builder()
                    .success(errors.isEmpty())
                    .masterId(masterId)
                    .masterRecord(savedMaster)
                    .subTableCounts(subTableCounts)
                    .savedRecords(savedRecords)
                    .subTableErrors(subTableErrors)
                    .errors(errors)
                    .duration(duration)
                    .operationType(opType)
                    .build();

        } catch (Exception e) {
            log.error("Joint save failed for model {}: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
            long duration = System.currentTimeMillis() - startTime;
            errors.add("Master save failed: " + e.getMessage());
            return JointSubTableSaveResponse.failure(errors, duration);
        }
    }

    RelationDefinition findRelationByName(ModelDefinition model, String relationName){
        if (model.getRelations() == null || model.getRelations().isEmpty()) {
            return null;
        }

        // First try exact name match
        for (RelationDefinition relation : model.getRelations()) {
            if (relationName.equals(relation.getName())) {
                return relation;
            }
        }

        // Try target model code match
        for (RelationDefinition relation : model.getRelations()) {
            if (relationName.equals(relation.getTargetModel())) {
                return relation;
            }
        }

        return null;
    }

    void deleteExistingChildRecords(RelationDefinition relation, String masterId){
        Long tenantId = getCurrentTenantId();

        if (relation.getRelationType() == RelationDefinition.RelationType.MANY_TO_MANY) {
            // Junction replacement changes target relations without deleting the target rows.
            RecordCommandWriterGuard.guardRelationReplacement(dynamicDataMapper,
                    getModelDefinition(relation.getTargetModel()), relation.getTargetTable(), relation.getJoinTable(),
                    relation.getSourceField(), relation.getTargetField(), masterId, code -> metadataService.getModelDefinition(code).orElse(null));
            Map<String, Object> conditions = new HashMap<>();
            conditions.put(relation.getSourceField(), masterId);
            conditions.put("tenant_id", tenantId);
            dynamicDataMapper.delete(relation.getJoinTable(), conditions);
            log.debug("Deleted existing M2M relations from {} for master {}",
                    logSafe(relation.getJoinTable()), logSafe(masterId));
        } else if (relation.getRelationType() == RelationDefinition.RelationType.ONE_TO_MANY) {
            // For O2M, delete from target table
            Map<String, Object> conditions = new HashMap<>();
            conditions.put(relation.getTargetField(), masterId);
            conditions.put("tenant_id", tenantId);
            conditions = RecordCommandWriterGuard.guardLegacyConditions(dynamicDataMapper,
                    getModelDefinition(relation.getTargetModel()), relation.getTargetTable(), conditions, "delete", null, code -> metadataService.getModelDefinition(code).orElse(null));
            dynamicDataMapper.delete(relation.getTargetTable(), conditions);
            log.debug("Deleted existing child records from {} for master {}",
                    logSafe(relation.getTargetTable()), logSafe(masterId));
        }
    }

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }
}
