package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.mapper.TenantMapper;
import com.auraboot.framework.tenant.service.TenantService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 租户服务实现类
 */
@Slf4j
@Service
@Transactional
public class TenantServiceImpl extends ServiceImpl<TenantMapper, Tenant> implements TenantService {

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private RoleService roleService;

    @Override
    @Transactional
    public Tenant createTenant(Tenant tenant) {
        log.info("Creating tenant: {}", tenant.getName());

        // Quota enforcement is handled by QuotaEnforcementAspect (AOP)
        // when auraboot.quota.enforcement.enabled=true

        // 检查租户名称是否已存在
        if (!isNameAvailable(tenant.getName())) {
            throw new BusinessException("租户名称已存在: " + tenant.getName());
        }
        
        // 设置创建和更新时间
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        
        // 保存租户
        save(tenant);
        
        log.info("Tenant created successfully: {}", tenant.getId());
        return tenant;
    }

    @Override
    @Transactional
    public Tenant updateTenant(Tenant tenant) {
        log.info("Updating tenant: {}", tenant.getId());
        
        Tenant existingTenant = getById(tenant.getId());
        if (existingTenant == null) {
            throw new BusinessException("租户不存在: " + tenant.getId());
        }
        
        // 如果修改了名称，检查新名称是否可用
        if (!existingTenant.getName().equals(tenant.getName()) && !isNameAvailable(tenant.getName())) {
            throw new BusinessException("租户名称已存在: " + tenant.getName());
        }
        
        tenant.setUpdatedAt(Instant.now());
        updateById(tenant);
        
        log.info("Tenant updated successfully: {}", tenant.getId());
        return tenant;
    }

    @Override
    public Tenant findByName(String name) {
        return tenantMapper.findByName(name);
    }

    @Override
    public Tenant getTenantByDomain(String domain) {
        QueryWrapper<Tenant> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("domain", domain)
                   .eq("deleted_flag", false);
        return getOne(queryWrapper);
    }

    @Override
    public List<Tenant> findByStatus(String status) {
        return tenantMapper.findByStatus(status);
    }

    @Override
    public List<Tenant> getAllTenants() {
        QueryWrapper<Tenant> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("deleted_flag", false)
                   .orderByDesc("created_at");
        return list(queryWrapper);
    }

    @Override
    public List<Tenant> getActiveTenants() {
        QueryWrapper<Tenant> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("deleted_flag", false)
                   .eq("status", StatusConstants.ACTIVE)
                   .orderByDesc("created_at");
        return list(queryWrapper);
    }

    @Override
    public Page<Tenant> findTenants(int pageNum, int pageSize, String tenantName, String tenantCode, String status, String keyword) {
        Page<Tenant> page = new Page<>(pageNum, pageSize);
        QueryWrapper<Tenant> queryWrapper = new QueryWrapper<>();
        
        queryWrapper.eq("deleted_flag", false);
        
        if (StringUtils.hasText(tenantName)) {
            queryWrapper.like("name", tenantName);
        }
        
        if (StringUtils.hasText(tenantCode)) {
            queryWrapper.like("code", tenantCode);
        }
        
        if (StringUtils.hasText(status)) {
            queryWrapper.eq("status", status);
        }

//
//        if (StringUtils.hasText(keyword)) {
//            queryWrapper.and(wrapper -> wrapper
//                .like("name", keyword)
//                .or().like("display_name", keyword)
//                .or().like("contact_email", keyword));
//        }
//
        queryWrapper.orderByDesc("created_at");
        
        return page(page, queryWrapper);
    }

    @Override
    @Transactional
    public boolean activateTenant(Long tenantId) {
        log.info("Activating tenant: {}", tenantId);
        
        Tenant tenant = getById(tenantId);
        if (tenant == null) {
            throw new BusinessException("租户不存在: " + tenantId);
        }
        
        tenant.setStatus(StatusConstants.ACTIVE);
        tenant.setUpdatedAt(Instant.now());
        
        return updateById(tenant);
    }

    @Override
    @Transactional
    public boolean deactivateTenant(Long tenantId) {
        log.info("Deactivating tenant: {}", tenantId);
        
        Tenant tenant = getById(tenantId);
        if (tenant == null) {
            throw new BusinessException("租户不存在: " + tenantId);
        }
        
        tenant.setStatus(StatusConstants.INACTIVE);
        tenant.setUpdatedAt(Instant.now());
        
        return updateById(tenant);
    }

    @Override
    @Transactional
    public boolean suspendTenant(Long tenantId, String reason) {
        log.info("Suspending tenant: {}, reason: {}", tenantId, reason);

        Tenant tenant = getById(tenantId);
        if (tenant == null) {
            throw new BusinessException("租户不存在: " + tenantId);
        }

        tenant.setStatus(StatusConstants.SUSPENDED);
        tenant.setUpdatedAt(Instant.now());
        // 可以在settings中记录暂停原因

        return updateById(tenant);
    }

    @Override
    @Transactional
    public boolean resumeTenant(Long tenantId) {
        log.info("Resuming tenant: {}", tenantId);
        
        Tenant tenant = getById(tenantId);
        if (tenant == null) {
            throw new BusinessException("租户不存在: " + tenantId);
        }
        
        tenant.setStatus(StatusConstants.ACTIVE);
        tenant.setUpdatedAt(Instant.now());
        
        return updateById(tenant);
    }

    @Override
    @Transactional
    public boolean deleteTenant(Long tenantId) {
        log.info("Deleting tenant: {}", tenantId);
        
        Tenant tenant = getById(tenantId);
        if (tenant == null) {
            throw new BusinessException("租户不存在: " + tenantId);
        }
        
        return getBaseMapper().deleteById(tenant.getId()) > 0;
    }

    @Override
    public boolean isNameAvailable(String name) {
        return findByName(name) == null;
    }

    @Override
    @Transactional
    public int batchDeleteTenants(List<Long> tenantIds) {
        log.info("Batch deleting tenants: {}", tenantIds);
        
        if (tenantIds == null || tenantIds.isEmpty()) {
            return 0;
        }
        
        int count = 0;
        for (Long tenantId : tenantIds) {
            if (deleteTenant(tenantId)) {
                count++;
            }
        }
        
        return count;
    }






    @Override
    public long countByStatus(String status) {
        return tenantMapper.countByStatus(status);
    }

    @Override
    public Tenant findByPid(String pid) {
        QueryWrapper<Tenant> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(Tenant::getPid, pid)
                   .eq(Tenant::getDeletedFlag, false);
        return getOne(queryWrapper);
    }


}