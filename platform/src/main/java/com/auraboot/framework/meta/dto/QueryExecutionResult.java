package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 查询执行结果DTO
 *
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryExecutionResult {

    /**
     * 查询名称
     */
    private String queryName;

    /**
     * 查询结果数据
     */
    private List<Map<String, Object>> data;

    /**
     * 总记录数
     */
    private Long totalCount;

    /**
     * 当前页码
     */
    private Integer pageNum;

    /**
     * 页大小
     */
    private Integer pageSize;

    /**
     * 总页数
     */
    private Integer totalPages;

    /**
     * 执行时间（毫秒）
     */
    private Long executionTime;

    /**
     * 执行状态
     */
    @Builder.Default
    private ExecutionStatus status = ExecutionStatus.SUCCESS;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 警告信息
     */
    private List<String> warnings;

    /**
     * 查询统计信息
     */
    private QueryStatistics statistics;

    /**
     * 执行时间戳
     */
    @Builder.Default
    private LocalDateTime executedAt = LocalDateTime.now(ZoneOffset.UTC);

    /**
     * 是否被缓存
     */
    @Builder.Default
    private Boolean cached = false;

    /**
     * 缓存键
     */
    private String cacheKey;

    /**
     * 执行状态枚举
     */
    public enum ExecutionStatus {
        SUCCESS,
        FAILED,
        TIMEOUT,
        Permission_DENIED,
        VALIDATION_ERROR
    }

    /**
     * 查询统计信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryStatistics {
        /**
         * 扫描行数
         */
        private Long scannedRows;

        /**
         * 返回行数
         */
        private Long returnedRows;

        /**
         * 索引使用情况
         */
        private List<String> indexesUsed;

        /**
         * 查询复杂度评分
         */
        private Integer complexityScore;

        /**
         * 是否使用了查询提示
         */
        private Boolean hintsApplied;

        /**
         * 脱敏字段数量
         */
        private Integer maskedFieldCount;
    }
}
