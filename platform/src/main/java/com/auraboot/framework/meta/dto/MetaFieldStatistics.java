package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;
import java.util.HashMap;

/**
 * 字段统计信息DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaFieldStatistics {

    /**
     * 租户ID
     */
    private Long tenantId;

      

    

    /**
     * 字段总数
     */
    private Long totalFields;

    /**
     * 当前版本字段数
     */
    private Long currentVersionFields;

    /**
     * 已发布字段数
     */
    private Long publishedFields;

    /**
     * 草稿字段数
     */
    private Long draftFields;

    /**
     * 归档字段数
     */
    private Long archivedFields;

    /**
     * 按数据类型分组统计
     */
    @Builder.Default
    private Map<String, Long> fieldsByDataType = new HashMap<>();

    /**
     * 按状态分组统计
     */
    @Builder.Default
    private Map<String, Long> fieldsByStatus = new HashMap<>();

    /**
     * 按数据源分组统计
     */
    @Builder.Default
    private Map<String, Long> fieldsByDataSource = new HashMap<>();

    /**
     * 被引用次数最多的字段
     */
    @Builder.Default
    private Map<String, Long> mostReferencedFields = new HashMap<>();

    /**
     * 最近创建的字段数（7天内）
     */
    private Long recentlyCreatedFields;

    /**
     * 最近更新的字段数（7天内）
     */
    private Long recentlyUpdatedFields;

    /**
     * 统计时间戳
     */
    private Long statisticsAt;

    /**
     * 添加数据类型统计
     * @param dataType 数据类型
     * @param count 数量
     */
    public void addDataTypeCount(String dataType, Long count) {
        if (fieldsByDataType == null) {
            fieldsByDataType = new HashMap<>();
        }
        fieldsByDataType.put(dataType, count);
    }

    /**
     * 添加状态统计
     * @param status 状态
     * @param count 数量
     */
    public void addStatusCount(String status, Long count) {
        if (fieldsByStatus == null) {
            fieldsByStatus = new HashMap<>();
        }
        fieldsByStatus.put(status, count);
    }

    /**
     * 添加数据源统计
     * @param dataSource 数据源
     * @param count 数量
     */
    public void addDataSourceCount(String dataSource, Long count) {
        if (fieldsByDataSource == null) {
            fieldsByDataSource = new HashMap<>();
        }
        fieldsByDataSource.put(dataSource, count);
    }

    /**
     * 添加字段引用统计
     * @param code 字段键
     * @param referenceCount 引用次数
     */
    public void addFieldReferenceCount(String code, Long referenceCount) {
        if (mostReferencedFields == null) {
            mostReferencedFields = new HashMap<>();
        }
        mostReferencedFields.put(code, referenceCount);
    }

    /**
     * 获取字段使用率
     * @return 使用率百分比
     */
    public Double getFieldUsageRate() {
        if (totalFields == null || totalFields == 0) {
            return 0.0;
        }
        Long usedFields = publishedFields != null ? publishedFields : 0L;
        return (usedFields.doubleValue() / totalFields.doubleValue()) * 100.0;
    }

    /**
     * 获取字段发布率
     * @return 发布率百分比
     */
    public Double getFieldPublishRate() {
        if (currentVersionFields == null || currentVersionFields == 0) {
            return 0.0;
        }
        Long published = publishedFields != null ? publishedFields : 0L;
        return (published.doubleValue() / currentVersionFields.doubleValue()) * 100.0;
    }
}