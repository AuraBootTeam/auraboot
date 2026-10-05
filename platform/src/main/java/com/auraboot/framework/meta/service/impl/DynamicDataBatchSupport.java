package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Executes bulk and per-row mutations with validation and write-scope checks. */
@Slf4j
@RequiredArgsConstructor
final class DynamicDataBatchSupport extends BaseMetaService {
    private final MetaModelService metadataService;

    private final ValidationService validationService;

    private final TypeSystemManager typeSystemManager;

    private final DynamicDataMapper dynamicDataMapper;

    private final DataPermissionEngine dataPermissionEngine;

    private final DataDomainService dataDomainService;

    private final PayloadTemporalNormalizer payloadTemporalNormalizer;

    private final GetByIdOperation getByIdOperation;

    private final CreateOperation createOperation;

    private final ConvertDataTypesOperation convertDataTypesOperation;

    private final FilterVirtualFieldsOperation filterVirtualFieldsOperation;

    private final StripNonWritableFieldsOperation stripNonWritableFieldsOperation;

    private final EnsureTableExistsOperation ensureTableExistsOperation;

    private final UpdateOperation updateOperation;

    private final AppendScopedBulkFilterOperation appendScopedBulkFilterOperation;

    private final GetModelDefinitionOperation getModelDefinitionOperation;

    private final AssertWritableOperation assertWritableOperation;

    private final ToColumnDataOperation toColumnDataOperation;

    @FunctionalInterface
    interface GetByIdOperation { Map<String, Object> execute(String modelCode, String recordId); }

    @FunctionalInterface
    interface CreateOperation { Map<String, Object> execute(String modelCode, Map<String, Object> data); }

    @FunctionalInterface
    interface ConvertDataTypesOperation { Map<String, Object> execute(ModelDefinition model, Map<String, Object> data); }

    @FunctionalInterface
    interface FilterVirtualFieldsOperation { void execute(ModelDefinition model, Map<String, Object> data); }

    @FunctionalInterface
    interface StripNonWritableFieldsOperation { Set<String> execute(String modelCode, Map<String, Object> data); }

    @FunctionalInterface
    interface EnsureTableExistsOperation { void execute(String modelCode); }

    @FunctionalInterface
    interface UpdateOperation { Map<String, Object> execute(String modelCode, String recordId, Map<String, Object> inputData); }

    @FunctionalInterface
    interface AppendScopedBulkFilterOperation { void execute(StringBuilder sql, String filter); }

    @FunctionalInterface
    interface GetModelDefinitionOperation { ModelDefinition execute(String modelCode); }

    @FunctionalInterface
    interface AssertWritableOperation { void execute(String modelCode); }

    @FunctionalInterface
    interface ToColumnDataOperation { Map<String, Object> execute(ModelDefinition model, Map<String, Object> data); }

    private Map<String, Object> getById(String modelCode, String recordId) { return getByIdOperation.execute(modelCode,recordId); }

    private Map<String, Object> create(String modelCode, Map<String, Object> data) { return createOperation.execute(modelCode,data); }

    private Map<String, Object> convertDataTypes(ModelDefinition model, Map<String, Object> data) { return convertDataTypesOperation.execute(model,data); }

    private void filterVirtualFields(ModelDefinition model, Map<String, Object> data) { filterVirtualFieldsOperation.execute(model,data); }

    private Set<String> stripNonWritableFields(String modelCode, Map<String, Object> data) { return stripNonWritableFieldsOperation.execute(modelCode,data); }

    private void ensureTableExists(String modelCode) { ensureTableExistsOperation.execute(modelCode); }

    private Map<String, Object> update(String modelCode, String recordId, Map<String, Object> inputData) { return updateOperation.execute(modelCode,recordId,inputData); }

    private void appendScopedBulkFilter(StringBuilder sql, String filter) { appendScopedBulkFilterOperation.execute(sql,filter); }

    private ModelDefinition getModelDefinition(String modelCode) { return getModelDefinitionOperation.execute(modelCode); }

    private void assertWritable(String modelCode) { assertWritableOperation.execute(modelCode); }

    private Map<String, Object> toColumnData(ModelDefinition model, Map<String, Object> data) { return toColumnDataOperation.execute(model,data); }

    DynamicBatchResponse batchCreate(String modelCode, List<Map<String, Object>> dataList){
        validateModelCode(modelCode);
        assertWritable(modelCode);
        if (dataList == null || dataList.isEmpty()) {
            throw new MetaServiceException("Data list cannot be null or empty");
        }

        logOperation("batchCreate", modelCode, dataList.size());

        ModelDefinition model = getModelDefinition(modelCode);
        ModelMutationGuard.assertCreateAllowed(model);
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);

        DynamicBatchResponse response = new DynamicBatchResponse();
        response.setTotal(dataList.size());

        int successCount = 0;
        int failedCount = 0;
        List<String> errors = new ArrayList<>();

        // No outer @Transactional — each create() runs in its own transaction
        for (int i = 0; i < dataList.size(); i++) {
            try {
                Map<String, Object> data = dataList.get(i);

                // Check if record already exists (idempotent behavior)
                Object primaryKeyValue = data.get(primaryKey.getCode());
                if (primaryKeyValue != null) {
                    try {
                        Map<String, Object> existingRecord = getById(modelCode, primaryKeyValue.toString());
                        if (existingRecord != null) {
                            log.info("Record with primary key {} already exists, skipping creation", logSafe(primaryKeyValue));
                            successCount++;
                            continue;
                        }
                    } catch (MetaServiceException e) {
                        if (!isRecordNotFound(e)) {
                            throw e;
                        }
                        // Record does not exist, proceed with creation.
                    }
                }

                create(modelCode, data);
                successCount++;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // Reliable duplicate key detection via exception type, not string matching
                log.info("Duplicate key detected for row {}, treating as success", i + 1);
                successCount++;
            } catch (Exception e) {
                failedCount++;
                errors.add("Row " + (i + 1) + ": " + e.getMessage());
                log.warn("Batch create failed for row {}: {}", i + 1, logSafe(e.getMessage()), e);
            }
        }

        response.setSuccess(successCount);
        response.setFailed(failedCount);
        response.setErrors(errors);

        return response;
    }

    List<Map<String, Object>> bulkCreate(String modelCode, List<Map<String, Object>> dataList){
        validateModelCode(modelCode);
        assertWritable(modelCode);
        if (dataList == null || dataList.isEmpty()) {
            throw new MetaServiceException("Data list cannot be null or empty");
        }

        logOperation("bulkCreate", modelCode, dataList.size());

        ModelDefinition model = getModelDefinition(modelCode);
        ModelMutationGuard.assertCreateAllowed(model);
        ensureTableExists(modelCode);
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        Set<String> jsonbColumns = JsonbFieldHelper.getJsonbHostColumns(model);

        Object currentUserId = getCurrentUserId();
        Object currentTenantId = getCurrentTenantId();
        java.time.Instant now = java.time.Instant.now();

        List<Map<String, Object>> columnDataList = new ArrayList<>(dataList.size());
        List<Map<String, Object>> createdRecords = new ArrayList<>(dataList.size());
        List<Map<String, Object>> validationRows = new ArrayList<>(dataList.size());

        for (Map<String, Object> input : dataList) {
            if (input == null || input.isEmpty()) {
                throw new MetaServiceException("Data cannot be null or empty");
            }
            // Preserve the create() normalization and conversion on copies of caller maps.
            // Validate the complete batch before the first write to share relation lookups.
            Map<String, Object> data = new HashMap<>(input);
            FieldWriterGuard.assertCreateAllowed(model, data);
            stripNonWritableFields(modelCode, data);
            payloadTemporalNormalizer.normalize(data, model);
            validationRows.add(data);

            Map<String, Object> enrichedData = new HashMap<>(data);
            enrichedData.put("created_at", now);
            enrichedData.put("created_by", currentUserId);
            enrichedData.put("updated_at", now);
            enrichedData.put("updated_by", currentUserId);
            enrichedData.put("tenant_id", currentTenantId);

            if (!enrichedData.containsKey(primaryKey.getCode())) {
                enrichedData.put(primaryKey.getCode(), typeSystemManager.generatePrimaryKey(primaryKey));
            }

            enrichedData = convertDataTypes(model, enrichedData);
            filterVirtualFields(model, enrichedData);

            columnDataList.add(toColumnData(model, enrichedData));
            createdRecords.add(enrichedData); // carries generated PK, in input order
        }

        validationService.validateBatchAndThrow(model, validationRows, ValidationContext.CREATE);

        int inserted = jsonbColumns.isEmpty()
                ? dynamicDataMapper.batchInsert(model.getTableName(), columnDataList)
                : dynamicDataMapper.batchInsertWithJsonb(model.getTableName(), columnDataList, jsonbColumns);
        if (inserted != dataList.size()) {
            throw new MetaServiceException(
                    "Bulk create expected " + dataList.size() + " rows inserted but got " + inserted);
        }

        return createdRecords;
    }

    boolean isRecordNotFound(MetaServiceException e){
        String message = e.getMessage();
        return message != null && message.startsWith("Record not found:");
    }

    DynamicBatchResponse batchUpdate(String modelCode, List<Map<String, Object>> dataList){
        validateModelCode(modelCode);
        assertWritable(modelCode);
        if (dataList == null || dataList.isEmpty()) {
            throw new MetaServiceException("Data list cannot be null or empty");
        }

        logOperation("batchUpdate", modelCode, dataList.size());

        ModelDefinition model = getModelDefinition(modelCode);
        ModelMutationGuard.assertMutable(model, "batch updated");
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);

        DynamicBatchResponse response = new DynamicBatchResponse();
        response.setTotal(dataList.size());

        int successCount = 0;
        int failedCount = 0;
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < dataList.size(); i++) {
            try {
                Map<String, Object> data = dataList.get(i);
                Object recordId = data.get(primaryKey.getCode());
                if (recordId == null) {
                    throw new MetaServiceException("Primary key is required for update");
                }

                update(modelCode, recordId.toString(), data);
                successCount++;
            } catch (Exception e) {
                failedCount++;
                errors.add("Row " + (i + 1) + ": " + e.getMessage());
                log.warn("Batch update failed for row {}: {}", i + 1, logSafe(e.getMessage()), e);
            }
        }

        response.setSuccess(successCount);
        response.setFailed(failedCount);
        response.setErrors(errors);

        return response;
    }

    void batchDelete(String modelCode, List<String> recordIds){
        validateModelCode(modelCode);
        assertWritable(modelCode);
        if (recordIds == null || recordIds.isEmpty()) {
            throw new MetaServiceException("Record IDs cannot be null or empty");
        }

        logOperation("batchDelete", modelCode, recordIds.size());

        ModelDefinition model = getModelDefinition(modelCode);
        ModelMutationGuard.assertDeleteAllowed(model);
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        String tableName = SqlSafetyUtils.requireIdentifier(model.getTableName(), "table name");
        String primaryKeyColumn = SqlSafetyUtils.requireIdentifier(
                primaryKey.getColumnName() != null ? primaryKey.getColumnName() : primaryKey.getCode(),
                "primary key column");

        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        StringBuilder sql = new StringBuilder("DELETE FROM ")
                .append(tableName)
                .append(" WHERE tenant_id = #{params.tenantId}")
                .append(" AND ")
                .append(primaryKeyColumn)
                .append(" IN (");
        for (int i = 0; i < recordIds.size(); i++) {
            String recordId = recordIds.get(i);
            if (recordId == null || recordId.isBlank()) {
                throw new MetaServiceException("Record ID cannot be null or empty");
            }
            if (i > 0) {
                sql.append(", ");
            }
            String paramName = "id" + i;
            sql.append("#{params.").append(paramName).append("}");
            params.put(paramName, recordId);
        }
        sql.append(")");

        String permitFilter = CommandPermitDataAccess.rowFilter(modelCode, userId);
        if (permitFilter != null) {
            appendScopedBulkFilter(sql, permitFilter);
        } else {
            try {
                String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId);
                appendScopedBulkFilter(sql, rowFilter);
            } catch (Exception e) {
                log.error("Failed to apply row-level data permission for batch delete on model {} — denying access",
                        logSafe(modelCode), e);
                throw new MetaServiceException("Data permission evaluation failed for model: " + modelCode, e);
            }

            try {
                String domainFilter = dataDomainService.buildDomainFilter(modelCode, userId);
                appendScopedBulkFilter(sql, domainFilter);
            } catch (Exception e) {
                log.error("Failed to apply domain filter for batch delete on model {} — denying access",
                        logSafe(modelCode), e);
                throw new MetaServiceException("Data domain filter evaluation failed for model: " + modelCode, e);
            }
        }

        int affected = dynamicDataMapper.deleteByQuery(sql.toString(), params);
        if (affected != recordIds.size()) {
            throw new MetaServiceException(
                    "Batch delete denied: only " + affected + " of " + recordIds.size()
                            + " requested records matched tenant and data scope");
        }

        log.info("Batch deleted {} records from model: {}", recordIds.size(), logSafe(modelCode));
    }

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }
}
