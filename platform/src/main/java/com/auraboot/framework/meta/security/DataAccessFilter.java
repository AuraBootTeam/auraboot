package com.auraboot.framework.meta.security;

import com.auraboot.framework.meta.dto.*;

import java.util.Map;

/**
 * 数据权限过滤器接口
 * 
 * 负责在数据访问层面进行权限过滤和数据脱敏
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
public interface DataAccessFilter {

    // ==================== 数据过滤核心方法 ====================

    /**
     * 过滤查询结果数据
     * 根据用户权限过滤和脱敏查询结果
     * 
     * @param request 数据过滤请求
     * @return 数据过滤结果
     */
    DataFilterResult filterQueryResult(DataFilterRequest request);

    /**
     * 批量过滤数据
     * 
     * @param request 批量数据过滤请求
     * @return 批量数据过滤结果
     */
    SimpleResult batchFilterData(Map<String, Object> request);

    /**
     * 过滤单条记录
     * 
     * @param request 记录过滤请求
     * @return 记录过滤结果
     */
    SimpleResult filterRecord(Map<String, Object> request);

    // ==================== 数据脱敏 ====================

    /**
     * 应用数据脱敏
     * 根据脱敏规则对数据进行脱敏处理
     * 
     * @param request 数据脱敏请求
     * @return 数据脱敏结果
     */
    DataMaskingResult applyDataMasking(DataMaskingRequest request);

    /**
     * 获取字段脱敏规则
     * 
     * @param request 脱敏规则获取请求
     * @return 脱敏规则获取结果
     */
    SimpleResult getFieldMaskingRule(Map<String, Object> request);

    // ==================== 动态脱敏策略 ====================

    /**
     * 计算动态脱敏策略
     * 基于上下文动态计算脱敏策略
     * 
     * @param request 动态脱敏策略请求
     * @return 动态脱敏策略结果
     */
    SimpleResult calculateDynamicMaskingStrategy(Map<String, Object> request);

    // ==================== 数据权限缓存 ====================

    /**
     * 预热数据权限缓存
     * 
     * @param request 缓存预热请求
     * @return 缓存预热结果
     */
    SimpleResult warmupDataPermissionCache(Map<String, Object> request);

    /**
     * 刷新数据权限缓存
     * 
     * @param request 缓存刷新请求
     * @return 缓存刷新结果
     */
    SimpleResult refreshDataPermissionCache(Map<String, Object> request);

    /**
     * 清理数据权限缓存
     * 
     * @param request 缓存清理请求
     * @return 缓存清理结果
     */
    SimpleResult clearDataPermissionCache(Map<String, Object> request);

    // ==================== 权限验证 ====================

    /**
     * 验证数据访问权限
     * 
     * @param request 数据访问权限验证请求
     * @return 数据访问权限验证结果
     */
    SimpleResult validateDataAccessPermission(Map<String, Object> request);

    /**
     * 验证数据修改权限
     * 
     * @param request 数据修改权限验证请求
     * @return 数据修改权限验证结果
     */
    SimpleResult validateDataModificationPermission(Map<String, Object> request);

    /**
     * 验证数据导出权限
     * 
     * @param request 数据导出权限验证请求
     * @return 数据导出权限验证结果
     */
    SimpleResult validateDataExportPermission(Map<String, Object> request);

    // ==================== 审计和监控 ====================

    /**
     * 记录数据访问日志
     * 
     * @param request 数据访问日志记录请求
     */
    void logDataAccess(DataAccessLogRequest request);

    /**
     * 分析数据访问模式
     * 
     * @param request 数据访问模式分析请求
     * @return 数据访问模式分析结果
     */
    SimpleResult analyzeDataAccessPattern(Map<String, Object> request);

    /**
     * 检测数据访问异常
     * 
     * @param request 数据访问异常检测请求
     * @return 数据访问异常检测结果
     */
    SimpleResult detectDataAccessAnomalies(Map<String, Object> request);

    // ==================== 规则引擎 ====================

    /**
     * 执行数据过滤规则
     * 
     * @param request 数据过滤规则执行请求
     * @return 数据过滤规则执行结果
     */
    SimpleResult executeDataFilterRules(Map<String, Object> request);

    /**
     * 验证数据过滤规则
     * 
     * @param request 数据过滤规则验证请求
     * @return 数据过滤规则验证结果
     */
    SimpleResult validateDataFilterRules(Map<String, Object> request);

    /**
     * 优化数据过滤规则
     * 
     * @param request 数据过滤规则优化请求
     * @return 数据过滤规则优化结果
     */
    SimpleResult optimizeDataFilterRules(Map<String, Object> request);
}