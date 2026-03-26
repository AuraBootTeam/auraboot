package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 查询审计配置DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class QueryAuditConfig {

    /**
     * 租户ID
     */
    @NotNull(message = "租户ID不能为空")
    private Long tenantId;

    /**
     * 是否启用审计
     */
    private Boolean auditEnabled = true;

    /**
     * 是否记录成功查询
     */
    private Boolean logSuccessfulQueries = true;

    /**
     * 是否记录失败查询
     */
    private Boolean logFailedQueries = true;

    /**
     * 是否记录慢查询
     */
    private Boolean logSlowQueries = true;

    /**
     * 慢查询阈值(毫秒)
     */
    @Min(value = 100, message = "慢查询阈值不能小于100毫秒")
    @Max(value = 300000, message = "慢查询阈值不能大于300秒")
    private Integer slowQueryThreshold = 5000;

    /**
     * 是否记录安全事件
     */
    private Boolean logSecurityEvents = true;

    /**
     * 日志保留天数
     */
    @Min(value = 1, message = "日志保留天数不能小于1天")
    @Max(value = 3650, message = "日志保留天数不能大于10年")
    private Integer retentionDays = 90;

    /**
     * 最大日志大小(MB)
     */
    @Min(value = 10, message = "最大日志大小不能小于10MB")
    @Max(value = 100000, message = "最大日志大小不能大于100GB")
    private Integer maxLogSizeMb = 1000;

    /**
     * 采样率(0.01-1.00)
     */
    @Min(value = 0, message = "采样率不能小于0")
    @Max(value = 1, message = "采样率不能大于1")
    private BigDecimal samplingRate = BigDecimal.ONE;

    /**
     * 排除的用户ID列表
     */
    private List<Long> excludedUsers;

    /**
     * 排除的模型编码列表
     */
    private List<String> excludedModels;

    /**
     * 告警阈值配置
     */
    private Map<String, Object> alertThresholds;

    /**
     * 是否启用实时监控
     */
    private Boolean realtimeMonitoringEnabled = true;

    /**
     * 实时监控时间窗口(分钟)
     */
    @Min(value = 1, message = "实时监控时间窗口不能小于1分钟")
    @Max(value = 1440, message = "实时监控时间窗口不能大于24小时")
    private Integer realtimeWindowMinutes = 15;

    /**
     * 是否启用异常检测
     */
    private Boolean anomalyDetectionEnabled = true;

    /**
     * 异常检测敏感度(1-10)
     */
    @Min(value = 1, message = "异常检测敏感度不能小于1")
    @Max(value = 10, message = "异常检测敏感度不能大于10")
    private Integer anomalyDetectionSensitivity = 5;

    /**
     * 是否启用性能分析
     */
    private Boolean performanceAnalysisEnabled = true;

    /**
     * 性能分析采样率
     */
    @Min(value = 0, message = "性能分析采样率不能小于0")
    @Max(value = 1, message = "性能分析采样率不能大于1")
    private BigDecimal performanceAnalysisSamplingRate = new BigDecimal("0.1");

    /**
     * 是否启用缓存统计
     */
    private Boolean cacheStatisticsEnabled = true;

    /**
     * 缓存统计更新间隔(分钟)
     */
    @Min(value = 1, message = "缓存统计更新间隔不能小于1分钟")
    @Max(value = 1440, message = "缓存统计更新间隔不能大于24小时")
    private Integer cacheStatisticsUpdateIntervalMinutes = 5;

    /**
     * 是否启用数据脱敏日志
     */
    private Boolean dataMaskingLogEnabled = true;

    /**
     * 是否启用权限检查日志
     */
    private Boolean permissionCheckLogEnabled = true;

    /**
     * 权限检查日志级别
     */
    private String permissionCheckLogLevel = "info";

    /**
     * 是否启用查询优化建议
     */
    private Boolean queryOptimizationSuggestionsEnabled = true;

    /**
     * 查询优化建议阈值(毫秒)
     */
    @Min(value = 100, message = "查询优化建议阈值不能小于100毫秒")
    private Integer optimizationSuggestionThreshold = 3000;

    /**
     * 是否启用自动报告生成
     */
    private Boolean autoReportGenerationEnabled = false;

    /**
     * 自动报告生成频率
     */
    private String autoReportFrequency = "weekly";

    /**
     * 报告接收人邮箱列表
     */
    private List<String> reportRecipients;

    /**
     * 自定义配置参数
     */
    private Map<String, Object> customParameters;

    /**
     * 配置版本
     */
    private String configVersion = "1.0";

    /**
     * 配置描述
     */
    private String description;

    /**
     * 是否启用配置
     */
    private Boolean enabled = true;
}