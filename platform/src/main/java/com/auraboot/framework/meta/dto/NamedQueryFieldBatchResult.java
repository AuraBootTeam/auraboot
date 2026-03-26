package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 命名查询字段批量操作结果DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryFieldBatchResult {

    /**
     * 操作类型
     */
    private String operationType;

    /**
     * 总数量
     */
    private Integer totalCount;

    /**
     * 成功数量
     */
    private Integer successCount = 0;

    /**
     * 失败数量
     */
    private Integer failureCount = 0;

    /**
     * 跳过数量
     */
    private Integer skippedCount = 0;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 处理时长（毫秒）
     */
    private Long durationMs;

    /**
     * 是否完成
     */
    private Boolean completed = false;

    /**
     * 成功的字段ID列表
     */
    private List<Long> successFieldIds = new ArrayList<>();

    /**
     * 失败的操作详情
     */
    private List<FieldOperationDetail> failures = new ArrayList<>();

    /**
     * 跳过的操作详情
     */
    private List<FieldOperationDetail> skipped = new ArrayList<>();

    /**
     * 操作摘要
     */
    private String summary;

    /**
     * 字段操作详情内部类
     */
    @Data
    public static class FieldOperationDetail {
        private Long fieldId;
        private String fieldCode;
        private String reason;
        private String errorMessage;
        private LocalDateTime operationTime;

        public FieldOperationDetail(Long fieldId, String fieldCode, String reason) {
            this.fieldId = fieldId;
            this.fieldCode = fieldCode;
            this.reason = reason;
            this.operationTime = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public NamedQueryFieldBatchResult(String operationType, Integer totalCount) {
        this.operationType = operationType;
        this.totalCount = totalCount;
        this.startTime = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 添加成功记录
     */
    public void addSuccess(Long fieldId) {
        this.successFieldIds.add(fieldId);
        this.successCount++;
    }

    /**
     * 添加失败记录
     */
    public void addFailure(Long fieldId, String fieldCode, String reason) {
        this.failures.add(new FieldOperationDetail(fieldId, fieldCode, reason));
        this.failureCount++;
    }

    /**
     * 添加跳过记录
     */
    public void addSkipped(Long fieldId, String fieldCode, String reason) {
        this.skipped.add(new FieldOperationDetail(fieldId, fieldCode, reason));
        this.skippedCount++;
    }

    /**
     * 完成操作
     */
    public void complete() {
        this.endTime = LocalDateTime.now(ZoneOffset.UTC);
        this.durationMs = this.endTime.toInstant(ZoneOffset.UTC).toEpochMilli() - this.startTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        this.completed = true;
        this.summary = String.format("批量%s完成：总计%d，成功%d，失败%d，跳过%d，耗时%dms",
                operationType, totalCount, successCount, failureCount, skippedCount, durationMs);
    }
}