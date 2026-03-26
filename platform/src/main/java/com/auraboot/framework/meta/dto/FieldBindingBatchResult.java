package com.auraboot.framework.meta.dto;

import lombok.Data;
import java.util.List;
import java.util.ArrayList;

/**
 * 批量字段绑定结果DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class FieldBindingBatchResult {

    /**
     * 操作是否成功
     */
    private Boolean success;

    /**
     * 总数量
     */
    private Integer totalCount;

    /**
     * 成功数量
     */
    private Integer successCount;

    /**
     * 失败数量
     */
    private Integer failureCount;

    /**
     * 跳过数量
     */
    private Integer skipCount;

    /**
     * 成功的绑定关系列表
     */
    private List<MetaModelFieldBindingDTO> successBindings;

    /**
     * 失败的绑定项列表
     */
    private List<FailureItem> failureItems;

    /**
     * 跳过的绑定项列表
     */
    private List<SkipItem> skipItems;

    /**
     * 操作消息
     */
    private String message;

    /**
     * 处理时间（毫秒）
     */
    private Long processingTime;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 构造函数
     */
    public FieldBindingBatchResult() {
        this.success = true;
        this.totalCount = 0;
        this.successCount = 0;
        this.failureCount = 0;
        this.skipCount = 0;
        this.successBindings = new ArrayList<>();
        this.failureItems = new ArrayList<>();
        this.skipItems = new ArrayList<>();
    }

    /**
     * 添加成功的绑定关系
     */
    public void addSuccessBinding(MetaModelFieldBindingDTO binding) {
        this.successBindings.add(binding);
        this.successCount++;
        this.totalCount++;
    }

    /**
     * 添加失败的绑定项
     */
    public void addFailureItem(Long fieldId, String reason, String details) {
        this.failureItems.add(new FailureItem(fieldId, reason, details));
        this.failureCount++;
        this.totalCount++;
        this.success = false;
    }

    /**
     * 添加跳过的绑定项
     */
    public void addSkipItem(Long fieldId, String reason) {
        this.skipItems.add(new SkipItem(fieldId, reason));
        this.skipCount++;
        this.totalCount++;
    }

    /**
     * 失败项
     */
    @Data
    public static class FailureItem {
        /**
         * 字段ID
         */
        private Long fieldId;

        /**
         * 失败原因
         */
        private String reason;

        /**
         * 详细信息
         */
        private String details;

        public FailureItem(Long fieldId, String reason, String details) {
            this.fieldId = fieldId;
            this.reason = reason;
            this.details = details;
        }
    }

    /**
     * 跳过项
     */
    @Data
    public static class SkipItem {
        /**
         * 字段ID
         */
        private Long fieldId;

        /**
         * 跳过原因
         */
        private String reason;

        public SkipItem(Long fieldId, String reason) {
            this.fieldId = fieldId;
            this.reason = reason;
        }
    }

    /**
     * 检查是否有失败项
     */
    public boolean hasFailures() {
        return failureCount > 0;
    }

    /**
     * 检查是否有跳过项
     */
    public boolean hasSkips() {
        return skipCount > 0;
    }

    /**
     * 获取成功率
     */
    public double getSuccessRate() {
        if (totalCount == 0) {
            return 1.0;
        }
        return (double) successCount / totalCount;
    }
}