package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 命名查询操作详情DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryOperationDetail {

    /**
     * 查询PID
     */
    private String queryPid;

    /**
     * 查询编码
     */
    private String queryCode;

    /**
     * 查询标题
     */
    private String queryTitle;

    /**
     * 操作类型
     */
    private String operationType;

    /**
     * 操作状态
     */
    private String status;

    /**
     * 操作结果
     */
    private String result;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 警告信息
     */
    private String warningMessage;

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
     * 操作前状态
     */
    private String beforeStatus;

    /**
     * 操作后状态
     */
    private String afterStatus;

    /**
     * 影响的记录数
     */
    private Integer affectedRows;

    /**
     * 操作备注
     */
    private String notes;

    /**
     * 构造函数
     */
    public NamedQueryOperationDetail() {
        this.startTime = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 构造函数
     * @param queryId 查询ID
     * @param queryCode 查询编码
     * @param operationType 操作类型
     */
    public NamedQueryOperationDetail(String queryPid, String queryCode, String operationType) {
        this();
        this.queryPid = queryPid;
        this.queryCode = queryCode;
        this.operationType = operationType;
        this.status = "processing";
    }

    /**
     * 标记操作成功
     * @param result 操作结果
     */
    public void markSuccess(String result) {
        this.endTime = LocalDateTime.now(ZoneOffset.UTC);
        this.durationMs = endTime.toInstant(ZoneOffset.UTC).toEpochMilli() - startTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        this.status = "success";
        this.result = result;
    }

    /**
     * 标记操作失败
     * @param errorMessage 错误信息
     */
    public void markFailure(String errorMessage) {
        this.endTime = LocalDateTime.now(ZoneOffset.UTC);
        this.durationMs = endTime.toInstant(ZoneOffset.UTC).toEpochMilli() - startTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        this.status = "failure";
        this.errorMessage = errorMessage;
    }

    /**
     * 标记操作跳过
     * @param reason 跳过原因
     */
    public void markSkipped(String reason) {
        this.endTime = LocalDateTime.now(ZoneOffset.UTC);
        this.durationMs = endTime.toInstant(ZoneOffset.UTC).toEpochMilli() - startTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        this.status = "skipped";
        this.result = reason;
    }

    /**
     * 添加警告
     * @param warningMessage 警告信息
     */
    public void addWarning(String warningMessage) {
        this.warningMessage = warningMessage;
    }

    /**
     * 是否成功
     * @return 是否成功
     */
    public Boolean isSuccess() {
        return StatusConstants.SUCCESS.equals(status);
    }

    /**
     * 是否失败
     * @return 是否失败
     */
    public Boolean isFailure() {
        return "failure".equals(status);
    }

    /**
     * 是否跳过
     * @return 是否跳过
     */
    public Boolean isSkipped() {
        return StatusConstants.SKIPPED.equals(status);
    }

    /**
     * 是否有警告
     * @return 是否有警告
     */
    public Boolean hasWarning() {
        return warningMessage != null && !warningMessage.trim().isEmpty();
    }

    /**
     * 获取操作摘要
     * @return 操作摘要
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("查询[").append(queryCode).append("]");
        sb.append("执行").append(operationType);
        sb.append("操作，状态：").append(status);
        if (durationMs != null) {
            sb.append("，耗时：").append(durationMs).append("ms");
        }
        if (isFailure() && errorMessage != null) {
            sb.append("，错误：").append(errorMessage);
        }
        return sb.toString();
    }
}