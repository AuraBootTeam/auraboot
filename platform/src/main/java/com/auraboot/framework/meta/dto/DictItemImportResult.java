package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;

/**
 * 字典项导入结果DTO
 * 用于字典项导入功能的结果返回
 */
@Data
public class DictItemImportResult {

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
    private Integer skipCount;

    /**
     * 成功导入的字典项
     */
    private List<DictItemDTO> successItems;

    /**
     * 失败的字典项
     */
    private List<DictItemImportError> failureItems;

    /**
     * 导入耗时（毫秒）
     */
    private Long importDuration;

    /**
     * 导入时间戳
     */
    private Long importTimestamp;

    /**
     * 构造函数
     */
    public DictItemImportResult() {
        this.success = false;
        this.totalCount = 0;
        this.successCount = 0;
        this.failureCount = 0;
        this.skipCount = 0;
        this.importTimestamp = System.currentTimeMillis();
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
     * 字典项导入错误信息
     */
    @Data
    public static class DictItemImportError {
        /**
         * 行号
         */
        private Integer rowIndex;

        /**
         * 字典项值
         */
        private String value;

        /**
         * 字典项标签
         */
        private String label;

        /**
         * 错误信息
         */
        private String errorMessage;
    }
}