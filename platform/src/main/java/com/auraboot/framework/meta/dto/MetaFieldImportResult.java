package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.ArrayList;

/**
 * 字段导入结果DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaFieldImportResult {

    /**
     * 导入是否成功
     */
    private Boolean success;

    /**
     * 总导入数量
     */
    private Integer totalCount;

    /**
     * 成功导入数量
     */
    private Integer successCount;

    /**
     * 失败导入数量
     */
    private Integer failureCount;

    /**
     * 跳过导入数量
     */
    private Integer skippedCount;

    /**
     * 成功导入的字段列表
     */
    @Builder.Default
    private List<ImportedField> successFields = new ArrayList<>();

    /**
     * 失败导入的字段列表
     */
    @Builder.Default
    private List<FailedField> failedFields = new ArrayList<>();

    /**
     * 跳过导入的字段列表
     */
    @Builder.Default
    private List<SkippedField> skippedFields = new ArrayList<>();

    /**
     * 导入警告信息
     */
    @Builder.Default
    private List<ImportWarning> warnings = new ArrayList<>();

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
     * 导入操作员
     */
    private String importOperator;

    /**
     * 成功导入的字段信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportedField {
        /**
         * 字段PID
         */
        private String fieldPid;

        /**
         * 字段键
         */
        private String code;

        /**
         * 字段显示名称
         */
        private String displayName;

        /**
         * 数据类型
         */
        private String dataType;

        /**
         * 导入操作类型（创建、更新）
         */
        private String operationType;

        /**
         * 导入时间
         */
        private Long importedAt;
    }

    /**
     * 失败导入的字段信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedField {
        /**
         * 字段键
         */
        private String code;

        /**
         * 字段显示名称
         */
        private String displayName;

        /**
         * 失败原因
         */
        private String failureReason;

        /**
         * 错误代码
         */
        private String errorCode;

        /**
         * 错误详情
         */
        private String errorDetails;

        /**
         * 行号（如果从文件导入）
         */
        private Integer lineNumber;
    }

    /**
     * 跳过导入的字段信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkippedField {
        /**
         * 字段键
         */
        private String code;

        /**
         * 字段显示名称
         */
        private String displayName;

        /**
         * 跳过原因
         */
        private String skipReason;

        /**
         * 行号（如果从文件导入）
         */
        private Integer lineNumber;
    }

    /**
     * 导入警告信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportWarning {
        /**
         * 警告代码
         */
        private String warningCode;

        /**
         * 警告消息
         */
        private String warningMessage;

        /**
         * 相关字段键
         */
        private String code;

        /**
         * 行号（如果从文件导入）
         */
        private Integer lineNumber;

        /**
         * 建议操作
         */
        private String suggestion;
    }

    /**
     * 添加成功导入的字段
     * @param fieldPid 字段PID
     * @param code 字段键
     * @param displayName 显示名称
     * @param dataType 数据类型
     * @param operationType 操作类型
     */
    public void addSuccessField(String fieldPid, String code, String displayName, String dataType, String operationType) {
        if (successFields == null) {
            successFields = new ArrayList<>();
        }
        successFields.add(ImportedField.builder()
            .fieldPid(fieldPid)
            .code(code)
            .displayName(displayName)
            .dataType(dataType)
            .operationType(operationType)
            .importedAt(System.currentTimeMillis())
            .build());
    }

    /**
     * 添加失败导入的字段
     * @param code 字段键
     * @param displayName 显示名称
     * @param failureReason 失败原因
     * @param errorCode 错误代码
     * @param errorDetails 错误详情
     * @param lineNumber 行号
     */
    public void addFailedField(String code, String displayName, String failureReason, String errorCode, String errorDetails, Integer lineNumber) {
        if (failedFields == null) {
            failedFields = new ArrayList<>();
        }
        failedFields.add(FailedField.builder()
            .code(code)
            .displayName(displayName)
            .failureReason(failureReason)
            .errorCode(errorCode)
            .errorDetails(errorDetails)
            .lineNumber(lineNumber)
            .build());
    }

    /**
     * 添加跳过导入的字段
     * @param code 字段键
     * @param displayName 显示名称
     * @param skipReason 跳过原因
     * @param lineNumber 行号
     */
    public void addSkippedField(String code, String displayName, String skipReason, Integer lineNumber) {
        if (skippedFields == null) {
            skippedFields = new ArrayList<>();
        }
        skippedFields.add(SkippedField.builder()
            .code(code)
            .displayName(displayName)
            .skipReason(skipReason)
            .lineNumber(lineNumber)
            .build());
    }

    /**
     * 添加导入警告
     * @param warningCode 警告代码
     * @param warningMessage 警告消息
     * @param code 字段键
     * @param lineNumber 行号
     * @param suggestion 建议操作
     */
    public void addWarning(String warningCode, String warningMessage, String code, Integer lineNumber, String suggestion) {
        if (warnings == null) {
            warnings = new ArrayList<>();
        }
        warnings.add(ImportWarning.builder()
            .warningCode(warningCode)
            .warningMessage(warningMessage)
            .code(code)
            .lineNumber(lineNumber)
            .suggestion(suggestion)
            .build());
    }

    /**
     * 计算成功率
     * @return 成功率百分比
     */
    public Double getSuccessRate() {
        if (totalCount == null || totalCount == 0) {
            return 0.0;
        }
        Integer success = successCount != null ? successCount : 0;
        return (success.doubleValue() / totalCount.doubleValue()) * 100.0;
    }

    /**
     * 检查是否有警告
     * @return 是否有警告
     */
    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }

    /**
     * 检查是否有失败
     * @return 是否有失败
     */
    public boolean hasFailures() {
        return failedFields != null && !failedFields.isEmpty();
    }

    /**
     * 获取导入摘要
     * @return 导入摘要
     */
    public String getSummary() {
        return String.format("导入完成：总计 %d 个字段，成功 %d 个，失败 %d 个，跳过 %d 个",
            totalCount != null ? totalCount : 0,
            successCount != null ? successCount : 0,
            failureCount != null ? failureCount : 0,
            skippedCount != null ? skippedCount : 0);
    }
}