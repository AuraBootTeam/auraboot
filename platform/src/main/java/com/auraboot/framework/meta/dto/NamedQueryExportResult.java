package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 命名查询导出结果DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryExportResult {

    /**
     * 导出是否成功
     */
    private Boolean success;

    /**
     * 导出消息
     */
    private String message;

    /**
     * 导出开始时间
     */
    private LocalDateTime startTime;

    /**
     * 导出结束时间
     */
    private LocalDateTime endTime;

    /**
     * 导出时长（毫秒）
     */
    private Long durationMs;

    /**
     * 导出的查询总数
     */
    private Integer totalQueries;

    /**
     * 成功导出的查询数
     */
    private Integer successfulExports;

    /**
     * 失败导出的查询数
     */
    private Integer failedExports;

    /**
     * 导出文件信息
     */
    private List<ExportFileInfo> exportFiles;

    /**
     * 导出格式
     */
    private String exportFormat;

    /**
     * 文件大小（字节）
     */
    private Long fileSizeBytes;

    /**
     * 是否压缩
     */
    private Boolean compressed;

    /**
     * 压缩比率
     */
    private Double compressionRatio;

    /**
     * 导出统计
     */
    private ExportStatistics exportStatistics;

    /**
     * 失败详情
     */
    private List<ExportFailure> failures;

    /**
     * 警告信息
     */
    private List<String> warnings;

    /**
     * 导出选项
     */
    private String exportOptions;

    /**
     * 下载链接
     */
    private String downloadUrl;

    /**
     * 文件过期时间
     */
    private LocalDateTime expiresAt;

    /**
     * 导出文件信息内部类
     */
    @Data
    public static class ExportFileInfo {
        private String fileName;
        private String filePath;
        private Long fileSizeBytes;
        private String fileFormat;
        private String checksum;
        private Integer queryCount;
        private LocalDateTime createdAt;
    }

    /**
     * 导出统计内部类
     */
    @Data
    public static class ExportStatistics {
        private Integer totalFields;
        private Integer totalVersions;
        private Integer totalDependencies;
        private Integer totalPermissions;
        private Long totalDataSize;
        private String largestQuery;
        private String mostComplexQuery;
        private Double averageQueryComplexity;
    }

    /**
     * 导出失败内部类
     */
    @Data
    public static class ExportFailure {
        private Long queryId;
        private String queryCode;
        private String errorMessage;
        private String errorType;
        private LocalDateTime failureTime;
    }

    public NamedQueryExportResult() {
        this.startTime = LocalDateTime.now(ZoneOffset.UTC);
    }

    public NamedQueryExportResult(Boolean success, String message) {
        this();
        this.success = success;
        this.message = message;
    }

    public static NamedQueryExportResult success(String message) {
        return new NamedQueryExportResult(true, message);
    }

    public static NamedQueryExportResult failure(String message) {
        return new NamedQueryExportResult(false, message);
    }

    /**
     * 完成导出
     */
    public void complete() {
        this.endTime = LocalDateTime.now(ZoneOffset.UTC);
        this.durationMs = this.endTime.toInstant(ZoneOffset.UTC).toEpochMilli() - this.startTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}