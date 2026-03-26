package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 查询审计报告请求DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class QueryAuditReportRequest {

    /**
     * 租户ID
     */
    @NotNull(message = "租户ID不能为空")
    private Long tenantId;

    /**
     * 报告类型
     */
    @NotNull(message = "报告类型不能为空")
    private String reportType;

    /**
     * 报告标题
     */
    private String title;

    /**
     * 报告周期开始时间
     */
    private LocalDateTime periodStartTime;

    /**
     * 报告周期结束时间
     */
    private LocalDateTime periodEndTime;

    /**
     * 报告格式
     */
    private String reportFormat = "json";

    /**
     * 详细程度
     */
    private String detailLevel = "standard";

    /**
     * 包含的报告章节
     */
    private List<String> includedSections;

    /**
     * 用户ID过滤器
     */
    private List<Long> userIds;

    /**
     * 模型编码过滤器
     */
    private List<String> modelCodes;

    /**
     * 查询类型过滤器
     */
    private List<String> queryTypes;

    /**
     * 是否只包含成功查询
     */
    private Boolean successfulQueriesOnly = false;

    /**
     * 是否只包含失败查询
     */
    private Boolean failedQueriesOnly = false;

    /**
     * 慢查询阈值(毫秒)
     */
    private Integer slowQueryThreshold = 5000;

    /**
     * 是否包含图表
     */
    private Boolean includeCharts = true;

    /**
     * 是否包含原始数据
     */
    private Boolean includeRawData = false;

    /**
     * 是否包含执行摘要
     */
    private Boolean includeExecutiveSummary = true;

    /**
     * 是否包含性能分析
     */
    private Boolean includePerformanceAnalysis = true;

    /**
     * 是否包含安全分析
     */
    private Boolean includeSecurityAnalysis = true;

    /**
     * 是否包含用户活动分析
     */
    private Boolean includeUserActivityAnalysis = true;

    /**
     * 是否包含模型使用分析
     */
    private Boolean includeModelUsageAnalysis = true;

    /**
     * 是否包含异常检测结果
     */
    private Boolean includeAnomalyDetection = true;

    /**
     * 是否包含趋势分析
     */
    private Boolean includeTrendAnalysis = true;

    /**
     * 是否包含建议和行动项
     */
    private Boolean includeRecommendations = true;

    /**
     * 分组维度
     */
    private List<String> groupByDimensions;

    /**
     * 排序字段
     */
    private String sortField = "createdAt";

    /**
     * 排序方向
     */
    private String sortDirection = "desc";

    /**
     * 最大记录数限制
     */
    private Integer maxRecords = 10000;

    /**
     * 自定义过滤条件
     */
    private Map<String, Object> customFilters;

    /**
     * 报告模板ID
     */
    private String templateId;

    /**
     * 是否异步生成
     */
    private Boolean asyncGeneration = false;

    /**
     * 报告接收人邮箱列表
     */
    private List<String> recipients;

    /**
     * 报告描述
     */
    private String description;

    /**
     * 自定义参数
     */
    private Map<String, Object> customParameters;
}