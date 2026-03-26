package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 命名查询测试结果DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryTestResult {

    /**
     * 测试是否成功
     */
    private Boolean success;

    /**
     * 测试消息
     */
    private String message;

    /**
     * 查询ID
     */
    private Long queryId;

    /**
     * 测试开始时间
     */
    private LocalDateTime startTime;

    /**
     * 测试结束时间
     */
    private LocalDateTime endTime;

    /**
     * 执行时长（毫秒）
     */
    private Long executionTimeMs;

    /**
     * 语法验证结果
     */
    private Boolean syntaxValid;

    /**
     * 语法错误信息
     */
    private List<String> syntaxErrors;

    /**
     * 查询结果数量
     */
    private Integer resultCount;

    /**
     * 查询结果数据（前几行）
     */
    private List<Map<String, Object>> sampleData;

    /**
     * 执行的SQL语句
     */
    private String executedSql;

    /**
     * 查询统计信息
     */
    private QueryExecutionStats executionStats;

    /**
     * 性能指标
     */
    private PerformanceMetrics performanceMetrics;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 警告信息
     */
    private List<String> warnings;

    /**
     * 测试环境
     */
    private String testEnvironment;

    /**
     * 查询执行统计内部类
     */
    @Data
    public static class QueryExecutionStats {
        private Long rowsScanned;
        private Long rowsReturned;
        private Long bytesProcessed;
        private Double cpuTimeMs;
        private Double ioTimeMs;
        private Integer tempTablesUsed;
        private String executionPlan;
    }

    /**
     * 性能指标内部类
     */
    @Data
    public static class PerformanceMetrics {
        private Double queryTimeMs;
        private Double parseTimeMs;
        private Double planTimeMs;
        private Double executeTimeMs;
        private Long memoryUsedBytes;
        private Integer connectionPoolSize;
        private String performanceGrade; // EXCELLENT, GOOD, FAIR, POOR
    }

    public NamedQueryTestResult() {
        this.startTime = LocalDateTime.now(ZoneOffset.UTC);
    }

    public NamedQueryTestResult(Boolean success, String message) {
        this();
        this.success = success;
        this.message = message;
    }

    public static NamedQueryTestResult success(String message) {
        return new NamedQueryTestResult(true, message);
    }

    public static NamedQueryTestResult failure(String message) {
        return new NamedQueryTestResult(false, message);
    }

    /**
     * 完成测试
     */
    public void complete() {
        this.endTime = LocalDateTime.now(ZoneOffset.UTC);
        this.executionTimeMs = this.endTime.toInstant(ZoneOffset.UTC).toEpochMilli() - this.startTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}