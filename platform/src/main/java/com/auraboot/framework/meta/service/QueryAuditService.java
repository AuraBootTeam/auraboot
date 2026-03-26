package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.meta.dto.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 查询审计服务接口
 * 提供查询操作的审计日志记录和查询功能
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
public interface QueryAuditService {

    // ==================== 审计日志记录 ====================

    /**
     * 记录查询执行日志
     * @param request 查询请求
     * @param result 查询结果
     * @param executionTimeMs 执行时间（毫秒）
     */
    void logQueryExecution(SecureQueryRequest request, Object result, long executionTimeMs);

    /**
     * 记录查询错误日志
     * @param request 查询请求
     * @param error 错误信息
     * @param executionTimeMs 执行时间（毫秒）
     */
    void logQueryError(SecureQueryRequest request, Throwable error, long executionTimeMs);

    /**
     * 记录权限检查日志
     * @param request 查询请求
     * @param permissionResult 权限检查结果
     */
    void logPermissionCheck(SecureQueryRequest request, QueryAccessCheckResult permissionResult);

    /**
     * 记录安全验证日志
     * @param request 查询请求
     * @param securityResult 安全验证结果
     */
    void logSecurityValidation(SecureQueryRequest request, QuerySecurityValidationResult securityResult);

    // ==================== 审计日志查询 ====================

    /**
     * 分页查询审计日志
     * @param request 查询请求
     * @return 分页结果
     */
    PageResult<QueryAuditLogDTO> queryAuditLogs(QueryAuditLogQueryRequest request);

    /**
     * 根据用户查询审计日志
     * @param userId 用户ID
     * @param tenantId 租户ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 审计日志列表
     */
    List<QueryAuditLogDTO> queryAuditLogsByUser(Long userId, Long tenantId, Instant startTime, Instant endTime);

    /**
     * 根据模型查询审计日志
     * @param modelCode 模型编码
     * @param tenantId 租户ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 审计日志列表
     */
    List<QueryAuditLogDTO> queryAuditLogsByModel(String modelCode, Long tenantId, Instant startTime, Instant endTime);

    /**
     * 查询失败的查询日志
     * @param tenantId 租户ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 失败查询日志列表
     */
    List<QueryAuditLogDTO> queryFailedQueries(Long tenantId, Instant startTime, Instant endTime);

    // ==================== 审计统计分析 ====================

    /**
     * 获取查询统计信息
     * @param request 统计请求
     * @return 统计结果
     */
    QueryAuditStatistics getQueryStatistics(QueryAuditStatisticsRequest request);

    /**
     * 获取用户查询统计
     * @param userId 用户ID
     * @param tenantId 租户ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 用户查询统计
     */
    UserQueryStatistics getUserQueryStatistics(Long userId, Long tenantId, Instant startTime, Instant endTime);

    /**
     * 获取模型查询统计
     * @param modelCode 模型编码
     * @param tenantId 租户ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 模型查询统计
     */
    ModelQueryStatistics getModelQueryStatistics(String modelCode, Long tenantId, Instant startTime, Instant endTime);

    /**
     * 获取查询性能统计
     * @param tenantId 租户ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 性能统计
     */
    QueryPerformanceStatistics getQueryPerformanceStatistics(Long tenantId, Instant startTime, Instant endTime);

    // ==================== 异常检测 ====================

    /**
     * 检测异常查询行为
     * @param request 异常检测请求
     * @return 异常检测结果
     */
    QueryAnomalyDetectionResult detectAnomalousQueries(QueryAnomalyDetectionRequest request);

    /**
     * 检测频繁查询
     * @param userId 用户ID
     * @param tenantId 租户ID
     * @param timeWindowMinutes 时间窗口（分钟）
     * @param threshold 阈值
     * @return 是否频繁查询
     */
    boolean detectFrequentQueries(Long userId, Long tenantId, int timeWindowMinutes, int threshold);

    /**
     * 检测慢查询
     * @param tenantId 租户ID
     * @param thresholdMs 慢查询阈值（毫秒）
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 慢查询列表
     */
    List<QueryAuditLogDTO> detectSlowQueries(Long tenantId, long thresholdMs, Instant startTime, Instant endTime);

    /**
     * 检测可疑查询模式
     * @param tenantId 租户ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 可疑查询列表
     */
    List<QueryAuditLogDTO> detectSuspiciousQueryPatterns(Long tenantId, Instant startTime, Instant endTime);

    // ==================== 审计配置管理 ====================

    /**
     * 获取审计配置
     * @param tenantId 租户ID
     * @return 审计配置
     */
    QueryAuditConfig getAuditConfig(Long tenantId);

    /**
     * 更新审计配置
     * @param tenantId 租户ID
     * @param config 审计配置
     */
    void updateAuditConfig(Long tenantId, QueryAuditConfig config);

    /**
     * 启用/禁用审计
     * @param tenantId 租户ID
     * @param enabled 是否启用
     */
    void setAuditEnabled(Long tenantId, boolean enabled);

    // ==================== 数据清理 ====================

    /**
     * 清理过期的审计日志
     * @param tenantId 租户ID
     * @param retentionDays 保留天数
     * @return 清理的记录数
     */
    int cleanupExpiredAuditLogs(Long tenantId, int retentionDays);

    /**
     * 归档审计日志
     * @param tenantId 租户ID
     * @param archiveBeforeDate 归档截止日期
     * @return 归档的记录数
     */
    int archiveAuditLogs(Long tenantId, Instant archiveBeforeDate);

    // ==================== 报告生成 ====================

    /**
     * 生成审计报告
     * @param request 报告生成请求
     * @return 审计报告
     */
    QueryAuditReport generateAuditReport(QueryAuditReportRequest request);

    /**
     * 导出审计日志
     * @param request 导出请求
     * @return 导出结果
     */
    QueryAuditExportResult exportAuditLogs(QueryAuditExportRequest request);
}