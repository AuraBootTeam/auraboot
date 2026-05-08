package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.dto.AddFieldRequest;
import com.auraboot.framework.meta.dto.AddFieldResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;
import java.util.Optional;

/**
 * 字段元数据服务接口
 * 提供字段定义的CRUD操作和元数据管理功能
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
public interface MetaFieldService {

    // ==================== 基础CRUD操作 ====================

    /**
     * 创建字段定义
     * @param request 创建请求
     * @return 创建的字段DTO
     */
    MetaFieldDTO create(MetaFieldCreateRequest request);

    /**
     * Atomic "add field to existing model". Encapsulates field+binding+publish
     * handshake. Designed as the single source of truth for AuraBot skills,
     * CLI commands, and any other automation. See spec §3 for invariants.
     *
     * @throws com.auraboot.framework.exception.ValidationException if modelCode unknown / code conflicts /
     *         dataType invalid (not in {@code string|text|int|long|decimal|
     *         boolean|date|datetime|json}).
     */
    AddFieldResult addToModel(AddFieldRequest request);

    /**
     * 根据PID查找字段
     * @param pid 字段PID
     * @return 字段DTO
     */
    MetaFieldDTO findByPid(String pid);

    /**
     * 根据ID查找字段
     * @param id 字段ID
     * @return 字段DTO
     */
    MetaFieldDTO findById(Long id);

    /**
     * 更新字段定义
     * @param pid 字段PID
     * @param request 更新请求
     * @return 更新后的字段DTO
     */
    MetaFieldDTO update(String pid, MetaFieldUpdateRequest request);

    /**
     * 删除字段定义
     * @param pid 字段PID
     */
    void delete(String pid);

    /**
     * 分页查询字段列表
     * @param page 页码
     * @param size 每页大小
     * @param code 字段键（模糊查询）
     * @param dataType 数据类型
     * @param status 状态
       
     * @param currentOnly 是否只查询当前版本
     * @return 分页结果
     */
    PageResult<MetaFieldDTO> listFields(Integer page, Integer size, String code, 
                                       String dataType, String status,
                                        Boolean currentOnly);

    // ==================== 字段查询 ====================

    /**
     * 根据字段键查询当前版本字段
     * @param code 字段键
     * @return 字段DTO
     */
    Optional<MetaFieldDTO> findCurrentByCode(String code);

    /**
     * 根据字段键和版本查询字段
     * @param code 字段键
     * @param version 版本号
     * @return 字段DTO
     */
    Optional<MetaFieldDTO> findByCodeAndVersion(String code, Integer version);

    /**
     * 查询指定租户下的所有当前版本字段
     * @return 字段DTO列表
     */
    List<MetaFieldDTO> findCurrentByTenant();

    /**
     * 查询指定字段的所有版本
     * @param code 字段键
     * @return 字段版本列表
     */
    List<MetaFieldDTO> findAllVersionsByCode(String code);

    /**
     * 根据数据类型查询字段
     * @param dataType 数据类型
     * @return 字段列表
     */
    List<MetaFieldDTO> findByDataType(String dataType);

    /**
     * 根据数据源查询字段
     * @param dataSourceId 数据源ID
     * @return 字段列表
     */
    List<MetaFieldDTO> findByDataSource(Long dataSourceId);

    /**
     * 根据状态查询字段
     * @param status 状态
     * @return 字段列表
     */
    List<MetaFieldDTO> findByStatus(String status);

    // ==================== 字段验证 ====================

    /**
     * 检查字段键是否唯一
     * @param code 字段键
     * @param excludePid 排除的PID（用于更新时检查）
     * @return 是否唯一
     */
    boolean isCodeUnique(String code, String excludePid);

    /**
     * 检查字段是否存在
     * @param code 字段键
     * @return 是否存在
     */
    boolean isFieldExists(String code);

    /**
     * 验证字段定义
     * @param code 字段键
     * @return 验证结果
     */
    MetaFieldValidationResult validateField(String code);

    // ==================== 字典绑定 ====================

    /**
     * 绑定字典到字段
     * @param fieldPid 字段PID
     * @param dictCode 字典编码
     * @return 绑定结果
     */
    boolean bindDictionary(String fieldPid, String dictCode);

    /**
     * 解绑字段的字典
     * @param fieldPid 字段PID
     * @return 解绑结果
     */
    boolean unbindDictionary(String fieldPid);

    /**
     * 获取字段绑定的字典信息
     * @param fieldPid 字段PID
     * @return 字典信息
     */
    Optional<DictDTO> getBoundDictionary(String fieldPid);

    // ==================== 版本管理 ====================

    /**
     * 发布字段版本
     * @param pid 字段PID
     * @return 发布结果
     */
    MetaFieldDTO publishVersion(String pid);

    /**
     * 回滚到指定版本
     * @param code 字段键
     * @param version 目标版本
     * @return 回滚结果
     */
    MetaFieldDTO rollbackToVersion(String code, Integer version);

    /**
     * 获取字段的下一个版本号
     * @param code 字段键
     * @return 下一个版本号
     */
    Integer getNextVersion(String code);

    // ==================== 缓存管理 ====================

    /**
     * 刷新字段缓存
     * @param code 字段键
     */
    void refreshFieldCache(String code);

    /**
     * 清除所有字段缓存
     */
    void clearAllFieldCache();
}