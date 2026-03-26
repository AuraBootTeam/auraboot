package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.PageSchema;

import java.util.List;
import java.util.Map;

/**
 * Schema权限投影服务接口
 * 
 * 负责将用户权限投影到Schema定义上，实现字段级权限控制和数据脱敏
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
public interface SchemaAccessProjector {


    // ==================== 动态权限计算 ====================

    /**
     * 计算动态Schema权限
     * 基于上下文信息动态计算Schema权限
     * 
     * @param request 动态权限计算请求
     * @return 动态权限计算结果
     */
    DynamicSchemaAccessResult calculateDynamicSchemaAccesss(DynamicSchemaAccessRequest request);

    // ==================== 字段级权限过滤 ====================

    /**
     * 过滤Schema字段
     * 根据权限过滤Schema中的字段
     * 
     * @param schema 原始Schema
     * @param userId 用户ID
     * @param tenantId 租户ID
     * @param context 权限上下文
     * @return 过滤后的Schema
     */
    PageSchema filterSchemaFields(PageSchema schema, Long userId, Long tenantId, Map<String, Object> context);

    /**
     * 过滤字段列表
     * 根据权限过滤字段列表
     * 
     * @param request 字段过滤请求
     * @return 字段过滤结果
     */
    FieldFilterResult filterFields(FieldFilterRequest request);

    // ==================== 缓存管理 ====================

    /**
     * 刷新Schema权限缓存
     * 
     * @param request 缓存刷新请求
     * @return 缓存刷新结果
     */
    SimpleResult refreshSchemaPermissionCache(Map<String, Object> request);

    /**
     * 清理Schema权限缓存
     * 
     * @param request 缓存清理请求
     * @return 缓存清理结果
     */
    SimpleResult clearSchemaPermissionCache(Map<String, Object> request);

    // ==================== 权限审计 ====================

    /**
     * 记录Schema权限访问日志
     * 
     * @param request 权限访问日志记录请求
     */
    void logSchemaPermissionAccess(Map<String, Object> request);

    /**
     * 分析Schema权限使用情况
     * 
     * @param request 权限使用分析请求
     * @return 权限使用分析结果
     */
    SimpleResult analyzeSchemaPermissionUsage(Map<String, Object> request);

    /**
     * 检测Schema权限异常
     * 
     * @param request 权限异常检测请求
     * @return 权限异常检测结果
     */
    SimpleResult detectSchemaPermissionAnomalies(Map<String, Object> request);

    // ==================== 权限验证 ====================

    /**
     * 验证Schema权限投影
     * 验证投影结果的正确性和完整性
     * 
     * @param request 权限投影验证请求
     * @return 权限投影验证结果
     */
    SimpleResult validateSchemaAccessProjection(Map<String, Object> request);

    /**
     * 验证字段权限一致性
     * 验证字段权限的一致性
     * 
     * @param request 字段权限一致性验证请求
     * @return 字段权限一致性验证结果
     */
    SimpleResult validateFieldPermissionConsistency(Map<String, Object> request);

    /**
     * 验证操作权限完整性
     * 验证操作权限的完整性
     * 
     * @param request 操作权限完整性验证请求
     * @return 操作权限完整性验证结果
     */
    SimpleResult validateOperationPermissionIntegrity(Map<String, Object> request);
}