package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 页面Schema版本比较结果DTO
 * 用于展示两个版本之间的差异信息
 * 
 * @author AuraBoot Framework
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class PageSchemaVersionComparisonDTO {

    /**
     * 源版本信息
     */
    private VersionInfo sourceVersion;

    /**
     * 目标版本信息
     */
    private VersionInfo targetVersion;

    /**
     * 差异列表
     */
    private List<FieldDifference> differences;

    /**
     * 比较摘要
     */
    private ComparisonSummary summary;

    /**
     * 版本信息内部类
     */
    @Data
    public static class VersionInfo {
        private Long historyId;
        private String pagePid;
        private Integer version;
        private String semver;
        private String operation;
        private LocalDateTime operationTime;
        private String operatorPid;
    }

    /**
     * 字段差异内部类
     */
    @Data
    public static class FieldDifference {
        /**
         * 字段路径，如 "name", "blocks[0].fields[0].label"
         */
        private String fieldPath;

        /**
         * 差异类型：ADDED, REMOVED, MODIFIED
         */
        private DifferenceType type;

        /**
         * 源值
         */
        private Object sourceValue;

        /**
         * 目标值
         */
        private Object targetValue;

        /**
         * 字段描述
         */
        private String description;
    }

    /**
     * 比较摘要内部类
     */
    @Data
    public static class ComparisonSummary {
        /**
         * 总差异数量
         */
        private Integer totalDifferences;

        /**
         * 新增字段数量
         */
        private Integer addedFields;

        /**
         * 删除字段数量
         */
        private Integer removedFields;

        /**
         * 修改字段数量
         */
        private Integer modifiedFields;

        /**
         * 是否有重大变更
         */
        private Boolean hasMajorChanges;

        /**
         * 变更类别统计
         */
        private Map<String, Integer> changesByCategory;
    }

    /**
     * 差异类型枚举
     */
    public enum DifferenceType {
        ADDED,      // 新增
        REMOVED,    // 删除
        MODIFIED    // 修改
    }
}