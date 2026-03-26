package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 查询审计日志查询请求DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class QueryAuditLogQueryRequest {

    /**
     * 租户ID
     */
    @NotNull(message = "租户ID不能为空")
    private Long tenantId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 模型编码
     */
    private String modelCode;

    /**
     * 查询类型
     */
    private String queryType;

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 最小执行时间(毫秒)
     */
    @Min(value = 0, message = "最小执行时间不能小于0")
    private Integer minExecutionTimeMs;

    /**
     * 最大执行时间(毫秒)
     */
    @Min(value = 0, message = "最大执行时间不能小于0")
    private Integer maxExecutionTimeMs;

    /**
     * IP地址
     */
    private String ipAddress;

    /**
     * 请求ID
     */
    private String requestId;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 是否命中缓存
     */
    private Boolean cacheHit;

    /**
     * 页码
     */
    @Min(value = 1, message = "页码不能小于1")
    private Integer page = 1;

    /**
     * 页大小
     */
    @Min(value = 1, message = "页大小不能小于1")
    private Integer size = 20;

    /**
     * 排序字段
     */
    private String sortField = "createdAt";

    /**
     * 排序方向
     */
    private String sortDirection = "desc";
}