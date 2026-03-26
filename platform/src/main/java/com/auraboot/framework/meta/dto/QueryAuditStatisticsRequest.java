package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 查询审计统计请求DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class QueryAuditStatisticsRequest {

    /**
     * 租户ID
     */
    @NotNull(message = "租户ID不能为空")
    private Long tenantId;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 用户ID列表
     */
    private List<Long> userIds;

    /**
     * 模型编码列表
     */
    private List<String> modelCodes;

    /**
     * 查询类型列表
     */
    private List<String> queryTypes;

    /**
     * 是否包含成功查询
     */
    private Boolean includeSuccessful = true;

    /**
     * 是否包含失败查询
     */
    private Boolean includeFailed = true;

    /**
     * 慢查询阈值(毫秒)
     */
    private Integer slowQueryThreshold = 5000;

    /**
     * 是否包含按小时统计
     */
    private Boolean includeHourlyStats = false;

    /**
     * 是否包含按日期统计
     */
    private Boolean includeDailyStats = false;

    /**
     * 是否包含性能趋势
     */
    private Boolean includePerformanceTrend = false;

    /**
     * 是否包含热门查询条件
     */
    private Boolean includePopularConditions = false;

    /**
     * 热门查询条件数量限制
     */
    private Integer popularConditionsLimit = 10;

    /**
     * 分组维度
     */
    private List<String> groupByDimensions;

    /**
     * 排序字段
     */
    private String sortField = "totalQueries";

    /**
     * 排序方向
     */
    private String sortDirection = "desc";
}