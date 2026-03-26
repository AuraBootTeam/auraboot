package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;

/**
 * 字典项使用情况DTO
 * 用于字典项使用情况的返回
 */
@Data
public class DictItemUsageInfo {

    /**
     * 字典项PID
     */
    private String itemPid;

    /**
     * 字典项值
     */
    private String itemValue;

    /**
     * 字典项标签
     */
    private String itemLabel;

    /**
     * 是否被使用
     */
    private Boolean inUse;

    /**
     * 使用次数
     */
    private Integer usageCount;

    /**
     * 使用的实体列表
     */
    private List<EntityUsage> entityUsages;

    /**
     * 使用的字段列表
     */
    private List<FieldUsage> fieldUsages;

    /**
     * 最后使用时间
     */
    private Long lastUsedAt;

    /**
     * 统计时间戳
     */
    private Long statisticsTimestamp;

    /**
     * 构造函数
     */
    public DictItemUsageInfo() {
        this.inUse = false;
        this.usageCount = 0;
        this.statisticsTimestamp = System.currentTimeMillis();
    }

    /**
     * 实体使用情况
     */
    @Data
    public static class EntityUsage {
        /**
         * 实体PID
         */
        private String entityPid;

        /**
         * 实体名称
         */
        private String entityName;

        /**
         * 使用次数
         */
        private Integer count;
    }

    /**
     * 字段使用情况
     */
    @Data
    public static class FieldUsage {
        /**
         * 字段PID
         */
        private String fieldPid;

        /**
         * 字段名称
         */
        private String fieldName;

        /**
         * 实体PID
         */
        private String entityPid;

        /**
         * 实体名称
         */
        private String entityName;

        /**
         * 使用次数
         */
        private Integer count;
    }
}