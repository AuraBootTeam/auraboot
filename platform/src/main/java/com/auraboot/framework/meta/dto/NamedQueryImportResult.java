package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 命名查询导入结果DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryImportResult {

    /**
     * 导入是否成功
     */
    private Boolean success;

    /**
     * 导入消息
     */
    private String message;

    /**
     * 导入开始时间
     */
    private LocalDateTime startTime;

    /**
     * 导入结束时间
     */
    private LocalDateTime endTime;

    /**
     * 导入时长（毫秒）
     */
    private Long durationMs;

    /**
     * 总查询数
     */
    private Integer totalQueries;

    /**
     * 成功导入数
     */
    private Integer successfulImports = 0;

    /**
     * 失败导入数
     */
    private Integer failedImports = 0;

    /**
     * 跳过导入数
     */
    private Integer skippedImports = 0;

    /**
     * 更新数量
     */
    private Integer updatedQueries = 0;

    /**
     * 创建数量
     */
    private Integer createdQueries = 0;

    /**
     * 导入统计
     */
    private ImportStatistics importStatistics;

    /**
     * 成功导入的查询ID列表
     */
    private List<Long> successfulQueryIds = new ArrayList<>();

    /**
     * 导入失败详情
     */
    private List<ImportFailure> failures = new ArrayList<>();

    /**
     * 跳过详情
     */
    private List<ImportSkipped> skipped = new ArrayList<>();

    /**
     * 警告信息
     */
    private List<String> warnings = new ArrayList<>();

    /**
     * 导入摘要
     */
    private String importSummary;

    /**
     * 是否预览模式
     */
    private Boolean previewMode;

    /**
     * 备份信息
     */
    private BackupInfo backupInfo;

    /**
     * 导入统计内部类
     */
    @Data
    public static class ImportStatistics {
        private Integer totalFields;
        private Integer importedFields;
        private Integer totalVersions;
        private Integer importedVersions;
        private Integer totalDependencies;
        private Integer importedDependencies;
        private Integer totalPermissions;
        private Integer importedPermissions;
        private Long totalDataSize;
        private String largestImportedQuery;
        private String mostComplexImportedQuery;
    }

    /**
     * 导入失败内部类
     */
    @Data
    public static class ImportFailure {
        private String queryCode;
        private String errorMessage;
        private String errorType;
        private Integer lineNumber;
        private LocalDateTime failureTime;
        private String originalData;

        public ImportFailure(String queryCode, String errorMessage) {
            this.queryCode = queryCode;
            this.errorMessage = errorMessage;
            this.failureTime = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    /**
     * 导入跳过内部类
     */
    @Data
    public static class ImportSkipped {
        private String queryCode;
        private String reason;
        private String skipType; // DUPLICATE, CONFLICT, VALIDATION_ERROR, Permission_DENIED
        private LocalDateTime skipTime;

        public ImportSkipped(String queryCode, String reason) {
            this.queryCode = queryCode;
            this.reason = reason;
            this.skipTime = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    /**
     * 备份信息内部类
     */
    @Data
    public static class BackupInfo {
        private String backupId;
        private String backupPath;
        private Long backupSize;
        private LocalDateTime backupTime;
        private Integer backedUpQueries;
        private String backupFormat;
    }

    public NamedQueryImportResult() {
        this.startTime = LocalDateTime.now(ZoneOffset.UTC);
    }

    public NamedQueryImportResult(Boolean success, String message) {
        this();
        this.success = success;
        this.message = message;
    }

    public static NamedQueryImportResult success(String message) {
        return new NamedQueryImportResult(true, message);
    }

    public static NamedQueryImportResult failure(String message) {
        return new NamedQueryImportResult(false, message);
    }

    /**
     * 添加成功记录
     */
    public void addSuccess(Long queryId) {
        this.successfulQueryIds.add(queryId);
        this.successfulImports++;
    }

    /**
     * 添加失败记录
     */
    public void addFailure(String queryCode, String errorMessage) {
        this.failures.add(new ImportFailure(queryCode, errorMessage));
        this.failedImports++;
    }

    /**
     * 添加跳过记录
     */
    public void addSkipped(String queryCode, String reason) {
        this.skipped.add(new ImportSkipped(queryCode, reason));
        this.skippedImports++;
    }

    /**
     * 完成导入
     */
    public void complete() {
        this.endTime = LocalDateTime.now(ZoneOffset.UTC);
        this.durationMs = this.endTime.toInstant(ZoneOffset.UTC).toEpochMilli() - this.startTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        this.importSummary = String.format("导入完成：总计%d，成功%d，失败%d，跳过%d，创建%d，更新%d，耗时%dms",
                totalQueries, successfulImports, failedImports, skippedImports, 
                createdQueries, updatedQueries, durationMs);
    }
}