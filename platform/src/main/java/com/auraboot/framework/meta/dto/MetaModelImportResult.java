package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 模型导入结果DTO
 * 用于模型导入功能的结果返回
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaModelImportResult {

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 错误信息
     */
    private String errorMessage;

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
    private Integer skippedCount;

    /**
     * 成功导入的模型
     */
    private List<MetaModelDTO> successModels;

    /**
     * 失败的模型
     */
    private List<MetaModelImportError> failureModels;

    /**
     * 导入开始时间
     */
    private Long importStartTime;

    /**
     * 导入结束时间
     */
    private Long importEndTime;

    /**
     * 导入耗时（毫秒）
     */
    private Long importDuration;

    /**
     * 导入时间戳
     */
    private Long importTimestamp;

    /**
     * 设置导入开始时间
     */
    public void setImportStartTime(Long importStartTime) {
        this.importStartTime = importStartTime;
    }

    /**
     * 获取导入开始时间
     */
    public Long getImportStartTime() {
        return this.importStartTime;
    }

    /**
     * 设置导入结束时间
     */
    public void setImportEndTime(Long importEndTime) {
        this.importEndTime = importEndTime;
    }

    /**
     * 获取导入结束时间
     */
    public Long getImportEndTime() {
        return this.importEndTime;
    }

    /**
     * 设置导入耗时
     */
    public void setImportDuration(Long importDuration) {
        this.importDuration = importDuration;
    }

    /**
     * 添加成功模型
     */
    public void addSuccessModel(String pid, String code, String displayName, String modelType, String operation) {
        // 实现添加成功模型的逻辑
        if (this.successCount == null) {
            this.successCount = 0;
        }
        this.successCount++;
    }

    /**
     * 添加失败模型
     */
    public void addFailedModel(String code, String displayName, String errorMessage, String errorType, String stackTrace, Object additionalInfo) {
        // 实现添加失败模型的逻辑
        if (this.failureCount == null) {
            this.failureCount = 0;
        }
        this.failureCount++;
    }

    /**
     * 设置成功状态
     */
    public void setSuccess() {
        this.success = true;
        this.errorMessage = null;
    }

    /**
     * 设置失败状态
     * @param errorMessage 错误信息
     */
    public void setFailure(String errorMessage) {
        this.success = false;
        this.errorMessage = errorMessage;
    }

    /**
     * 模型导入错误信息
     */
    @Data
    public static class MetaModelImportError {
        /**
         * 行号
         */
        private Integer rowIndex;

        /**
         * 模型编码
         */
        private String code;

        /**
         * 模型名称
         */
        private String displayName;

        /**
         * 错误信息
         */
        private String errorMessage;
    }
}