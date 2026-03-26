package com.auraboot.framework.meta.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.HashMap;

/**
 * 绑定关系统计信息DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingStatistics {

    /**
     * 统计时间
     */
    private LocalDateTime statisticsTime;

    /**
     * 租户ID
     */
    private Long tenantId;

      

    

    /**
     * 总绑定关系数量
     */
    private Long totalBindings;

    /**
     * 活跃绑定关系数量
     */
    private Long activeBindings;

    /**
     * 模型数量
     */
    private Long modelCount;

    /**
     * 字段数量
     */
    private Long fieldCount;

    /**
     * 平均每个模型的字段数量
     */
    private Double avgFieldsPerModel;

    /**
     * 平均每个字段被使用的次数
     */
    private Double avgUsagePerField;

    /**
     * 按模型分组的统计
     */
    private Map<String, ModelBindingStats> modelStats;

    /**
     * 按字段类型分组的统计
     */
    private Map<String, FieldTypeStats> fieldTypeStats;

    /**
     * 按状态分组的统计
     */
    private Map<String, Long> statusStats;

    /**
     * 兼容性统计
     */
    private CompatibilityStats compatibilityStats;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 构造函数
     */
    public BindingStatistics() {
        this.statisticsTime = LocalDateTime.now(ZoneOffset.UTC);
        this.modelStats = new HashMap<>();
        this.fieldTypeStats = new HashMap<>();
        this.statusStats = new HashMap<>();
    }

    /**
     * 模型绑定统计
     */
    @Data
    public static class ModelBindingStats {
        /**
         * 模型编码
         */
        private String modelCode;

        /**
         * 模型名称
         */
        private String modelName;

        /**
         * 绑定字段数量
         */
        private Long fieldCount;

        /**
         * 必填字段数量
         */
        private Long requiredFieldCount;

        /**
         * 只读字段数量
         */
        private Long readonlyFieldCount;

        /**
         * 隐藏字段数量
         */
        private Long hiddenFieldCount;

        /**
         * 最后更新时间
         */
        private LocalDateTime lastUpdated;
    }

    /**
     * 字段类型统计
     */
    @Data
    public static class FieldTypeStats {
        /**
         * 字段类型
         */
        private String fieldType;

        /**
         * 使用次数
         */
        private Long usageCount;

        /**
         * 使用百分比
         */
        private Double usagePercentage;

        /**
         * 平均排序位置
         */
        private Double avgOrder;
    }

    /**
     * 兼容性统计
     */
    @Data
    public static class CompatibilityStats {
        /**
         * 兼容的绑定关系数量
         */
        private Long compatibleCount;

        /**
         * 部分兼容的绑定关系数量
         */
        private Long partiallyCompatibleCount;

        /**
         * 不兼容的绑定关系数量
         */
        private Long incompatibleCount;

        /**
         * 未检查的绑定关系数量
         */
        private Long uncheckedCount;

        /**
         * 兼容性百分比
         */
        private Double compatibilityPercentage;
    }

    /**
     * 计算兼容性百分比
     */
    public void calculateCompatibilityPercentage() {
        if (compatibilityStats != null && totalBindings > 0) {
            double compatible = compatibilityStats.getCompatibleCount() + 
                              compatibilityStats.getPartiallyCompatibleCount() * 0.5;
            compatibilityStats.setCompatibilityPercentage(compatible / totalBindings * 100);
        }
    }

    /**
     * 添加模型统计
     */
    public void addModelStats(String modelCode, ModelBindingStats stats) {
        this.modelStats.put(modelCode, stats);
    }

    /**
     * 添加字段类型统计
     */
    public void addFieldTypeStats(String fieldType, FieldTypeStats stats) {
        this.fieldTypeStats.put(fieldType, stats);
    }

    /**
     * 添加状态统计
     */
    public void addStatusStats(String status, Long count) {
        this.statusStats.put(status, count);
    }
}
