package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.DictDataResult;
import com.auraboot.framework.meta.dto.DictLoadRequest;
import com.auraboot.framework.meta.dto.DictVersionInfo;
import com.auraboot.framework.meta.entity.Dict;

import java.util.List;
import java.util.Map;

/**
 * 字典版本策略服务接口
 * 提供字典版本管理和数据加载的统一接口
 * 支持PINNED和LATEST两种版本策略
 */
public interface DictVersionService {

    // ==================== 版本策略管理 ====================

    /**
     * 根据版本策略加载字典数据
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @param versionStrategy 版本策略（PINNED/LATEST）
     * @param pinnedVersion 固定版本号（当策略为PINNED时使用）
     * @return 字典数据结果
     */
    DictDataResult loadDictByStrategy(    String code, 
                                     String versionStrategy, String pinnedVersion);

    /**
     * 批量根据版本策略加载字典数据
     * @param requests 加载请求列表
     * @return 字典数据结果列表
     */
    List<DictDataResult> batchLoadDictByStrategy(List<DictLoadRequest> requests);

    /**
     * 获取字典的版本信息
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @return 版本信息
     */
    DictVersionInfo getDictVersionInfo(    String code);

    /**
     * 获取字典的所有可用版本
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @return 版本列表
     */
    List<String> getAvailableVersions(    String code);

    // ==================== 小字典和大字典统一加载 ====================

    /**
     * 统一加载字典数据（自动识别小字典和大字典）
     * @param dict 字典实体
     * @return 字典数据结果
     */
    DictDataResult loadUnifiedDictData(Dict dict);

    /**
     * 加载小字典数据（直接从Dict.items字段）
     * @param dict 字典实体
     * @return 字典数据结果
     */
    DictDataResult loadStaticDictData(Dict dict);

    /**
     * 加载大字典数据（从DictItem表）
     * @param dict 字典实体
     * @return 字典数据结果
     */
    DictDataResult loadDynamicDictData(Dict dict);

    /**
     * 加载级联字典数据
     * @param dict 字典实体
     * @param parentValue 父级值（可选）
     * @return 字典数据结果
     */
    DictDataResult loadCascadeDictData(Dict dict, String parentValue);

    // ==================== 版本切换和管理 ====================

    /**
     * 切换字典的当前版本
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @param targetVersion 目标版本号
     * @return 是否切换成功
     */
    boolean switchCurrentVersion(    String code, Integer targetVersion);

    /**
     * 获取字典的当前版本
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @return 当前版本的字典实体
     */
    Dict getCurrentVersion(    String code);

    /**
     * 获取字典的指定版本
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @param version 版本号
     * @return 指定版本的字典实体
     */
    Dict getSpecificVersion(    String code, Integer version);

    // ==================== 版本兼容性和验证 ====================

    /**
     * 验证版本策略配置
     * @param versionStrategy 版本策略
     * @param pinnedVersion 固定版本号
     * @return 是否有效
     */
    boolean validateVersionStrategy(String versionStrategy, String pinnedVersion);

    /**
     * 检查版本是否存在
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @param version 版本号
     * @return 是否存在
     */
    boolean isVersionExists(    String code, Integer version);

    /**
     * 获取版本兼容性信息
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @param fromVersion 起始版本
     * @param toVersion 目标版本
     * @return 兼容性信息
     */
    Map<String, Object> getVersionCompatibility(    String code, 
                                               Integer fromVersion, Integer toVersion);

    // ==================== 缓存管理 ====================

    /**
     * 预热字典缓存
     * @param tenantId 租户ID
       
     * @param codes 字典编码列表（为空时预热所有字典）
     */
    void warmupDictCache(    List<String> codes);

    /**
     * 清除字典缓存
     * @param tenantId 租户ID
       
     * @param code 字典编码
     */
    void clearDictCache(    String code);

    /**
     * 清除所有字典缓存
     * @param tenantId 租户ID
       
     */
    void clearAllDictCache( );

    // ==================== 统计和监控 ====================

    /**
     * 获取版本策略使用统计
     * @param tenantId 租户ID
       
     * @return 统计信息
     */
    Map<String, Long> getVersionStrategyStats( );

    /**
     * 获取字典加载性能统计
     * @param tenantId 租户ID
       
     * @return 性能统计
     */
    Map<String, Object> getDictLoadPerformanceStats( );
}