package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 命名查询统计信息DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryStatistics {

    /**
     * 查询ID
     */
    private Long queryId;

    /**
     * 查询编码
     */
    private String queryCode;

    /**
     * 查询标题
     */
    private String queryTitle;

    /**
     * 统计时间范围 - 开始
     */
    private LocalDateTime periodStart;

    /**
     * 统计时间范围 - 结束
     */
    private LocalDateTime periodEnd;

    /**
     * 总执行次数
     */
    private Long totalExecutions;

    /**
     * 成功执行次数
     */
    private Long successfulExecutions;

    /**
     * 失败执行次数
     */
    private Long failedExecutions;

    /**
     * 被拒绝次数
     */
    private Long rejectedExecutions;

    /**
     * 成功率（百分比）
     */
    private Double successRate;

    /**
     * 失败率（百分比）
     */
    private Double failureRate;

    /**
     * 拒绝率（百分比）
     */
    private Double rejectionRate;

    /**
     * 平均执行时间（毫秒）
     */
    private Double averageExecutionTime;

    /**
     * 最小执行时间（毫秒）
     */
    private Integer minExecutionTime;

    /**
     * 最大执行时间（毫秒）
     */
    private Integer maxExecutionTime;

    /**
     * 中位数执行时间（毫秒）
     */
    private Double medianExecutionTime;

    /**
     * 95分位数执行时间（毫秒）
     */
    private Double p95ExecutionTime;

    /**
     * 99分位数执行时间（毫秒）
     */
    private Double p99ExecutionTime;

    /**
     * 平均返回结果数量
     */
    private Double averageResultCount;

    /**
     * 最小返回结果数量
     */
    private Long minResultCount;

    /**
     * 最大返回结果数量
     */
    private Long maxResultCount;

    /**
     * 总返回结果数量
     */
    private Long totalResultCount;

    /**
     * 慢查询次数（超过阈值）
     */
    private Long slowQueryCount;

    /**
     * 慢查询阈值（毫秒）
     */
    private Integer slowQueryThreshold;

    /**
     * 大结果集查询次数（超过阈值）
     */
    private Long largeResultQueryCount;

    /**
     * 大结果集阈值
     */
    private Long largeResultThreshold;

    /**
     * 唯一用户数量
     */
    private Long uniqueUserCount;

    /**
     * 最活跃用户
     */
    private String mostActiveUser;

    /**
     * 最活跃用户执行次数
     */
    private Long mostActiveUserExecutions;

    /**
     * 首次执行时间
     */
    private LocalDateTime firstExecutionTime;

    /**
     * 最后执行时间
     */
    private LocalDateTime lastExecutionTime;

    /**
     * 每日执行次数分布
     */
    private Map<String, Long> dailyExecutions;

    /**
     * 每小时执行次数分布
     */
    private Map<Integer, Long> hourlyExecutions;

    /**
     * 用户执行次数分布
     */
    private Map<String, Long> userExecutions;

    /**
     * 错误类型分布
     */
    private Map<String, Long> errorTypeDistribution;

    /**
     * 拒绝原因分布
     */
    private Map<String, Long> rejectionReasonDistribution;

    /**
     * 查询条件使用频率
     */
    private Map<String, Long> conditionUsageFrequency;

    /**
     * 字段使用频率
     */
    private Map<String, Long> fieldUsageFrequency;

    /**
     * 操作符使用频率
     */
    private Map<String, Long> operatorUsageFrequency;

    /**
     * 性能趋势（时间 -> 平均执行时间）
     */
    private Map<String, Double> performanceTrend;

    /**
     * 使用量趋势（时间 -> 执行次数）
     */
    private Map<String, Long> usageTrend;

    /**
     * 统计生成时间
     */
    private LocalDateTime generatedAt;

    /**
     * 统计版本
     */
    private String statisticsVersion;

    /**
     * 构造函数
     */
    public NamedQueryStatistics() {
        this.generatedAt = LocalDateTime.now(ZoneOffset.UTC);
        this.statisticsVersion = "1.0";
    }

    /**
     * 构造函数
     * @param queryId 查询ID
     * @param queryCode 查询编码
     * @param periodStart 统计开始时间
     * @param periodEnd 统计结束时间
     */
    public NamedQueryStatistics(Long queryId, String queryCode, LocalDateTime periodStart, LocalDateTime periodEnd) {
        this();
        this.queryId = queryId;
        this.queryCode = queryCode;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
    }

    /**
     * 计算成功率
     */
    public void calculateRates() {
        if (totalExecutions != null && totalExecutions > 0) {
            if (successfulExecutions != null) {
                this.successRate = (double) successfulExecutions / totalExecutions * 100;
            }
            if (failedExecutions != null) {
                this.failureRate = (double) failedExecutions / totalExecutions * 100;
            }
            if (rejectedExecutions != null) {
                this.rejectionRate = (double) rejectedExecutions / totalExecutions * 100;
            }
        }
    }

    /**
     * 是否为高频查询
     * @param threshold 高频阈值
     * @return 是否为高频查询
     */
    public Boolean isHighFrequencyQuery(Long threshold) {
        return totalExecutions != null && totalExecutions >= threshold;
    }

    /**
     * 是否为慢查询
     * @return 是否为慢查询
     */
    public Boolean isSlowQuery() {
        return slowQueryCount != null && slowQueryCount > 0;
    }

    /**
     * 是否为大结果集查询
     * @return 是否为大结果集查询
     */
    public Boolean isLargeResultQuery() {
        return largeResultQueryCount != null && largeResultQueryCount > 0;
    }

    /**
     * 获取查询健康度评分（0-100）
     * @return 健康度评分
     */
    public Double getHealthScore() {
        double score = 100.0;
        
        // 成功率影响（权重40%）
        if (successRate != null) {
            score = score * 0.6 + successRate * 0.4;
        }
        
        // 性能影响（权重30%）
        if (averageExecutionTime != null && slowQueryThreshold != null) {
            double performanceScore = Math.max(0, 100 - (averageExecutionTime / slowQueryThreshold * 100));
            score = score * 0.7 + performanceScore * 0.3;
        }
        
        // 稳定性影响（权重20%）
        if (rejectionRate != null) {
            double stabilityScore = Math.max(0, 100 - rejectionRate * 2);
            score = score * 0.8 + stabilityScore * 0.2;
        }
        
        // 使用频率影响（权重10%）
        if (totalExecutions != null) {
            double usageScore = Math.min(100, totalExecutions / 10.0);
            score = score * 0.9 + usageScore * 0.1;
        }
        
        return Math.max(0, Math.min(100, score));
    }

    /**
     * 获取性能等级
     * @return 性能等级
     */
    public String getPerformanceLevel() {
        if (averageExecutionTime == null) {
            return "unknown";
        }
        
        if (averageExecutionTime < 100) {
            return "excellent";
        } else if (averageExecutionTime < 500) {
            return "good";
        } else if (averageExecutionTime < 1000) {
            return "fair";
        } else if (averageExecutionTime < 5000) {
            return "poor";
        } else {
            return "critical";
        }
    }

    /**
     * 获取使用频率等级
     * @return 使用频率等级
     */
    public String getUsageLevel() {
        if (totalExecutions == null) {
            return "unknown";
        }
        
        // 基于统计周期计算日均执行次数
        long periodDays = java.time.Duration.between(periodStart, periodEnd).toDays();
        if (periodDays == 0) {
            periodDays = 1;
        }
        
        double dailyAverage = (double) totalExecutions / periodDays;
        
        if (dailyAverage < 1) {
            return "low";
        } else if (dailyAverage < 10) {
            return "medium";
        } else if (dailyAverage < 100) {
            return "high";
        } else {
            return "very_high";
        }
    }

    /**
     * 获取统计摘要
     * @return 统计摘要
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("查询[").append(queryCode).append("]统计：");
        sb.append("执行").append(totalExecutions != null ? totalExecutions : 0).append("次");
        if (successRate != null) {
            sb.append("，成功率").append(String.format("%.1f", successRate)).append("%");
        }
        if (averageExecutionTime != null) {
            sb.append("，平均耗时").append(String.format("%.0f", averageExecutionTime)).append("ms");
        }
        sb.append("，性能等级").append(getPerformanceLevel());
        sb.append("，使用频率").append(getUsageLevel());
        return sb.toString();
    }
}