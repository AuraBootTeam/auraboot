package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 查询异常检测请求DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class QueryAnomalyDetectionRequest {

    /**
     * 租户ID
     */
    @NotNull(message = "租户ID不能为空")
    private Long tenantId;

    /**
     * 检测开始时间
     */
    private LocalDateTime startTime;

    /**
     * 检测结束时间
     */
    private LocalDateTime endTime;

    /**
     * 用户ID列表（为空表示检测所有用户）
     */
    private List<Long> userIds;

    /**
     * 模型编码列表（为空表示检测所有模型）
     */
    private List<String> modelCodes;

    /**
     * 异常检测类型
     */
    private List<String> detectionTypes;

    /**
     * 频繁查询阈值（时间窗口内的查询次数）
     */
    private Integer frequentQueryThreshold = 100;

    /**
     * 频繁查询时间窗口（分钟）
     */
    private Integer frequentQueryTimeWindowMinutes = 60;

    /**
     * 慢查询阈值（毫秒）
     */
    private Integer slowQueryThreshold = 10000;

    /**
     * 异常执行时间倍数（超过平均执行时间的倍数）
     */
    private Double abnormalExecutionTimeMultiplier = 5.0;

    /**
     * 可疑查询模式检测敏感度（1-10，数值越高越敏感）
     */
    private Integer suspiciousPatternSensitivity = 5;

    /**
     * 是否检测SQL注入尝试
     */
    private Boolean detectSqlInjection = true;

    /**
     * 是否检测权限绕过尝试
     */
    private Boolean detectPermissionBypass = true;

    /**
     * 是否检测数据泄露风险
     */
    private Boolean detectDataLeakage = true;

    /**
     * 是否检测异常访问模式
     */
    private Boolean detectAbnormalAccess = true;

    /**
     * 风险分数阈值（超过此分数的查询被标记为异常）
     */
    private Integer riskScoreThreshold = 70;

    /**
     * 最大返回异常数量
     */
    private Integer maxAnomalies = 100;

    /**
     * 是否包含详细分析
     */
    private Boolean includeDetailedAnalysis = false;

    /**
     * 是否生成异常报告
     */
    private Boolean generateReport = false;
}