package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 查询审计日志DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class QueryAuditLogDTO {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 租户ID
     */
    private Long tenantId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 查询标识
     */
    private String queryId;

    /**
     * 查询名称
     */
    private String queryName;

    /**
     * 模型编码
     */
    private String modelCode;

    /**
     * 查询类型
     */
    private String queryType;

    /**
     * 查询条件
     */
    private String queryConditions;

    /**
     * 选择字段
     */
    private String selectFields;

    /**
     * 排序字段
     */
    private String sortFields;

    /**
     * 分页信息
     */
    private String paginationInfo;

    /**
     * 执行时间(毫秒)
     */
    private Integer executionTimeMs;

    /**
     * 结果记录数
     */
    private Integer resultCount;

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 错误类型
     */
    private String errorType;

    /**
     * IP地址
     */
    private String ipAddress;

    /**
     * 用户代理
     */
    private String userAgent;

    /**
     * 请求ID
     */
    private String requestId;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 查询复杂度分数
     */
    private Integer queryComplexityScore;

    /**
     * 是否命中缓存
     */
    private Boolean cacheHit;

    /**
     * 是否应用数据脱敏
     */
    private Boolean dataMaskingApplied;

    /**
     * 权限检查耗时(毫秒)
     */
    private Integer permissionCheckTimeMs;

    /**
     * 安全验证耗时(毫秒)
     */
    private Integer securityValidationTimeMs;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}