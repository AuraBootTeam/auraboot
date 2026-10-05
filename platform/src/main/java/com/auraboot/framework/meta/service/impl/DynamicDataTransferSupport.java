package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Exports and imports records and executes model actions through the facade ports. */
@Slf4j
@RequiredArgsConstructor
final class DynamicDataTransferSupport extends BaseMetaService {
    private final QueryBuilderService queryBuilderService;

    private final DynamicDataMapper dynamicDataMapper;

    private final ObjectMapper objectMapper;

    private final DataPermissionEngine dataPermissionEngine;

    private final FieldMaskService fieldMaskService;

    private final DataDomainService dataDomainService;

    private final FieldPermissionService fieldPermissionService;

    private final BuildFieldLabelMapOperation buildFieldLabelMapOperation;

    private final MaterializeReferenceDisplayValuesOperation materializeReferenceDisplayValuesOperation;

    private final ApplyFieldPermissionFilterOperation applyFieldPermissionFilterOperation;

    private final CurrentMemberIdForFieldPermissionsOperation currentMemberIdForFieldPermissionsOperation;

    private final ResolveEnrichmentTargetOperation resolveEnrichmentTargetOperation;

    private final EnrichReferenceDisplayFieldsOperation enrichReferenceDisplayFieldsOperation;

    private final AppendScopedBulkFilterOperation appendScopedBulkFilterOperation;

    private final GetModelDefinitionOperation getModelDefinitionOperation;

    private final AssertWritableOperation assertWritableOperation;

    private final ToColumnDataOperation toColumnDataOperation;

    private final MapSortFieldsOperation mapSortFieldsOperation;

    @FunctionalInterface
    interface BuildFieldLabelMapOperation { Map<String, String> execute(List<FieldDefinition> fieldDefs); }

    @FunctionalInterface
    interface MaterializeReferenceDisplayValuesOperation { List<Map<String, Object>> execute(
            List<Map<String, Object>> rows, Set<String> referenceFieldCodes); }

    @FunctionalInterface
    interface ApplyFieldPermissionFilterOperation { List<Map<String, Object>> execute(String modelCode, List<Map<String, Object>> records); }

    @FunctionalInterface
    interface CurrentMemberIdForFieldPermissionsOperation { Long execute(); }

    @FunctionalInterface
    interface ResolveEnrichmentTargetOperation { String[] execute(FieldDefinition field); }

    @FunctionalInterface
    interface EnrichReferenceDisplayFieldsOperation { void execute(String modelCode, List<Map<String, Object>> records); }

    @FunctionalInterface
    interface AppendScopedBulkFilterOperation { void execute(StringBuilder sql, String filter); }

    @FunctionalInterface
    interface GetModelDefinitionOperation { ModelDefinition execute(String modelCode); }

    @FunctionalInterface
    interface AssertWritableOperation { void execute(String modelCode); }

    @FunctionalInterface
    interface ToColumnDataOperation { Map<String, Object> execute(ModelDefinition model, Map<String, Object> data); }

    @FunctionalInterface
    interface MapSortFieldsOperation { List<SortField> execute(ModelDefinition model, List<SortField> sortFields); }

    private Map<String, String> buildFieldLabelMap(List<FieldDefinition> fieldDefs) { return buildFieldLabelMapOperation.execute(fieldDefs); }

    private List<Map<String, Object>> materializeReferenceDisplayValues(
            List<Map<String, Object>> rows, Set<String> referenceFieldCodes) { return materializeReferenceDisplayValuesOperation.execute(rows,referenceFieldCodes); }

    private List<Map<String, Object>> applyFieldPermissionFilter(String modelCode, List<Map<String, Object>> records) { return applyFieldPermissionFilterOperation.execute(modelCode,records); }

    private Long currentMemberIdForFieldPermissions() { return currentMemberIdForFieldPermissionsOperation.execute(); }

    private String[] resolveEnrichmentTarget(FieldDefinition field) { return resolveEnrichmentTargetOperation.execute(field); }

    private void enrichReferenceDisplayFields(String modelCode, List<Map<String, Object>> records) { enrichReferenceDisplayFieldsOperation.execute(modelCode,records); }

    private void appendScopedBulkFilter(StringBuilder sql, String filter) { appendScopedBulkFilterOperation.execute(sql,filter); }

    private ModelDefinition getModelDefinition(String modelCode) { return getModelDefinitionOperation.execute(modelCode); }

    private void assertWritable(String modelCode) { assertWritableOperation.execute(modelCode); }

    private Map<String, Object> toColumnData(ModelDefinition model, Map<String, Object> data) { return toColumnDataOperation.execute(model,data); }

    private List<SortField> mapSortFields(ModelDefinition model, List<SortField> sortFields) { return mapSortFieldsOperation.execute(model,sortFields); }

    ExportResult exportData(String modelCode, DataExportRequest exportRequest){
        validateModelCode(modelCode);
        logOperation("exportData", modelCode, exportRequest);

        Instant startTime = Instant.now();
        ModelDefinition model = getModelDefinition(modelCode);

        // Permission checks BEFORE outer try — failures must NOT be swallowed
        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();

        String rowFilter;
        try {
            rowFilter = dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId);
        } catch (Exception e) {
            log.error("Failed to apply row-level data permission in export for model {} — denying access", logSafe(modelCode), e);
            throw new MetaServiceException("Data permission evaluation failed for export: " + modelCode, e);
        }

        String domainFilter;
        try {
            domainFilter = dataDomainService.buildDomainFilter(modelCode, userId);
        } catch (Exception e) {
            log.error("Failed to apply domain filter in export for model {} — denying access", logSafe(modelCode), e);
            throw new MetaServiceException("Data domain filter failed for export: " + modelCode, e);
        }

        try {
            // Build query for export data
            List<QueryCondition> conditions = exportRequest.getConditions() != null
                    ? exportRequest.getConditions() : Collections.emptyList();
            QueryBuilderService.QueryBuilder queryBuilder = queryBuilderService.buildConditionQuery(model, conditions);
            if (exportRequest.getKeyword() != null && !exportRequest.getKeyword().isBlank()) {
                queryBuilder = queryBuilderService.buildKeywordSearch(
                        queryBuilder,
                        exportRequest.getKeyword().trim(),
                        model);
            }
            queryBuilder.addCondition("tenant_id", QueryCondition.Operator.EQ.name(), tenantId);

            // Apply row-level permission filter
            if (rowFilter != null && !rowFilter.isBlank()) {
                queryBuilder.addRawCondition(rowFilter);
            }

            // Apply domain isolation filter
            if (domainFilter != null && !domainFilter.isBlank()) {
                queryBuilder.addRawCondition(domainFilter);
            }

            // Add sort
            if (exportRequest.getSortFields() != null && !exportRequest.getSortFields().isEmpty()) {
                List<SortField> mappedSortFields = mapSortFields(model, exportRequest.getSortFields());
                queryBuilder = queryBuilderService.buildOrderQuery(queryBuilder, mappedSortFields, model);
            }

            // Add limit
            if (exportRequest.getLimit() != null && exportRequest.getLimit() > 0) {
                queryBuilder.setLimit(exportRequest.getLimit());
            }

            String sql = queryBuilder.getSql();
            Map<String, Object> paramMap = queryBuilder.getParameterMap();
            List<Map<String, Object>> data = dynamicDataMapper.selectByQuery(sql, paramMap);

            // Apply policy-based field masking (fail-secure)
            try {
                List<FieldMaskRule> maskRules = dataPermissionEngine.getFieldMaskRules(tenantId, modelCode, userId);
                if (maskRules != null && !maskRules.isEmpty()) {
                    data = dataPermissionEngine.applyFieldMasking(data, maskRules);
                }
            } catch (Exception e) {
                log.error("Failed to apply policy-based masking in export for model {} — denying access", logSafe(modelCode), e);
                throw new MetaServiceException("Policy-based masking failed for export: " + modelCode, e);
            }

            // Apply configurable field masking for export (A9)
            try {
                data = fieldMaskService.applyMaskingForExport(modelCode, data, userId);
            } catch (Exception e) {
                log.error("Failed to apply configurable masking in export for model {} — denying access",
                        logSafe(modelCode), e);
                throw new MetaServiceException(
                        "Configurable field masking failed for export: " + modelCode, e);
            }

            // Export is another read surface. Apply the same field-level visibility contract as
            // list/detail before choosing columns so a hidden field cannot leak as either a value
            // or a header merely because the client requested its code.
            data = applyFieldPermissionFilter(modelCode, data);

            // Resolve reference display names so the export shows names, not pids (same as list/detail).
            enrichReferenceDisplayFields(modelCode, data);

            // Determine export fields
            List<String> exportFields = exportRequest.getFields();
            Set<String> allowedExportFields = model.getFields().stream()
                    .map(FieldDefinition::getCode)
                    .filter(Objects::nonNull)
                    .filter(field -> !Set.of("id", "tenant_id", "row_version", "deleted", "deleted_flag")
                            .contains(field))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            FieldPermissionSet exportFieldPermissions = fieldPermissionService.getFieldPermissions(
                    currentMemberIdForFieldPermissions(), modelCode);
            allowedExportFields.removeAll(exportFieldPermissions.hiddenFields());
            if (exportFields == null || exportFields.isEmpty()) {
                // A normal roster/export starts with business columns. Explicit
                // authorized audit exports can still request these fields.
                exportFields = allowedExportFields.stream()
                        .filter(field -> !Set.of("pid", "created_at", "updated_at", "created_by", "updated_by").contains(field))
                        .toList();
            } else {
                List<String> forbiddenFields = exportFields.stream()
                        .filter(field -> !allowedExportFields.contains(field))
                        .distinct()
                        .toList();
                if (!forbiddenFields.isEmpty()) {
                    throw new MetaServiceException("Export fields are not allowed: "
                            + String.join(",", forbiddenFields));
                }
                exportFields = exportFields.stream().distinct().toList();
            }

            Set<String> requestedExportFields = new LinkedHashSet<>(exportFields);
            Set<String> referenceExportFields = model.getFields().stream()
                    .filter(field -> requestedExportFields.contains(field.getCode()))
                    .filter(field -> resolveEnrichmentTarget(field) != null)
                    .map(FieldDefinition::getCode)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            data = materializeReferenceDisplayValues(data, referenceExportFields);

            // Build field code → display label map for human-readable headers
            Map<String, String> fieldLabelMap = buildFieldLabelMap(model.getFields());

            // Generate export file
            DataExportRequest.ExportFormat format = exportRequest.getFormat() != null
                    ? exportRequest.getFormat() : DataExportRequest.ExportFormat.CSV;
            String fileName = exportRequest.getFileName() != null
                    ? exportRequest.getFileName()
                    : modelCode + "_export_" + System.currentTimeMillis();

            Path tempFile;
            switch (format) {
                case EXCEL:
                    tempFile = exportAsExcel(data, exportFields, fieldLabelMap, fileName, exportRequest.getIncludeHeader());
                    break;
                case JSON:
                    tempFile = exportAsJson(data, exportFields, fieldLabelMap, fileName);
                    break;
                case CSV:
                default:
                    tempFile = exportAsCsv(data, exportFields, fieldLabelMap, fileName, exportRequest.getIncludeHeader());
                    break;
            }

            long fileSize = Files.size(tempFile);
            return ExportResult.builder()
                    .success(true)
                    .filePath(tempFile.toString())
                    .recordCount((long) data.size())
                    .fileSize(fileSize)
                    .format(format.name())
                    .rowSetDigest(NamedQueryRowSetDigest.digest(data, exportFields))
                    .exportTime(startTime)
                    .build();

        } catch (MetaServiceException e) {
            throw e; // Never swallow permission failures
        } catch (Exception e) {
            log.error("Export failed for model {}: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
            return ExportResult.builder()
                    .success(false)
                    .errorMessage("Export failed: " + e.getMessage())
                    .format(exportRequest.getFormat() != null ? exportRequest.getFormat().name() : "csv")
                    .build();
        }
    }

    ImportResult importData(String modelCode, DataImportRequest importRequest){
        validateModelCode(modelCode);
        assertWritable(modelCode);
        logOperation("importData", modelCode, importRequest);

        Instant startTime = Instant.now();
        ModelDefinition model = getModelDefinition(modelCode);
        ModelMutationGuard.assertCreateAllowed(model);

        try {
            // Validate file exists
            Path filePath = Paths.get(importRequest.getFilePath());
            if (!Files.exists(filePath)) {
                return ImportResult.builder()
                        .success(false)
                        .summary("Import file not found: " + importRequest.getFilePath())
                        .build();
            }

            // Parse file to data list
            List<Map<String, Object>> records;
            DataImportRequest.ImportFormat format = importRequest.getFormat() != null
                    ? importRequest.getFormat() : DataImportRequest.ImportFormat.CSV;

            switch (format) {
                case JSON:
                    records = parseJsonImport(filePath);
                    break;
                case CSV:
                default:
                    records = parseCsvImport(filePath, importRequest.getSkipFirstRow());
                    break;
            }

            // Apply field mapping
            Map<String, String> fieldMapping = importRequest.getFieldMapping();
            if (fieldMapping != null && !fieldMapping.isEmpty()) {
                records = records.stream()
                        .map(row -> applyFieldMapping(row, fieldMapping))
                        .collect(Collectors.toList());
            }

            // Batch insert
            int batchSize = importRequest.getBatchSize() != null ? importRequest.getBatchSize() : 100;
            int successCount = 0;
            int failedCount = 0;
            List<ImportResult.ImportError> errors = new ArrayList<>();
            Long tenantId = getCurrentTenantId();

            for (int i = 0; i < records.size(); i += batchSize) {
                int end = Math.min(i + batchSize, records.size());
                List<Map<String, Object>> batch = records.subList(i, end);

                for (int j = 0; j < batch.size(); j++) {
                    int rowIndex = i + j;
                    try {
                        Map<String, Object> record = batch.get(j);
                        FieldWriterGuard.assertCreateAllowed(model, record);
                        // Add system columns
                        Map<String, Object> columnData = toColumnData(model, record);
                        columnData.put("tenant_id", tenantId);
                        columnData.put("created_at", Instant.now());
                        columnData.put("created_by", getCurrentUserId());

                        Set<String> batchJsonbCols = JsonbFieldHelper.getJsonbHostColumns(model);
                        if (batchJsonbCols.isEmpty()) {
                            dynamicDataMapper.insert(model.getTableName(), columnData);
                        } else {
                            dynamicDataMapper.insertWithJsonb(model.getTableName(), columnData, batchJsonbCols);
                        }
                        successCount++;
                    } catch (Exception e) {
                        failedCount++;
                        errors.add(ImportResult.ImportError.builder()
                                .rowNumber(rowIndex + 1)
                                .fieldName(null)
                                .errorMessage(e.getMessage())
                                .build());
                    }
                }
            }

            return ImportResult.builder()
                    .success(failedCount == 0)
                    .totalCount(records.size())
                    .successCount(successCount)
                    .failedCount(failedCount)
                    .errors(errors)
                    .importTime(startTime)
                    .summary(String.format("Imported %d/%d records", successCount, records.size()))
                    .build();

        } catch (Exception e) {
            log.error("Import failed for model {}: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
            return ImportResult.builder()
                    .success(false)
                    .summary("Import failed: " + e.getMessage())
                    .build();
        }
    }

    ActionExecutionResult executeCustomAction(String modelCode, String actionName, Map<String, Object> actionParams){
        validateModelCode(modelCode);
        logOperation("executeCustomAction", modelCode, actionName);

        Instant startTime = Instant.now();
        ModelDefinition model = getModelDefinition(modelCode);

        try {
            Map<String, Object> resultData = new HashMap<>();

            switch (actionName) {
                case "count": {
                    Long tenantId = getCurrentTenantId();
                    Long userId = getCurrentUserId();
                    StringBuilder sql = new StringBuilder("SELECT COUNT(*) as cnt FROM ")
                            .append(model.getTableName())
                            .append(" WHERE tenant_id = #{params.tenantId}");
                    Map<String, Object> params = new HashMap<>();
                    params.put("tenantId", tenantId);

                    String permitFilter = CommandPermitDataAccess.rowFilter(modelCode, userId);
                    if (permitFilter != null) {
                        appendScopedBulkFilter(sql, permitFilter);
                    } else {
                        String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId);
                        if (rowFilter != null && !rowFilter.isBlank()) {
                            sql.append(" ").append(rowFilter);
                        }
                        String domainFilter = dataDomainService.buildDomainFilter(modelCode, userId);
                        if (domainFilter != null && !domainFilter.isBlank()) {
                            sql.append(" ").append(domainFilter);
                        }
                    }

                    List<Map<String, Object>> results = dynamicDataMapper.selectByQuery(sql.toString(), params);
                    long count = results.isEmpty() ? 0 : ((Number) results.get(0).get("cnt")).longValue();
                    resultData.put("count", count);
                    break;
                }
                case "truncate": {
                    return ActionExecutionResult.builder()
                            .success(false)
                            .actionName(actionName)
                            .errorMessage("Unsupported action: " + actionName)
                            .executionTime(startTime)
                            .duration(java.time.Duration.between(startTime, Instant.now()).toMillis())
                            .build();
                }
                default:
                    return ActionExecutionResult.builder()
                            .success(false)
                            .actionName(actionName)
                            .errorMessage("Unsupported action: " + actionName)
                            .executionTime(startTime)
                            .duration(java.time.Duration.between(startTime, Instant.now()).toMillis())
                            .build();
            }

            return ActionExecutionResult.builder()
                    .success(true)
                    .actionName(actionName)
                    .resultData(resultData)
                    .message("Action '" + actionName + "' executed successfully")
                    .executionTime(startTime)
                    .duration(java.time.Duration.between(startTime, Instant.now()).toMillis())
                    .build();

        } catch (Exception e) {
            log.error("Custom action '{}' failed for model {}: {}",
                    logSafe(actionName), logSafe(modelCode), logSafe(e.getMessage()), e);
            return ActionExecutionResult.builder()
                    .success(false)
                    .actionName(actionName)
                    .errorMessage("Action failed: " + e.getMessage())
                    .executionTime(startTime)
                    .duration(java.time.Duration.between(startTime, Instant.now()).toMillis())
                    .build();
        }
    }

    DynamicDataFileCodec fileCodec(){
        return new DynamicDataFileCodec(objectMapper);
    }

    Path exportAsExcel(List<Map<String, Object>> data, List<String> fields,
                              Map<String, String> labels, String name, Boolean header) throws IOException{
        return fileCodec().exportAsExcel(data, fields, labels, name, header);
    }

    Path exportAsCsv(List<Map<String, Object>> data, List<String> fields,
                            Map<String, String> labels, String name, Boolean header) throws IOException{
        return fileCodec().exportAsCsv(data, fields, labels, name, header);
    }

    Path exportAsJson(List<Map<String, Object>> data, List<String> fields,
                             Map<String, String> labels, String name) throws IOException{
        return fileCodec().exportAsJson(data, fields, labels, name);
    }

    List<Map<String, Object>> parseJsonImport(Path path) throws IOException{
        return fileCodec().parseJsonImport(path);
    }

    List<Map<String, Object>> parseCsvImport(Path path, Boolean skipFirstRow) throws IOException{
        return fileCodec().parseCsvImport(path, skipFirstRow);
    }

    Map<String, Object> applyFieldMapping(Map<String, Object> row, Map<String, String> mapping){
        return fileCodec().applyFieldMapping(row, mapping);
    }

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }
}
