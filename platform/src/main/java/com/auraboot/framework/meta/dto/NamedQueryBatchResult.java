package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 命名查询批量操作结果DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryBatchResult {

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
    private Integer skippedCount;

    /**
     * 成功的查询ID列表
     */
    private List<String> successPids;

    /**
     * 失败的查询ID列表
     */
    private List<String> failurePids;

    /**
     * 跳过的查询ID列表
     */
    private List<String> skippedPids;

    /**
     * 错误信息映射（查询ID -> 错误信息）
     */
    private Map<String, String> errorMessages;

    /**
     * 警告信息映射（查询ID -> 警告信息）
     */
    private Map<String, String> warningMessages;

    /**
     * 操作详情列表
     */
    private List<NamedQueryOperationDetail> operationDetails;

    /**
     * 操作类型
     */
    private String operationType;

    /**
     * 操作开始时间
     */
    private LocalDateTime startTime;

    /**
     * 操作结束时间
     */
    private LocalDateTime endTime;

    /**
     * 操作耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 操作者
     */
    private String operator;

    /**
     * 操作备注
     */
    private String notes;

    /**
     * 是否有警告
     */
    private Boolean hasWarnings;

    /**
     * 是否部分成功
     */
    private Boolean partialSuccess;

    /**
     * 操作摘要
     */
    private String summary;

    /**
     * 扩展信息
     */
    private Map<String, Object> metadata;

    /**
     * 构造函数
     */
    public NamedQueryBatchResult() {
        this.startTime = LocalDateTime.now(ZoneOffset.UTC);
        this.successCount = 0;
        this.failureCount = 0;
        this.skippedCount = 0;
    }

    /**
     * 构造函数
     * @param operationType 操作类型
     * @param totalCount 总数量
     */
    public NamedQueryBatchResult(String operationType, Integer totalCount) {
        this();
        this.operationType = operationType;
        this.totalCount = totalCount;
    }

    /**
     * 完成操作
     */
    public void complete() {
        this.endTime = LocalDateTime.now(ZoneOffset.UTC);
        this.durationMs = endTime.toInstant(ZoneOffset.UTC).toEpochMilli() - startTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        this.success = failureCount == 0;
        this.partialSuccess = successCount > 0 && failureCount > 0;
        this.hasWarnings = warningMessages != null && !warningMessages.isEmpty();
        
        // 生成操作摘要
        StringBuilder sb = new StringBuilder();
        sb.append("批量").append(operationType).append("操作完成");
        sb.append("，总计").append(totalCount).append("项");
        sb.append("，成功").append(successCount).append("项");
        if (failureCount > 0) {
            sb.append("，失败").append(failureCount).append("项");
        }
        if (skippedCount > 0) {
            sb.append("，跳过").append(skippedCount).append("项");
        }
        sb.append("，耗时").append(durationMs).append("毫秒");
        this.summary = sb.toString();
    }

    /**
     * 添加成功项
     * @param id 查询ID
     */
    public void addSuccess(String pid) {
        if (successPids == null) {
            successPids = new java.util.ArrayList<>();
        }
        successPids.add(pid);
        successCount++;
    }

    /**
     * 添加失败项
     * @param id 查询ID
     * @param errorMessage 错误信息
     */
    public void addFailure(String pid, String errorMessage) {
        if (failurePids == null) {
            failurePids = new java.util.ArrayList<>();
        }
        if (errorMessages == null) {
            errorMessages = new java.util.HashMap<>();
        }
        failurePids.add(pid);
        errorMessages.put(pid, errorMessage);
        failureCount++;
    }

    /**
     * 添加跳过项
     * @param id 查询ID
     */
    public void addSkipped(String pid) {
        if (skippedPids == null) {
            skippedPids = new java.util.ArrayList<>();
        }
        skippedPids.add(pid);
        skippedCount++;
    }

    /**
     * 添加警告
     * @param id 查询ID
     * @param warningMessage 警告信息
     */
    public void addWarning(String pid, String warningMessage) {
        if (warningMessages == null) {
            warningMessages = new java.util.HashMap<>();
        }
        warningMessages.put(pid, warningMessage);
    }

    /**
     * 获取成功率
     * @return 成功率（百分比）
     */
    public Double getSuccessRate() {
        if (totalCount == null || totalCount == 0) {
            return 0.0;
        }
        return (double) successCount / totalCount * 100;
    }

    /**
     * 获取失败率
     * @return 失败率（百分比）
     */
    public Double getFailureRate() {
        if (totalCount == null || totalCount == 0) {
            return 0.0;
        }
        return (double) failureCount / totalCount * 100;
    }

    /**
     * 是否全部成功
     * @return 是否全部成功
     */
    public Boolean isAllSuccess() {
        return Boolean.TRUE.equals(success) && failureCount == 0;
    }

    /**
     * 是否全部失败
     * @return 是否全部失败
     */
    public Boolean isAllFailure() {
        return successCount == 0 && failureCount > 0;
    }
}