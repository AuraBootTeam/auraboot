package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 查询审计导出结果DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class QueryAuditExportResult {

    /**
     * 导出任务ID
     */
    private String exportTaskId;

    /**
     * 租户ID
     */
    private Long tenantId;

    /**
     * 导出状态
     */
    private String status;

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 导出开始时间
     */
    private LocalDateTime startTime;

    /**
     * 导出结束时间
     */
    private LocalDateTime endTime;

    /**
     * 导出耗时(毫秒)
     */
    private Long durationMs;

    /**
     * 导出记录数
     */
    private Long exportedRecords;

    /**
     * 总记录数
     */
    private Long totalRecords;

    /**
     * 导出进度(%)
     */
    private Double progress;

    /**
     * 导出文件信息
     */
    private List<ExportFileInfo> files;

    /**
     * 导出统计信息
     */
    private ExportStatistics statistics;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 错误详情
     */
    private List<String> errorDetails;

    /**
     * 警告信息
     */
    private List<String> warnings;

    /**
     * 导出配置
     */
    private Map<String, Object> exportConfiguration;

    /**
     * 执行信息
     */
    private ExportExecutionInfo executionInfo;

    /**
     * 下载链接
     */
    private List<String> downloadUrls;

    /**
     * 文件过期时间
     */
    private LocalDateTime fileExpirationTime;

    /**
     * 导出文件信息
     */
    @Data
    public static class ExportFileInfo {
        private String fileName;
        private String filePath;
        private String fileFormat;
        private Long fileSize;
        private String fileSizeFormatted;
        private String checksum;
        private String downloadUrl;
        private LocalDateTime createdAt;
        private Boolean compressed;
        private String compressionFormat;
        private String encoding;
    }

    /**
     * 导出统计信息
     */
    @Data
    public static class ExportStatistics {
        private Long totalQueries;
        private Long successfulQueries;
        private Long failedQueries;
        private Long slowQueries;
        private Map<String, Long> queryTypeDistribution;
        private Map<String, Long> modelDistribution;
        private Map<Long, Long> userDistribution;
        private Map<String, Long> errorTypeDistribution;
        private LocalDateTime earliestQueryTime;
        private LocalDateTime latestQueryTime;
        private Double averageExecutionTime;
        private Integer maxExecutionTime;
        private Integer minExecutionTime;
    }

    /**
     * 导出执行信息
     */
    @Data
    public static class ExportExecutionInfo {
        private String executorId;
        private String executorName;
        private String exportVersion;
        private Map<String, Object> systemInfo;
        private List<String> appliedFilters;
        private List<String> exportedFields;
        private String sortConfiguration;
        private Integer batchSize;
        private Integer totalBatches;
        private Integer processedBatches;
        private List<String> processingSteps;
        private Map<String, Long> stepDurations;
    }
}