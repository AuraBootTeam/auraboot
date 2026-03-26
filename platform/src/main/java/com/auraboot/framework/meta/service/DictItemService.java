package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * 字典项管理服务接口
 * 提供字典项的CRUD操作、批量操作和排序管理功能
 */
public interface DictItemService {

    // ==================== 基础CRUD操作 ====================

    /**
     * 创建字典项
     * @param request 创建请求
     * @return 字典项响应
     */
    DictItemDTO create(DictItemCreateRequest request);

    /**
     * 更新字典项
     * @param pid 字典项PID
     * @param request 更新请求
     * @return 字典项响应
     */
    DictItemDTO update(String pid, DictItemUpdateRequest request);

    /**
     * 删除字典项（软删除）
     * @param pid 字典项PID
     */
    void delete(String pid);

    /**
     * 根据PID查询字典项
     * @param pid 字典项PID
     * @return 字典项响应
     */
    DictItemDTO findByPid(String pid);

    /**
     * 根据字典和值查询字典项
     * @param dictPid 字典PID
     * @param value 字典项值
     * @return 字典项响应
     */
    DictItemDTO findByDictAndValue(String dictPid, String value);

    // ==================== 查询操作 ====================

    /**
     * 分页查询字典项
     * @param request 查询请求
     * @return 分页结果
     */
    Page<DictItemDTO> findPage(DictItemQueryRequest request);

    /**
     * 查询字典下的所有字典项
     * @param dictPid 字典PID
     * @return 字典项列表
     */
    List<DictItemDTO> findByDict(String dictPid);

    /**
     * 查询字典下指定状态的字典项
     * @param dictPid 字典PID
     * @param status 状态
     * @return 字典项列表
     */
    List<DictItemDTO> findByDictAndStatus(String dictPid, String status);

    /**
     * 查询级联字典的子项
     * @param dictPid 字典PID
     * @param parentValue 父级值
     * @return 字典项列表
     */
    List<DictItemDTO> findCascadeChildren(String dictPid, String parentValue);

    /**
     * 查询级联字典的根项
     * @param dictPid 字典PID
     * @return 字典项列表
     */
    List<DictItemDTO> findCascadeRoots(String dictPid);

    /**
     * 搜索字典项
     * @param dictPid 字典PID
     * @param keyword 关键词
     * @return 字典项列表
     */
    List<DictItemDTO> search(String dictPid, String keyword);

    // ==================== 排序管理 ====================

    /**
     * 更新字典项排序
     * @param pid 字典项PID
     * @param sortNo 新排序号
     * @return 字典项响应
     */
    DictItemDTO updateSort(String pid, Integer sortNo);

    /**
     * 批量更新字典项排序
     * @param sortRequests 排序请求列表
     * @return 更新数量
     */
    int batchUpdateSort(List<DictItemSortRequest> sortRequests);

    /**
     * 上移字典项
     * @param pid 字典项PID
     * @return 字典项响应
     */
    DictItemDTO moveUp(String pid);

    /**
     * 下移字典项
     * @param pid 字典项PID
     * @return 字典项响应
     */
    DictItemDTO moveDown(String pid);

    /**
     * 移动字典项到指定位置
     * @param pid 字典项PID
     * @param targetPosition 目标位置
     * @return 字典项响应
     */
    DictItemDTO moveTo(String pid, Integer targetPosition);

    // ==================== 状态管理 ====================

    /**
     * 启用字典项
     * @param pid 字典项PID
     * @return 字典项响应
     */
    DictItemDTO enable(String pid);

    /**
     * 禁用字典项
     * @param pid 字典项PID
     * @return 字典项响应
     */
    DictItemDTO disable(String pid);

    /**
     * 设置为默认值
     * @param pid 字典项PID
     * @return 字典项响应
     */
    DictItemDTO setAsDefault(String pid);

    /**
     * 取消默认值
     * @param pid 字典项PID
     * @return 字典项响应
     */
    DictItemDTO unsetDefault(String pid);

    // ==================== 批量操作 ====================

    /**
     * 批量创建字典项
     * @param requests 创建请求列表
     * @return 创建结果列表
     */
    List<DictItemDTO> batchCreate(List<DictItemCreateRequest> requests);

    /**
     * 批量更新字典项状态
     * @param pids 字典项PID列表
     * @param status 新状态
     * @return 更新数量
     */
    int batchUpdateStatus(List<String> pids, String status);

    /**
     * 批量删除字典项
     * @param pids 字典项PID列表
     * @return 删除数量
     */
    int batchDelete(List<String> pids);

    /**
     * 复制字典项到其他字典
     * @param sourcePids 源字典项PID列表
     * @param targetDictPid 目标字典PID
     * @return 复制结果
     */
    BatchOperationResult copyToDict(List<String> sourcePids, String targetDictPid);

    /**
     * 移动字典项到其他字典
     * @param sourcePids 源字典项PID列表
     * @param targetDictPid 目标字典PID
     * @return 移动结果
     */
    BatchOperationResult moveToDict(List<String> sourcePids, String targetDictPid);

    // ==================== 导入导出 ====================

    /**
     * 导入字典项
     * @param dictPid 字典PID
     * @param itemData 字典项数据
     * @return 导入结果
     */
    DictItemImportResult importItems(String dictPid, List<DictItemCreateRequest> itemData);

    /**
     * 导出字典项
     * @param dictPid 字典PID
     * @param format 导出格式
     * @return 字典项数据
     */
    List<DictItemDTO> exportItems(String dictPid, String format);

    // ==================== 验证和统计 ====================

    /**
     * 验证字典项值唯一性
     * @param dictPid 字典PID
     * @param value 字典项值
     * @param excludePid 排除的PID
     * @return 是否唯一
     */
    boolean isValueUnique(String dictPid, String value, String excludePid);

    /**
     * 验证字典项配置
     * @param pid 字典项PID
     * @return 验证结果
     */
    DictItemValidationResult validateConfig(String pid);

    /**
     * 统计字典项数量
     * @param dictPid 字典PID
     * @return 统计结果
     */
    DictItemStatistics getStatistics(String dictPid);

    /**
     * 获取字典项的使用情况
     * @param pid 字典项PID
     * @return 使用情况
     */
    DictItemUsageInfo getUsageInfo(String pid);
}