package com.auraboot.framework.meta.util;

import com.auraboot.framework.meta.dto.DynamicBatchResponse;
import com.auraboot.framework.meta.dto.PaginationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRecordSanitizerTest {

    @Test
    void sanitizeRecordRemovesOnlyTopLevelInternalSystemFields() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", 1001L);
        record.put("pid", "rec-pid-1");
        record.put("tenant_id", 321L);
        record.put("created_by", 7L);
        record.put("updated_by", 8L);
        record.put("material_id", "MAT-001");
        record.put("external_order_id", "SO-001");
        record.put("nested", Map.of("id", 2002L, "pid", "nested-pid"));

        Map<String, Object> sanitized = PublicRecordSanitizer.sanitizeRecord(record);

        assertThat(sanitized).containsEntry("pid", "rec-pid-1");
        assertThat(sanitized).containsEntry("material_id", "MAT-001");
        assertThat(sanitized).containsEntry("external_order_id", "SO-001");
        assertThat(sanitized).doesNotContainKeys("id", "tenant_id", "created_by", "updated_by");
        assertThat(((Map<?, ?>) sanitized.get("nested")).get("id")).isEqualTo(2002L);
        assertThat(record).containsKey("id");
    }

    @Test
    void sanitizePaginationResultRemovesInternalFieldsFromEachRecord() {
        PaginationResult<Map<String, Object>> result = PaginationResult.of(
                List.of(row(1L, "p1"), row(2L, "p2")), 2L, 1, 20);

        PaginationResult<Map<String, Object>> sanitized = PublicRecordSanitizer.sanitizePage(result);

        assertThat(sanitized.getRecords()).hasSize(2);
        assertThat(sanitized.getRecords().get(0)).containsEntry("pid", "p1");
        assertThat(sanitized.getRecords().get(0)).doesNotContainKeys("id", "tenant_id", "created_by", "updated_by");
        assertThat(sanitized.getTotal()).isEqualTo(2L);
        assertThat(sanitized.getPage()).isEqualTo(1);
        assertThat(sanitized.getPageSize()).isEqualTo(20);
    }

    @Test
    void sanitizeBatchResponseCleansSuccessAndErrorRows() {
        DynamicBatchResponse response = new DynamicBatchResponse();
        response.setTotal(2);
        response.setSuccess(1);
        response.setFailed(1);
        response.setSuccessItems(new ArrayList<>(List.of(row(10L, "ok-pid"))));
        DynamicBatchResponse.BatchErrorItem failed = new DynamicBatchResponse.BatchErrorItem();
        failed.setIndex(1);
        failed.setData(row(11L, "bad-pid"));
        failed.setError("invalid");
        response.setFailedItems(new ArrayList<>(List.of(failed)));

        DynamicBatchResponse sanitized = PublicRecordSanitizer.sanitizeBatch(response);

        assertThat(sanitized.getSuccessItems().get(0)).containsEntry("pid", "ok-pid");
        assertThat(sanitized.getSuccessItems().get(0)).doesNotContainKey("id");
        assertThat(sanitized.getFailedItems().get(0).getData()).containsEntry("pid", "bad-pid");
        assertThat(sanitized.getFailedItems().get(0).getData()).doesNotContainKeys("id", "tenant_id");
    }

    private static Map<String, Object> row(Long id, String pid) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("pid", pid);
        row.put("tenant_id", 321L);
        row.put("created_by", 7L);
        row.put("updated_by", 8L);
        row.put("name", "record-" + pid);
        return row;
    }
}
