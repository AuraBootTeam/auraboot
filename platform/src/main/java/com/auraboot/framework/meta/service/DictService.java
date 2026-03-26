package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.Dict;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * 字典管理服务接口
 * 提供字典的CRUD操作、版本管理和级联字典功能
 */
public interface DictService {

    // ==================== 基础CRUD操作 ====================

    /**
     * 创建字典
     * @param request 创建请求
     * @return 字典响应
     */
    DictDTO create(DictCreateRequest request);

    /**
     * 更新字典
     * @param pid 字典PID
     * @param request 更新请求
     * @return 字典响应
     */
    DictDTO update(String pid, DictUpdateRequest request);

    /**
     * 删除字典（软删除）
     * @param pid 字典PID
     */
    void delete(String pid);

    /**
     * 根据PID查询字典
     * @param pid 字典PID
     * @return 字典响应
     */
    DictDTO findByPid(String pid);


    /**
     * 根据字典编码查询字典（不抛异常）
     * @param code 字典编码
     * @return 字典响应，如果不存在返回 null
     */
    DictDTO findByCode(String code);

    // ==================== 查询操作 ====================

    /**
     * 分页查询字典
     * @param request 查询请求
     * @return 分页结果
     */
    Page<DictDTO> findPage(DictQueryRequest request);

    /**
     * 查询租户下的所有字典
     * @param tenantId 租户ID
       
     * @return 字典列表
     */
    List<DictDTO> findByTenant( );

    /**
     * 根据状态查询字典
     * @param tenantId 租户ID
       
     * @param status 状态
     * @return 字典列表
     */
    List<DictDTO> findByStatus(    String status);

    /**
     * 根据类型查询字典
     * @param tenantId 租户ID
       
     * @param dictType 字典类型
     * @return 字典列表
     */
    List<DictDTO> findByType(    String dictType);

    /**
     * 搜索字典
     * @param tenantId 租户ID
       
     * @param keyword 关键词
     * @return 字典列表
     */
    List<DictDTO> search(    String keyword);

    // ==================== 版本管理 ====================

    /**
     * 发布字典
     * @param pid 字典PID
     * @param versionNote 版本说明
     * @return 字典响应
     */
    DictDTO publish(String pid, String versionNote);

    /**
     * 取消发布字典
     * @param pid 字典PID
     * @return 字典响应
     */
    DictDTO unpublish(String pid);

    /**
     * 创建字典版本
     * @param pid 字典PID
     * @param versionNote 版本说明
     * @return 新版本字典响应
     */
    DictDTO createVersion(String pid, String versionNote);

    /**
     * 获取字典版本历史
     * @param code 字典编码
     * @param tenantId 租户ID
       
     * @return 版本历史列表
     */
    List<DictDTO> getVersionHistory(String code  );

    // ==================== 级联字典支持 ====================

    /**
     * 获取级联字典的子字典
     * @param parentPid 父字典PID
     * @param parentValue 父字典值
     * @return 子字典列表
     */
    List<DictItemData> getCascadeChildren(String parentPid, String parentValue);

    /**
     * 构建级联字典树
     * @param rootPid 根字典PID
     * @return 字典树
     */
    DictTreeNode buildCascadeTree(String rootPid);

    // ==================== 字典数据加载 ====================

    /**
     * 根据版本策略加载字典数据
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @param versionStrategy 版本策略（PINNED/LATEST）
     * @param pinnedVersion 固定版本号（当策略为PINNED时使用）
     * @return 字典数据
     */
    DictDataResult loadDictData(    String code, 
                               String versionStrategy, String pinnedVersion);

    /**
     * 批量加载字典数据
     * @param requests 加载请求列表
     * @return 字典数据映射
     */
    List<DictDataResult> batchLoadDictData(List<DictLoadRequest> requests);

    // ==================== 统计和验证 ====================

    /**
     * 统计字典数量
     * @param tenantId 租户ID
       
     * @return 统计结果
     */
    DictStatistics getStatistics( );

    /**
     * 验证字典编码唯一性
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @param excludePid 排除的PID
     * @return 是否唯一
     */
    boolean isCodeUnique(    String code, String excludePid);

    /**
     * 验证字典配置
     * @param pid 字典PID
     * @return 验证结果
     */
    DictValidationResult validateConfig(String pid);

    // ==================== 批量操作 ====================

    /**
     * 批量创建字典
     * @param requests 创建请求列表
     * @return 创建结果列表
     */
    List<DictDTO> batchCreate(List<DictCreateRequest> requests);

    /**
     * 批量更新字典状态
     * @param pids 字典PID列表
     * @param status 新状态
     * @return 更新数量
     */
    int batchUpdateStatus(List<String> pids, String status);

    /**
     * 批量删除字典
     * @param pids 字典PID列表
     * @return 删除数量
     */
    int batchDelete(List<String> pids);

    // ==================== 导入导出 ====================

    /**
     * 导入字典
     * @param tenantId 租户ID
       
     * @param dictData 字典数据
     * @return 导入结果
     */
    DictImportResult importDicts(    List<DictCreateRequest> dictData);

    /**
     * 导出字典
     * @param tenantId 租户ID

     * @param codes 字典编码列表
     * @return 字典数据
     */
    List<DictDTO> exportDicts(    List<String> codes);

    // ==================== 字典项管理 ====================

    /**
     * 替换字典项
     * 删除现有字典项并重新创建
     * @param dictPid 字典PID
     * @param items 新的字典项列表
     * @return 更新后的字典响应
     */
    DictDTO replaceItems(String dictPid, List<DictCreateRequest.DictItemCreateRequest> items);

    /**
     * Replace only PLUGIN-sourced dict items, preserving USER-sourced items.
     * Used during plugin reimport to avoid losing user-added dict items.
     */
    DictDTO replacePluginItems(String dictPid, List<DictCreateRequest.DictItemCreateRequest> items);

    /**
     * Mark all items of a dict as PLUGIN-sourced.
     * Called after plugin import creates a new dict to properly tag items.
     */
    void markItemsAsPluginSource(String dictPid);
}