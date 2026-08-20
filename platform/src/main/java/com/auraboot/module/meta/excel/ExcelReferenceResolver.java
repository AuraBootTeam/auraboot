package com.auraboot.module.meta.excel;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves user-facing Excel reference values to the configured stored reference value.
 *
 * <p>PID remains backwards compatible. Business-key lookup is opt-in through
 * {@code refTarget.importMatchFields}; every lookup uses DynamicDataService so tenant, row-scope,
 * field masking and target-model read permission remain authoritative.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExcelReferenceResolver {

    private final DynamicDataService dynamicDataService;
    private final MetaModelService metaModelService;

    public String resolve(FieldDefinition field, String uploadedValue) {
        if (uploadedValue == null || uploadedValue.isBlank()) {
            return uploadedValue;
        }
        FieldDefinition.RefTarget refTarget = requireRefTarget(field);
        String targetModel = refTarget.getTargetEntity();
        String valueField = textOrDefault(refTarget.getValueField(), "pid");

        // Keep the existing public-PID contract. Permission and tenant visibility are checked by
        // getById; failures fall through to explicitly configured business keys.
        try {
            Map<String, Object> byPid = dynamicDataService.getById(targetModel, uploadedValue);
            String resolved = readStoredValue(byPid, valueField);
            if (resolved != null) {
                return resolved;
            }
        } catch (Exception ignored) {
            // A non-PID business value is expected to miss this fast path.
        }

        List<String> matchFields = normalizedMatchFields(refTarget);
        if (matchFields.isEmpty()) {
            throw unresolved(field, uploadedValue, List.of("PID"));
        }

        Map<String, Map<String, Object>> uniqueMatches = new LinkedHashMap<>();
        for (String matchField : matchFields) {
            PaginationResult<Map<String, Object>> result;
            try {
                result = dynamicDataService.list(targetModel, DynamicQueryRequest.builder()
                        .pageNum(1)
                        .pageSize(2)
                        .conditions(List.of(QueryCondition.builder()
                                .fieldName(matchField)
                                .operator(QueryCondition.Operator.EQ)
                                .value(uploadedValue)
                                .build()))
                        .build());
            } catch (Exception lookupError) {
                // Keep SQL/mapper/authorization internals in server logs. The spreadsheet user
                // only needs the fail-closed, actionable reference outcome.
                log.warn("Excel reference lookup failed: targetModel={}, field={}, matchField={}",
                        targetModel, field == null ? null : field.getCode(), matchField, lookupError);
                throw unresolved(field, uploadedValue, matchFields);
            }

            List<Map<String, Object>> records = result == null || result.getRecords() == null
                    ? List.of() : result.getRecords();
            long total = result == null || result.getTotal() == null ? records.size() : result.getTotal();
            if (total > 1 || records.size() > 1) {
                throw ambiguous(field, uploadedValue, matchField);
            }
            for (Map<String, Object> record : records) {
                String storedValue = readStoredValue(record, valueField);
                if (storedValue == null) {
                    throw new BusinessException("Reference target for " + fieldLabel(field)
                            + " does not expose stored field " + valueField);
                }
                uniqueMatches.put(storedValue, record);
            }
        }

        if (uniqueMatches.size() > 1) {
            throw ambiguous(field, uploadedValue, String.join(", ", matchFields));
        }
        if (uniqueMatches.isEmpty()) {
            throw unresolved(field, uploadedValue, matchFields);
        }
        return uniqueMatches.keySet().iterator().next();
    }

    /** Human-readable, stable template help for an opt-in business-key reference field. */
    public String importHint(FieldDefinition field) {
        if (field == null || field.getRefTarget() == null) {
            return null;
        }
        List<String> matchFields = normalizedMatchFields(field.getRefTarget());
        if (matchFields.isEmpty()) {
            return null;
        }
        Map<String, String> labels = new LinkedHashMap<>();
        try {
            List<FieldDefinition> targetFields = metaModelService.getModelFields(
                    field.getRefTarget().getTargetEntity());
            if (targetFields != null) {
                for (FieldDefinition targetField : targetFields) {
                    labels.put(targetField.getCode(), fieldLabel(targetField));
                }
            }
        } catch (Exception ignored) {
            // Codes are still a complete, deterministic fallback for the generated workbook.
        }
        List<String> accepted = matchFields.stream()
                .map(code -> labels.getOrDefault(code, code) + " (" + code + ")")
                .toList();
        return "可填写 " + String.join(" / ", accepted)
                + "；仍兼容内部 PID；值必须唯一且当前用户可读取。";
    }

    private FieldDefinition.RefTarget requireRefTarget(FieldDefinition field) {
        FieldDefinition.RefTarget refTarget = field == null ? null : field.getRefTarget();
        if (refTarget == null || refTarget.getTargetEntity() == null
                || refTarget.getTargetEntity().isBlank()) {
            throw new BusinessException("Reference target is not configured for " + fieldLabel(field));
        }
        return refTarget;
    }

    private List<String> normalizedMatchFields(FieldDefinition.RefTarget refTarget) {
        if (refTarget == null || refTarget.getImportMatchFields() == null) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String field : refTarget.getImportMatchFields()) {
            if (field != null && !field.isBlank()) {
                unique.add(field.trim());
            }
        }
        return new ArrayList<>(unique);
    }

    private String readStoredValue(Map<String, Object> record, String valueField) {
        if (record == null || record.isEmpty()) {
            return null;
        }
        Object value = record.get(valueField);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private BusinessException ambiguous(FieldDefinition field, String value, String matchField) {
        return new BusinessException("Reference value is ambiguous for " + fieldLabel(field)
                + ": '" + value + "' matched multiple records via " + matchField);
    }

    private BusinessException unresolved(FieldDefinition field, String value, List<String> fields) {
        return new BusinessException("Referenced record does not exist or is not accessible for "
                + fieldLabel(field) + ": '" + value + "' (accepted: "
                + String.join(", ", fields) + ")");
    }

    private String fieldLabel(FieldDefinition field) {
        if (field == null) {
            return "reference field";
        }
        return field.getDisplayName() == null || field.getDisplayName().isBlank()
                ? field.getCode() : field.getDisplayName();
    }

    private String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

}
