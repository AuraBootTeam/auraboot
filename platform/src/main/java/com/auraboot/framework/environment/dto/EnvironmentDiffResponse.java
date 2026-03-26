package com.auraboot.framework.environment.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for environment configuration diff.
 */
@Data
public class EnvironmentDiffResponse {

    private String sourceCode;
    private String targetCode;

    private List<DiffEntry> differences;

    @Data
    public static class DiffEntry {
        /** Config key path, e.g. "apiBaseUrl" or "dbConnectionInfo.host" */
        private String key;
        private Object sourceValue;
        private Object targetValue;
        /** ADDED, REMOVED, CHANGED */
        private String changeType;
    }
}
