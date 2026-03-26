package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Auto-Fill API Controller
 *
 * <p>Provides field value lookup for REFERENCE field auto-fill.
 * When a user selects a reference record, the frontend can call this endpoint
 * to fetch related field values and pre-fill them into the current form.</p>
 *
 * <p>Security: Leverages the existing DynamicDataService.getById which enforces
 * tenant isolation and row-level permission checks automatically.</p>
 *
 * @author AuraBoot Team
 * @since 3.1.0
 */
@Slf4j
@Tag(name = "自动填充", description = "引用字段自动填充 - 根据引用记录ID查询并返回字段值")
@RestController
@RequestMapping("/api/meta/auto-fill")
@RequiredArgsConstructor
public class AutoFillController {

    private final DynamicDataService dynamicDataService;
    private final MetaModelService metaModelService;

    /**
     * Fetch field values from a reference record for auto-fill.
     *
     * <p>The caller specifies which fields to return via the {@code fields} parameter
     * (comma-separated field codes). Only those field values are returned, keyed by
     * field code.</p>
     *
     * <p>If the record is not found or the caller lacks permission, an empty map is
     * returned rather than an error, so the form gracefully handles missing data.</p>
     *
     * @param modelCode target model code (e.g. "crm_account")
     * @param recordId  primary-key value of the record to look up
     * @param fields    comma-separated list of field codes to return
     * @return map of fieldCode → value for the requested fields
     */
    @Operation(
        summary = "获取引用记录字段值",
        description = "根据引用记录ID查询指定字段的值，用于表单自动填充。" +
                      "返回值以字段编码为 key，仅包含 fields 参数指定的字段。"
    )
    @GetMapping
    public ApiResponse<Map<String, Object>> getAutoFillValues(
            @Parameter(description = "模型编码，例如 crm_account")
            @RequestParam String modelCode,

            @Parameter(description = "记录ID（主键值）")
            @RequestParam String recordId,

            @Parameter(description = "需要返回的字段编码，多个以逗号分隔，例如 crm_acc_industry,crm_acc_city")
            @RequestParam String fields) {

        log.debug("Auto-fill lookup: modelCode={}, recordId={}, fields={}", modelCode, recordId, fields);

        // Validate identifier patterns to prevent SQL injection
        validateIdentifier(modelCode, "modelCode");
        validateIdentifier(recordId, "recordId");

        // Parse requested field codes
        Set<String> requestedFields = parseFieldCodes(fields);
        if (requestedFields.isEmpty()) {
            return ApiResponse.success(Collections.emptyMap());
        }

        // Validate each field code
        for (String fieldCode : requestedFields) {
            validateIdentifier(fieldCode, "field code");
        }

        // Resolve field codes to column names using model metadata
        List<FieldDefinition> fieldDefs = metaModelService.getModelFields(modelCode);
        if (fieldDefs == null || fieldDefs.isEmpty()) {
            log.warn("Auto-fill: no fields found for modelCode={}", modelCode);
            return ApiResponse.success(Collections.emptyMap());
        }

        Map<String, String> codeToColumn = new HashMap<>();
        for (FieldDefinition fd : fieldDefs) {
            if (fd.getCode() != null) {
                codeToColumn.put(fd.getCode(), fd.getColumnName() != null ? fd.getColumnName() : fd.getCode());
            }
        }

        // Fetch the full record — DynamicDataService enforces tenant isolation + row-level ACL
        Map<String, Object> record;
        try {
            record = dynamicDataService.getById(modelCode, recordId);
        } catch (MetaServiceException e) {
            // Record not found or access denied — return empty map so form stays clean
            log.debug("Auto-fill: record not found or access denied for modelCode={} recordId={}: {}",
                    modelCode, recordId, e.getMessage());
            return ApiResponse.success(Collections.emptyMap());
        }

        if (record == null || record.isEmpty()) {
            return ApiResponse.success(Collections.emptyMap());
        }

        // Build result: map requested field codes to their values from the record
        Map<String, Object> result = new LinkedHashMap<>();
        for (String fieldCode : requestedFields) {
            // Try field code first (service may return code-keyed or column-keyed maps)
            if (record.containsKey(fieldCode)) {
                result.put(fieldCode, record.get(fieldCode));
            } else {
                // Fall back to column name lookup
                String columnName = codeToColumn.get(fieldCode);
                if (columnName != null && record.containsKey(columnName)) {
                    result.put(fieldCode, record.get(columnName));
                }
                // Field not present in record — omit from result (form keeps existing value)
            }
        }

        log.debug("Auto-fill result: modelCode={}, recordId={}, returnedFields={}", modelCode, recordId, result.keySet());
        return ApiResponse.success(result);
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * Validate that the given value is a safe identifier (alphanumeric + underscore).
     * Mirrors the validation pattern used throughout DynamicDataServiceImpl.
     */
    private void validateIdentifier(String value, String paramName) {
        if (value == null || value.trim().isEmpty()) {
            throw new MetaServiceException(paramName + " cannot be null or empty");
        }
        if (!value.matches("^[a-zA-Z0-9_-]+$")) {
            throw new MetaServiceException("Invalid " + paramName + " format: " + value);
        }
    }

    /**
     * Parse a comma-separated list of field codes, trimming whitespace and ignoring blanks.
     */
    private Set<String> parseFieldCodes(String fields) {
        if (fields == null || fields.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String part : fields.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
