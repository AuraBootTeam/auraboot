package com.auraboot.framework.tenant.service;

import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 租户服务接口
 */
public interface TenantService extends IService<Tenant> {

    /**
     * 创建租户
     */
    Tenant createTenant(Tenant tenant);

    /**
     * 更新租户信息
     */
    Tenant updateTenant(Tenant tenant);

    /**
     * 根据租户名称查询租户
     */
    Tenant findByName(String name);

    /**
     * 根据域名查询租户
     */
    Tenant getTenantByDomain(String domain);

    /**
     * 根据状态查询租户列表
     */
    List<Tenant> findByStatus(String status);

    /**
     * 查询所有租户
     */
    List<Tenant> getAllTenants();

    /**
     * 查询活跃租户
     */
    List<Tenant> getActiveTenants();

    /**
     * 分页查询租户列表
     */
    Page<Tenant> findTenants(int pageNum, int pageSize, String tenantName, String tenantCode, String status, String keyword);

    /**
     * 激活租户
     */
    boolean activateTenant(Long tenantId);

    /**
     * 停用租户
     */
    boolean deactivateTenant(Long tenantId);

    /**
     * 暂停租户
     */
    boolean suspendTenant(Long tenantId, String reason);

    /**
     * 恢复租户
     */
    boolean resumeTenant(Long tenantId);

    /**
     * 删除租户(逻辑删除)
     */
    boolean deleteTenant(Long tenantId);

    /**
     * 批量删除租户
     */
    int batchDeleteTenants(List<Long> tenantIds);

    /**
     * 检查租户名称是否可用
     */
    boolean isNameAvailable(String name);


    /**
     * 统计租户数量
     */
    long countByStatus(String status);


    /**
     * 根据业务ID查询租户
     * @param pid 业务ID
     * @return 租户信息
     */
    Tenant findByPid(String pid);
}