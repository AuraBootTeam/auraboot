package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;
import java.util.HashMap;

/**
 * 模型统计信息DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaModelStatistics {

    /**
     * 租户ID
     */
    private Long tenantId;

      

    

    /**
     * 模型总数
     */
    private Long totalModels;

    /**
     * 当前版本模型数
     */
    private Long currentVersionModels;

    /**
     * 已发布模型数
     */
    private Long publishedModels;

    /**
     * 草稿模型数
     */
    private Long draftModels;

    /**
     * 归档模型数
     */
    private Long archivedModels;

    /**
     * 按模型类型分组统计
     */
    @Builder.Default
    private Map<String, Long> modelsByType = new HashMap<>();

    /**
     * 按状态分组统计
     */
    @Builder.Default
    private Map<String, Long> modelsByStatus = new HashMap<>();

    /**
     * 字段绑定统计
     */
    @Builder.Default
    private Map<String, Long> fieldBindingStats = new HashMap<>();

    /**
     * 被引用次数最多的模型
     */
    @Builder.Default
    private Map<String, Long> mostReferencedModels = new HashMap<>();

    /**
     * 最近创建的模型数（7天内）
     */
    private Long recentlyCreatedModels;

    /**
     * 最近更新的模型数（7天内）
     */
    private Long recentlyUpdatedModels;

    /**
     * 统计时间戳
     */
    private Long statisticsAt;

    /**
     * 添加模型类型统计
     * @param modelType 模型类型
     * @param count 数量
     */
    public void addModelTypeCount(String modelType, Long count) {
        if (modelsByType == null) {
            modelsByType = new HashMap<>();
        }
        modelsByType.put(modelType, count);
    }

    /**
     * 添加状态统计
     * @param status 状态
     * @param count 数量
     */
    public void addStatusCount(String status, Long count) {
        if (modelsByStatus == null) {
            modelsByStatus = new HashMap<>();
        }
        modelsByStatus.put(status, count);
    }

    /**
     * 添加字段绑定统计
     * @param statType 统计类型
     * @param count 数量
     */
    public void addFieldBindingCount(String statType, Long count) {
        if (fieldBindingStats == null) {
            fieldBindingStats = new HashMap<>();
        }
        fieldBindingStats.put(statType, count);
    }

    /**
     * 添加模型引用统计
     * @param modelCode 模型编码
     * @param referenceCount 引用次数
     */
    public void addModelReferenceCount(String modelCode, Long referenceCount) {
        if (mostReferencedModels == null) {
            mostReferencedModels = new HashMap<>();
        }
        mostReferencedModels.put(modelCode, referenceCount);
    }

    /**
     * 获取模型使用率
     * @return 使用率百分比
     */
    public Double getModelUsageRate() {
        if (totalModels == null || totalModels == 0) {
            return 0.0;
        }
        Long usedModels = publishedModels != null ? publishedModels : 0L;
        return (usedModels.doubleValue() / totalModels.doubleValue()) * 100.0;
    }

    /**
     * 获取模型发布率
     * @return 发布率百分比
     */
    public Double getModelPublishRate() {
        if (currentVersionModels == null || currentVersionModels == 0) {
            return 0.0;
        }
        Long published = publishedModels != null ? publishedModels : 0L;
        return (published.doubleValue() / currentVersionModels.doubleValue()) * 100.0;
    }

    /**
     * 获取平均字段数
     * @return 平均字段数
     */
    public Double getAverageFieldCount() {
        if (currentVersionModels == null || currentVersionModels == 0) {
            return 0.0;
        }
        Long totalFields = fieldBindingStats.getOrDefault("totalBindings", 0L);
        return totalFields.doubleValue() / currentVersionModels.doubleValue();
    }
}