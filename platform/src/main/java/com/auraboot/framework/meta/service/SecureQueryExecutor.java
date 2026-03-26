package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.meta.dto.*;

import java.util.List;
import java.util.Map;

/**
 * 安全查询执行器接口
 * 提供防SQL注入的安全查询执行功能
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
public interface SecureQueryExecutor {

    // ==================== 核心查询执行方法 ====================

    /**
     * 执行安全查询
     * @param request 安全查询请求
     * @return 分页查询结果
     */
    <T> PaginationResult<T> executeSecureQuery(SecureQueryRequest request);

    /**
     * 执行安全查询（返回列表）
     * @param request 安全查询请求
     * @return 查询结果列表
     */
    <T> List<T> executeSecureQueryList(SecureQueryRequest request);

    /**
     * 执行安全查询（返回单个对象）
     * @param request 安全查询请求
     * @return 单个查询结果
     */
    <T> T executeSecureQuerySingle(SecureQueryRequest request);

    /**
     * 执行安全计数查询
     * @param request 安全查询请求
     * @return 记录总数
     */
    Long executeSecureCount(SecureQueryRequest request);

    /**
     * 执行安全聚合查询
     * @param request 安全查询请求
     * @return 聚合结果
     */
    Map<String, Object> executeSecureAggregate(SecureQueryRequest request);

    // ==================== 查询验证方法 ====================

    /**
     * 验证查询安全性
     * @param request 安全查询请求
     * @return 验证结果
     */
    QuerySecurityValidationResult validateQuerySecurity(SecureQueryRequest request);

    /**
     * 检查查询权限
     * @param request 安全查询请求
     * @return 权限检查结果
     */
    QueryAccessCheckResult checkQueryPermissions(SecureQueryRequest request);

    /**
     * 验证查询复杂度
     * @param request 安全查询请求
     * @return 复杂度验证结果
     */
    QueryComplexityValidationResult validateQueryComplexity(SecureQueryRequest request);

    /**
     * 检查查询限制
     * @param request 安全查询请求
     * @return 限制检查结果
     */
    QueryLimitCheckResult checkQueryLimits(SecureQueryRequest request);

    // ==================== 查询构建方法 ====================

    /**
     * 构建安全查询
     * @param request 安全查询请求
     * @return 查询构建器
     */
    QueryBuilderService.QueryBuilder buildSecureQuery(SecureQueryRequest request);

    /**
     * 应用权限过滤
     * @param queryBuilder 查询构建器
     * @param request 安全查询请求
     * @return 应用权限后的查询构建器
     */
    QueryBuilderService.QueryBuilder applyPermissionFilters(QueryBuilderService.QueryBuilder queryBuilder, 
                                                           SecureQueryRequest request);

    /**
     * 应用数据脱敏
     * @param data 原始数据
     * @param request 安全查询请求
     * @return 脱敏后的数据
     */
    <T> T applyDataMasking(T data, SecureQueryRequest request);

    /**
     * 应用字段权限过滤
     * @param data 原始数据
     * @param request 安全查询请求
     * @return 过滤后的数据
     */
    <T> T applyFieldPermissionFilter(T data, SecureQueryRequest request);

    // ==================== 缓存管理方法 ====================

    /**
     * 获取查询缓存
     * @param request 安全查询请求
     * @return 缓存结果
     */
    <T> T getQueryCache(SecureQueryRequest request);

    /**
     * 设置查询缓存
     * @param request 安全查询请求
     * @param result 查询结果
     */
    <T> void setQueryCache(SecureQueryRequest request, T result);

    /**
     * 清除查询缓存
     * @param request 安全查询请求
     */
    void clearQueryCache(SecureQueryRequest request);

    /**
     * 生成缓存键
     * @param request 安全查询请求
     * @return 缓存键
     */
    String generateCacheKey(SecureQueryRequest request);

    // ==================== 审计日志方法 ====================

    /**
     * 记录查询审计日志
     * @param request 安全查询请求
     * @param result 查询结果
     * @param executionTimeMs 执行时间（毫秒）
     */
    void logQueryAudit(SecureQueryRequest request, Object result, long executionTimeMs);

    /**
     * 记录查询错误日志
     * @param request 安全查询请求
     * @param error 错误信息
     * @param executionTimeMs 执行时间（毫秒）
     */
    void logQueryError(SecureQueryRequest request, Throwable error, long executionTimeMs);

    // ==================== 性能监控方法 ====================

    /**
     * 获取查询性能统计
     * @param modelCode 模型编码
     * @param userId 用户ID
     * @return 性能统计结果
     */
    QueryPerformanceStatistics getQueryPerformanceStatistics(String modelCode, Long userId);

    /**
     * 获取查询执行计划
     * @param request 安全查询请求
     * @return 执行计划
     */
    QueryExecutionPlan getQueryExecutionPlan(SecureQueryRequest request);

    /**
     * 优化查询性能
     * @param request 安全查询请求
     * @return 优化建议
     */
    QueryOptimizationSuggestion optimizeQuery(SecureQueryRequest request);
}