package com.auraboot.framework.meta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 实体导出结果DTO
 */
@Data
@Schema(description = "实体导出结果DTO")
public class EntityExportResult {

    @Schema(description = "导出ID")
    private String exportId;

    @Schema(description = "导出状态")
    private ExportStatus status;

    @Schema(description = "导出的实体数量")
    private Integer exportedEntityCount;

    @Schema(description = "导出的字段数量")
    private Integer exportedFieldCount;

    @Schema(description = "导出文件路径")
    private String exportFilePath;

    @Schema(description = "导出文件大小（字节）")
    private Long exportFileSize;

    @Schema(description = "导出格式")
    private String exportFormat;

    @Schema(description = "导出开始时间")
    private LocalDateTime startTime;

    @Schema(description = "导出结束时间")
    private LocalDateTime endTime;

    @Schema(description = "导出耗时（毫秒）")
    private Long duration;

    @Schema(description = "成功导出的实体列表")
    private List<ExportedEntityInfo> successfulEntities;

    @Schema(description = "导出失败的实体列表")
    private List<ExportFailureInfo> failedEntities;

    @Schema(description = "导出配置")
    private Map<String, Object> exportConfig;

    @Schema(description = "错误信息")
    private String errorMessage;

    /**
     * 导出状态枚举
     */
    public enum ExportStatus {
        PENDING("待处理"),
        IN_PROGRESS("导出中"),
        COMPLETED("已完成"),
        FAILED("导出失败"),
        CANCELLED("已取消");

        private final String description;

        ExportStatus(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 导出的实体信息
     */
    @Data
    @Schema(description = "导出的实体信息")
    public static class ExportedEntityInfo {
        @Schema(description = "实体PID")
        private String entityPid;
        
        @Schema(description = "实体名称")
        private String entityName;
        
        @Schema(description = "字段数量")
        private Integer fieldCount;
        
        @Schema(description = "记录数量")
        private Long recordCount;
        
        @Schema(description = "导出时间")
        private LocalDateTime exportTime;
    }

    /**
     * 导出失败信息
     */
    @Data
    @Schema(description = "导出失败信息")
    public static class ExportFailureInfo {
        @Schema(description = "实体PID")
        private String entityPid;
        
        @Schema(description = "实体名称")
        private String entityName;
        
        @Schema(description = "失败原因")
        private String failureReason;
        
        @Schema(description = "错误代码")
        private String errorCode;
        
        @Schema(description = "失败时间")
        private LocalDateTime failureTime;
    }

    /**
     * 创建成功的导出结果
     */
    public static EntityExportResult success(String exportId, List<ExportedEntityInfo> entities) {
        EntityExportResult result = new EntityExportResult();
        result.setExportId(exportId);
        result.setStatus(ExportStatus.COMPLETED);
        result.setSuccessfulEntities(entities);
        result.setExportedEntityCount(entities.size());
        result.setEndTime(LocalDateTime.now(ZoneOffset.UTC));
        return result;
    }

    /**
     * 创建失败的导出结果
     */
    public static EntityExportResult failure(String exportId, String errorMessage) {
        EntityExportResult result = new EntityExportResult();
        result.setExportId(exportId);
        result.setStatus(ExportStatus.FAILED);
        result.setErrorMessage(errorMessage);
        result.setEndTime(LocalDateTime.now(ZoneOffset.UTC));
        return result;
    }
}