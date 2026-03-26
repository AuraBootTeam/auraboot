package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;

/**
 * 实体字段复制结果DTO
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityFieldCopyResult {
    
    /**
     * 复制操作ID
     */
    private String copyId;
    
    /**
     * 租户ID
     */
    private String tenantId;
    
    /**
     * 源实体PID
     */
    private String sourceEntityPid;
    
    /**
     * 源实体名称
     */
    private String sourceEntityName;
    
    /**
     * 目标实体PID
     */
    private String targetEntityPid;
    
    /**
     * 目标实体名称
     */
    private String targetEntityName;
    
    /**
     * 复制状态
     */
    private CopyStatus status;
    
    /**
     * 总字段数
     */
    private Integer totalFields;
    
    /**
     * 成功复制的字段数
     */
    private Integer successfulCopies;
    
    /**
     * 跳过的字段数
     */
    private Integer skippedFields;
    
    /**
     * 失败的字段数
     */
    private Integer failedFields;
    
    /**
     * 成功复制的字段列表
     */
    @Builder.Default
    private List<CopiedFieldInfo> successfulFields = new ArrayList<>();
    
    /**
     * 跳过的字段列表
     */
    @Builder.Default
    private List<SkippedFieldInfo> skippedFieldsList = new ArrayList<>();
    
    /**
     * 失败的字段列表
     */
    @Builder.Default
    private List<FailedFieldInfo> failedFieldsList = new ArrayList<>();
    
    /**
     * 复制开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 复制结束时间
     */
    private LocalDateTime endTime;
    
    /**
     * 操作人
     */
    private String operatedBy;
    
    /**
     * 复制时间
     */
    private LocalDateTime copyTime;
    
    /**
     * 复制状态枚举
     */
    public enum CopyStatus {
        SUCCESS,
        PARTIAL_SUCCESS,
        FAILED,
        IN_PROGRESS
    }
    
    /**
     * 成功复制的字段信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CopiedFieldInfo {
        private String fieldPid;
        private String code;
        private String fieldName;
        private String dataType;
        private Integer sortOrder;
    }
    
    /**
     * 跳过的字段信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkippedFieldInfo {
        private String fieldPid;
        private String code;
        private String fieldName;
        private String reason;
    }
    
    /**
     * 失败的字段信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedFieldInfo {
        private String fieldPid;
        private String code;
        private String fieldName;
        private String errorMessage;
        private String errorCode;
    }
    
    /**
     * 创建成功结果
     */
    public static EntityFieldCopyResult success(String copyId, String tenantId, 
                                               String sourceEntityPid, String targetEntityPid) {
        return EntityFieldCopyResult.builder()
                .copyId(copyId)
                .tenantId(tenantId)
                .sourceEntityPid(sourceEntityPid)
                .targetEntityPid(targetEntityPid)
                .status(CopyStatus.SUCCESS)
                .endTime(LocalDateTime.now(ZoneOffset.UTC))
                .copyTime(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }
    
    /**
     * 创建部分成功结果
     */
    public static EntityFieldCopyResult partialSuccess(String copyId, String tenantId,
                                                       String sourceEntityPid, String targetEntityPid) {
        return EntityFieldCopyResult.builder()
                .copyId(copyId)
                .tenantId(tenantId)
                .sourceEntityPid(sourceEntityPid)
                .targetEntityPid(targetEntityPid)
                .status(CopyStatus.PARTIAL_SUCCESS)
                .endTime(LocalDateTime.now(ZoneOffset.UTC))
                .copyTime(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }
    
    /**
     * 创建失败结果
     */
    public static EntityFieldCopyResult failed(String copyId, String tenantId,
                                              String sourceEntityPid, String targetEntityPid) {
        return EntityFieldCopyResult.builder()
                .copyId(copyId)
                .tenantId(tenantId)
                .sourceEntityPid(sourceEntityPid)
                .targetEntityPid(targetEntityPid)
                .status(CopyStatus.FAILED)
                .endTime(LocalDateTime.now(ZoneOffset.UTC))
                .copyTime(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }
    
    /**
     * 检查是否完全成功
     */
    public boolean isCompleteSuccess() {
        return CopyStatus.SUCCESS.equals(status) && 
               (failedFields == null || failedFields == 0) &&
               (skippedFields == null || skippedFields == 0);
    }
    
    /**
     * 检查是否有失败
     */
    public boolean hasFailures() {
        return failedFields != null && failedFields > 0;
    }
    
    /**
     * 检查是否有跳过的字段
     */
    public boolean hasSkippedFields() {
        return skippedFields != null && skippedFields > 0;
    }
    
    /**
     * 获取成功率
     */
    public double getSuccessRate() {
        if (totalFields == null || totalFields == 0) {
            return 0.0;
        }
        return (double) (successfulCopies != null ? successfulCopies : 0) / totalFields;
    }
}