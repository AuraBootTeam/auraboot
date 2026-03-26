package com.auraboot.framework.tenant.service;

import com.auraboot.framework.tenant.dto.TenantRequest;
import com.auraboot.framework.tenant.dto.TenantResponse;
import com.auraboot.framework.tenant.dto.TenantSelectionRequest;
import com.auraboot.framework.tenant.dto.TenantSelectionResponse;
import com.auraboot.framework.user.dao.entity.User;

public interface TenantApplicationService {
    
    /**
     * 获取当前用户的租户信息
     */
    TenantResponse getCurrentTenantInfo(Long userId);
    
    /**
     * 更新租户信息
     */
    TenantResponse updateTenant(String tenantPid, TenantRequest request, Long userId);
    
    /**
     * 根据PID获取租户信息
     */
    TenantResponse getTenantByPid(String tenantPid, Long userId);


    TenantSelectionResponse createTenantForUser(TenantSelectionRequest request, User user);

    TenantSelectionResponse joinTenantByInviteCode(TenantSelectionRequest request, User user);
}