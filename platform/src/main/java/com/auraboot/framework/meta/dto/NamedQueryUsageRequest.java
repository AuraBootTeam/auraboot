package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;

/**
 * 命名查询使用情况查询请求DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryUsageRequest {

    /**
     * 统计开始时间
     */
    private LocalDateTime startTime;

    /**
     * 统计结束时间
     */
    private LocalDateTime endTime;

    /**
     * 统计粒度
     */
    private String granularity = "day"; // HOUR, DAY, WEEK, MONTH

    /**
     * 统计类型
     */
    private String usageType = "all"; // ALL, EXECUTION, ERROR, PERFORMANCE

    /**
     * 分组维度
     */
    private String groupBy = "time"; // TIME, USER, TENANT, ENVIRONMENT

    /**
     * 是否包含详细信息
     */
    private Boolean includeDetails = false;

    /**
     * 页码
     */
    @Min(value = 1, message = "页码必须大于0")
    private Integer page = 1;

    /**
     * 页大小
     */
    @Min(value = 1, message = "页大小必须大于0")
    @Max(value = 1000, message = "页大小不能超过1000")
    private Integer size = 100;

    /**
     * 排序字段
     */
    private String sortBy = "timestamp";

    /**
     * 排序方向
     */
    private String sortDirection = "desc";

    /**
     * 过滤条件
     */
    private String filter;

    /**
     * 用户ID过滤
     */
    private Long userId;

    /**
     * 环境过滤
     */
    private String environment;

    /**
     * 状态过滤
     */
    private String status;
}