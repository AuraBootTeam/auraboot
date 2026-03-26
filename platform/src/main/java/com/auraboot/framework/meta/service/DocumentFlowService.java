package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.entity.payload.DocumentFlowConfig;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes cross-module document flows triggered by command side effects.
 *
 * <p>A document flow reads a source record, resolves field mappings, creates a new target
 * record in the configured model, and optionally replicates line items from a child model.</p>
 *
 * <p>Expression formats supported in fieldMapping values:</p>
 * <ul>
 *   <li>{@code ${record.fieldCode}} — value from source header record</li>
 *   <li>{@code ${recordId}} — source record PID</li>
 *   <li>{@code 'literal'} — string literal (single quotes stripped)</li>
 *   <li>Plain string/non-string — used as-is</li>
 * </ul>
 *
 * @author AuraBoot Team
 * @since 2.7.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentFlowService {

    private final DynamicDataMapper dynamicDataMapper;
    private final MetaModelService metaModelService;
    private final DynamicDataService dynamicDataService;

    /**
     * Execute a document flow: create a target document from a source record.
     *
     * @param sourceModelCode source model code (e.g., "sales_order")
     * @param sourceRecordId  source record PID (ULID or numeric string)
     * @param tenantId        tenant ID for data isolation
     * @param config          document flow configuration
     * @return the created target record's PID
     */
    public String executeFlow(String sourceModelCode, String sourceRecordId,
                              Long tenantId, DocumentFlowConfig config) {
        if (config == null || config.getTargetModelCode() == null) {
            throw new BusinessException(ResponseCode.BadParam,
                    "DocumentFlowConfig must specify targetModelCode");
        }

        // 1. Load source record data
        Map<String, Object> sourceRecord = loadSourceRecord(sourceModelCode, sourceRecordId, tenantId);
        if (sourceRecord == null) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Source record not found: " + sourceModelCode + "#" + sourceRecordId);
        }

        // 2. Resolve header field mappings and create target record
        Map<String, Object> targetData = new HashMap<>();
        if (config.getFieldMapping() != null) {
            for (Map.Entry<String, String> entry : config.getFieldMapping().entrySet()) {
                Object resolved = resolveExpression(entry.getValue(), sourceRecord, sourceRecordId, null);
                targetData.put(entry.getKey(), resolved);
            }
        }
        targetData.put("tenant_id", tenantId);

        Map<String, Object> created;
        try {
            created = dynamicDataService.create(config.getTargetModelCode(), targetData);
            log.info("DOCUMENT_FLOW: created {} from {} #{}", config.getTargetModelCode(),
                    sourceModelCode, sourceRecordId);
        } catch (Exception e) {
            log.error("DOCUMENT_FLOW: failed to create {}: {}", config.getTargetModelCode(), e.getMessage());
            throw new BusinessException(ResponseCode.BadParam,
                    "Document flow failed: create " + config.getTargetModelCode() + ": " + e.getMessage());
        }

        // Extract the newly created record's PID
        String targetRecordId = extractRecordId(created);

        // 3. If lineMapping is configured, replicate source lines into target lines
        DocumentFlowConfig.LineMapping lineMapping = config.getLineMapping();
        if (lineMapping != null && targetRecordId != null) {
            replicateLines(lineMapping, sourceRecord, sourceRecordId, targetRecordId, tenantId);
        }

        return targetRecordId;
    }

    /**
     * Load the source record from its model table.
     */
    private Map<String, Object> loadSourceRecord(String modelCode, String recordId, Long tenantId) {
        String tableName = metaModelService.getTableName(modelCode);
        var idEntry = resolveRecordIdColumn(recordId);
        String sql = "SELECT * FROM " + tableName
                + " WHERE tenant_id = #{params.tenantId} AND " + idEntry.getKey() + " = #{params.recordId}";
        Map<String, Object> params = Map.of("tenantId", tenantId, "recordId", idEntry.getValue());
        List<Map<String, Object>> results = dynamicDataMapper.selectByQuery(sql, params);
        return (results != null && !results.isEmpty()) ? results.get(0) : null;
    }

    /**
     * Replicate source line items into the target line model.
     */
    private void replicateLines(DocumentFlowConfig.LineMapping lineMapping,
                                 Map<String, Object> sourceRecord, String sourceRecordId,
                                 String targetRecordId, Long tenantId) {
        if (lineMapping.getSourceLineModel() == null || lineMapping.getTargetLineModel() == null
                || lineMapping.getSourceForeignKey() == null) {
            log.warn("DOCUMENT_FLOW lineMapping missing required fields (sourceLineModel, targetLineModel, sourceForeignKey)");
            return;
        }

        // Load source lines
        String sourceLineTable = metaModelService.getTableName(lineMapping.getSourceLineModel());
        SqlSafetyUtils.validateIdentifier(lineMapping.getSourceForeignKey(), "lineMapping.sourceForeignKey");
        String sql = "SELECT * FROM " + sourceLineTable
                + " WHERE tenant_id = #{params.tenantId} AND "
                + lineMapping.getSourceForeignKey() + " = #{params.sourceRecordId}";
        Map<String, Object> params = Map.of("tenantId", tenantId, "sourceRecordId", sourceRecordId);
        List<Map<String, Object>> sourceLines = dynamicDataMapper.selectByQuery(sql, params);

        if (sourceLines == null || sourceLines.isEmpty()) {
            log.info("DOCUMENT_FLOW: no lines found in {} for {} #{}", lineMapping.getSourceLineModel(),
                    lineMapping.getSourceForeignKey(), sourceRecordId);
            return;
        }

        int created = 0;
        for (Map<String, Object> sourceLine : sourceLines) {
            Map<String, Object> lineData = new HashMap<>();

            // Resolve line field mappings
            if (lineMapping.getFieldMapping() != null) {
                for (Map.Entry<String, String> entry : lineMapping.getFieldMapping().entrySet()) {
                    Object resolved = resolveExpression(entry.getValue(), sourceRecord, sourceRecordId, sourceLine);
                    lineData.put(entry.getKey(), resolved);
                }
            }

            // Set the foreign key linking to the newly created target header
            if (lineMapping.getTargetForeignKey() != null) {
                lineData.put(lineMapping.getTargetForeignKey(), targetRecordId);
            }

            lineData.put("tenant_id", tenantId);

            try {
                dynamicDataService.create(lineMapping.getTargetLineModel(), lineData);
                created++;
            } catch (Exception e) {
                log.error("DOCUMENT_FLOW: failed to create line in {}: {}",
                        lineMapping.getTargetLineModel(), e.getMessage());
                throw new BusinessException(ResponseCode.BadParam,
                        "Document flow failed: create line in " + lineMapping.getTargetLineModel()
                                + ": " + e.getMessage());
            }
        }

        log.info("DOCUMENT_FLOW: replicated {} line(s) from {} into {}",
                created, lineMapping.getSourceLineModel(), lineMapping.getTargetLineModel());
    }

    /**
     * Resolve a field mapping expression to a concrete value.
     *
     * <p>Supported formats:</p>
     * <ul>
     *   <li>{@code ${record.fieldCode}} — value from source header record</li>
     *   <li>{@code ${line.fieldCode}} — value from current source line item (if present)</li>
     *   <li>{@code ${recordId}} — source record PID</li>
     *   <li>{@code 'literal'} — string literal with single quotes stripped</li>
     *   <li>Other strings / non-strings — returned as-is</li>
     * </ul>
     *
     * @param expression   the expression string from the config
     * @param sourceRecord the source header record map
     * @param sourceRecordId the source record's PID
     * @param sourceLine   the current source line map (may be null for header-level mappings)
     * @return resolved value
     */
    Object resolveExpression(String expression, Map<String, Object> sourceRecord,
                             String sourceRecordId, Map<String, Object> sourceLine) {
        if (!(expression instanceof String)) {
            return expression;
        }
        String expr = expression.trim();

        // ${record.fieldCode}
        if (expr.startsWith("${record.") && expr.endsWith("}")) {
            String fieldCode = expr.substring("${record.".length(), expr.length() - 1);
            return sourceRecord != null ? sourceRecord.get(fieldCode) : null;
        }

        // ${line.fieldCode}
        if (expr.startsWith("${line.") && expr.endsWith("}")) {
            String fieldCode = expr.substring("${line.".length(), expr.length() - 1);
            return sourceLine != null ? sourceLine.get(fieldCode) : null;
        }

        // ${recordId}
        if ("${recordId}".equals(expr)) {
            return sourceRecordId;
        }

        // 'literal string'
        if (expr.startsWith("'") && expr.endsWith("'") && expr.length() >= 2) {
            return expr.substring(1, expr.length() - 1);
        }

        // Plain value — return as-is
        return expression;
    }

    private Map.Entry<String, Object> resolveRecordIdColumn(String recordId) {
        try {
            return Map.entry("id", (Object) Long.parseLong(recordId));
        } catch (NumberFormatException e) {
            return Map.entry("pid", (Object) recordId);
        }
    }

    /**
     * Extract the PID (or numeric id as string) from a created record response.
     */
    private String extractRecordId(Map<String, Object> record) {
        if (record == null) return null;
        Object pid = record.get("pid");
        if (pid != null) return pid.toString();
        Object id = record.get("id");
        if (id != null) return id.toString();
        return null;
    }
}
