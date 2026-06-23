package com.auraboot.framework.meta.util;

import com.auraboot.framework.meta.dto.DynamicBatchResponse;
import com.auraboot.framework.meta.dto.PaginationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes platform-internal row identifiers from public dynamic-record payloads.
 */
public final class PublicRecordSanitizer {

    private static final Set<String> INTERNAL_FIELDS = Set.of(
            "id",
            "tenant_id",
            "created_by",
            "updated_by"
    );

    private PublicRecordSanitizer() {
    }

    public static Map<String, Object> sanitizeRecord(Map<String, Object> record) {
        if (record == null) {
            return null;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            if (!INTERNAL_FIELDS.contains(entry.getKey())) {
                sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        return sanitized;
    }

    public static List<Map<String, Object>> sanitizeRecords(List<Map<String, Object>> records) {
        if (records == null) {
            return null;
        }
        List<Map<String, Object>> sanitized = new ArrayList<>(records.size());
        for (Map<String, Object> record : records) {
            sanitized.add(sanitizeRecord(record));
        }
        return sanitized;
    }

    public static PaginationResult<Map<String, Object>> sanitizePage(
            PaginationResult<Map<String, Object>> result) {
        if (result == null) {
            return null;
        }
        PaginationResult<Map<String, Object>> sanitized = new PaginationResult<>(
                sanitizeRecords(result.getRecords()),
                result.getTotal(),
                result.getPage(),
                result.getPageSize()
        );
        sanitized.setTotalPages(result.getTotalPages());
        sanitized.setNextCursor(result.getNextCursor());
        return sanitized;
    }

    public static DynamicBatchResponse sanitizeBatch(DynamicBatchResponse response) {
        if (response == null) {
            return null;
        }
        DynamicBatchResponse sanitized = new DynamicBatchResponse();
        sanitized.setTotal(response.getTotal());
        sanitized.setSuccess(response.getSuccess());
        sanitized.setFailed(response.getFailed());
        sanitized.setSkipped(response.getSkipped());
        sanitized.setErrors(response.getErrors() == null ? null : new ArrayList<>(response.getErrors()));
        sanitized.setSuccessItems(sanitizeRecords(response.getSuccessItems()));
        sanitized.setFailedItems(sanitizeErrorItems(response.getFailedItems()));
        sanitized.setSkippedItems(sanitizeErrorItems(response.getSkippedItems()));
        sanitized.setDuration(response.getDuration());
        sanitized.setMetadata(response.getMetadata() == null ? null : new LinkedHashMap<>(response.getMetadata()));
        return sanitized;
    }

    private static List<DynamicBatchResponse.BatchErrorItem> sanitizeErrorItems(
            List<DynamicBatchResponse.BatchErrorItem> items) {
        if (items == null) {
            return null;
        }
        List<DynamicBatchResponse.BatchErrorItem> sanitized = new ArrayList<>(items.size());
        for (DynamicBatchResponse.BatchErrorItem item : items) {
            if (item == null) {
                sanitized.add(null);
                continue;
            }
            DynamicBatchResponse.BatchErrorItem copy = new DynamicBatchResponse.BatchErrorItem();
            copy.setIndex(item.getIndex());
            copy.setData(sanitizeRecord(item.getData()));
            copy.setError(item.getError());
            copy.setErrorCode(item.getErrorCode());
            sanitized.add(copy);
        }
        return sanitized;
    }
}
