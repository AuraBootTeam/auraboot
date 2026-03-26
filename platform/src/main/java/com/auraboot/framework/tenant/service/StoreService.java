package com.auraboot.framework.tenant.service;

import com.auraboot.framework.tenant.dto.*;
import com.auraboot.framework.meta.dto.PaginationRequest;
import com.auraboot.framework.meta.dto.PaginationResult;

import java.util.List;

/**
 * 门店服务接口
 * 提供门店管理的核心业务功能
 */
public interface StoreService {

    /**
     * 分页查询门店列表
     *
     * @param queryRequest 查询条件
     * @param paginationRequest 分页参数
     * @return 分页结果
     */
    PaginationResult<StoreResponse> list(StoreQueryRequest queryRequest, PaginationRequest paginationRequest);

    /**
     * 根据PID查询门店详情
     *
     * @param pid 门店PID
     * @return 门店详情
     */
    StoreResponse findByPid(String pid);

    /**
     * 创建门店
     *
     * @param request 创建请求
     * @return 创建的门店信息
     */
    StoreResponse create(StoreCreateRequest request);

    /**
     * 更新门店信息
     *
     * @param pid 门店PID
     * @param request 更新请求
     * @return 更新后的门店信息
     */
    StoreResponse update(String pid, StoreUpdateRequest request);

    /**
     * 删除门店（软删除）
     *
     * @param pid 门店PID
     */
    void delete(String pid);

    /**
     * 批量删除门店
     *
     * @param pids 门店PID列表
     */
    void batchDelete(List<String> pids);

    /**
     * 根据租户ID查询门店列表
     *
     * @param tenantId 租户ID
     * @return 门店列表
     */
    List<StoreResponse> findByTenantId(Long tenantId);

    /**
     * 统计租户下的门店数量
     *
     * @param tenantId 租户ID
     * @return 门店数量
     */
    Long countByTenantId(Long tenantId);

    /**
     * 检查门店编码是否唯一
     *
     * @param code 门店编码
     * @param excludePid 排除的门店PID（用于更新时检查）
     * @return 是否唯一
     */
    boolean isCodeUnique(String code, String excludePid);

    /**
     * 根据编码查询门店
     *
     * @param code 门店编码
     * @return 门店信息
     */
    StoreResponse findByCode(String code);
}