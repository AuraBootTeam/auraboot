package com.auraboot.framework.governance.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Response DTO showing field-level diff between two versions.
 */
@Data
public class VersionDiffResponse {

    private Integer fromVersion;
    private Integer toVersion;
    private List<FieldDiff> changes;

    @Data
    public static class FieldDiff {
        private String fieldName;
        private Object oldValue;
        private Object newValue;

        public FieldDiff() {}

        public FieldDiff(String fieldName, Object oldValue, Object newValue) {
            this.fieldName = fieldName;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
    }
}
