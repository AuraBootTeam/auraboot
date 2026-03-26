package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 命名查询版本比较结果DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryVersionCompareResult {

    /**
     * 查询ID
     */
    private Long queryId;

    /**
     * 源版本ID
     */
    private Long fromVersionId;

    /**
     * 目标版本ID
     */
    private Long toVersionId;

    /**
     * 源版本号
     */
    private String fromVersion;

    /**
     * 目标版本号
     */
    private String toVersion;

    /**
     * 比较时间
     */
    private LocalDateTime compareTime;

    /**
     * 是否有差异
     */
    private Boolean hasDifferences;

    /**
     * 差异数量
     */
    private Integer differenceCount;

    /**
     * 字段差异列表
     */
    private List<FieldDifference> fieldDifferences;

    /**
     * SQL差异
     */
    private SqlDifference sqlDifference;

    /**
     * 配置差异
     */
    private Map<String, Object> configDifferences;

    /**
     * 比较摘要
     */
    private String compareSummary;

    /**
     * 字段差异内部类
     */
    @Data
    public static class FieldDifference {
        private String fieldName;
        private String changeType; // ADDED, REMOVED, MODIFIED
        private Object oldValue;
        private Object newValue;
        private String description;
    }

    /**
     * SQL差异内部类
     */
    @Data
    public static class SqlDifference {
        private String oldSql;
        private String newSql;
        private List<String> addedLines;
        private List<String> removedLines;
        private List<String> modifiedLines;
        private String diffSummary;
    }

    public NamedQueryVersionCompareResult() {
        this.compareTime = LocalDateTime.now(ZoneOffset.UTC);
    }
}