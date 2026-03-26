package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.DataPermissionPolicyCreateRequest;
import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.auraboot.framework.meta.entity.DataPermissionRoleBinding;
import com.auraboot.framework.meta.mapper.DataPermissionPolicyMapper;
import com.auraboot.framework.meta.mapper.DataPermissionRoleBindingMapper;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.DataPermissionPolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Implementation of DataPermissionPolicyService.
 *
 * @since 5.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataPermissionPolicyServiceImpl implements DataPermissionPolicyService {

    private final DataPermissionPolicyMapper policyMapper;
    private final DataPermissionRoleBindingMapper bindingMapper;
    private final DataPermissionEngine dataPermissionEngine;

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "dataPermissionRowFilter", allEntries = true),
            @CacheEvict(value = "dataPermissionMaskRules", allEntries = true)
    })
    public DataPermissionPolicy create(DataPermissionPolicyCreateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String pid = UniqueIdGenerator.generate();

        DataPermissionPolicy entity = new DataPermissionPolicy();
        entity.setTenantId(tenantId);
        entity.setPid(pid);
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setModelCode(request.getModelCode());
        entity.setPolicyType(request.getPolicyType());
        entity.setScopeType(request.getScopeType());
        entity.setScopeExpression(request.getScopeExpression());
        entity.setFieldCode(request.getFieldCode());
        entity.setMaskType(request.getMaskType());
        entity.setMaskExpression(request.getMaskExpression());
        entity.setPriority(request.getPriority());
        entity.setEnabled(request.isEnabled());

        policyMapper.insert(entity);
        log.info("Created data permission policy: pid={}, name={}, model={}, type={}",
                pid, request.getName(), request.getModelCode(), request.getPolicyType());
        return entity;
    }

    @Override
    public DataPermissionPolicy getByPid(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return policyMapper.findByPid(tenantId, pid);
    }

    @Override
    public List<DataPermissionPolicy> listByModelCode(String modelCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return policyMapper.findByModelCode(tenantId, modelCode);
    }

    @Override
    public List<DataPermissionPolicy> listAll() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return policyMapper.findAllEnabled(tenantId);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "dataPermissionRowFilter", allEntries = true),
            @CacheEvict(value = "dataPermissionMaskRules", allEntries = true)
    })
    public DataPermissionPolicy update(String pid, DataPermissionPolicyCreateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        DataPermissionPolicy existing = policyMapper.findByPid(tenantId, pid);
        if (existing == null) {
            throw new IllegalArgumentException("Data permission policy not found: " + pid);
        }

        existing.setName(request.getName());
        existing.setDescription(request.getDescription());
        existing.setModelCode(request.getModelCode());
        existing.setPolicyType(request.getPolicyType());
        existing.setScopeType(request.getScopeType());
        existing.setScopeExpression(request.getScopeExpression());
        existing.setFieldCode(request.getFieldCode());
        existing.setMaskType(request.getMaskType());
        existing.setMaskExpression(request.getMaskExpression());
        existing.setPriority(request.getPriority());
        existing.setEnabled(request.isEnabled());

        policyMapper.updateById(existing);
        log.info("Updated data permission policy: pid={}", pid);
        return existing;
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "dataPermissionRowFilter", allEntries = true),
            @CacheEvict(value = "dataPermissionMaskRules", allEntries = true)
    })
    public void delete(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        bindingMapper.deleteByPolicyPid(tenantId, pid);
        policyMapper.deleteByPid(tenantId, pid);
        log.info("Deleted data permission policy and bindings: pid={}", pid);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "dataPermissionRowFilter", allEntries = true),
            @CacheEvict(value = "dataPermissionMaskRules", allEntries = true)
    })
    public void enable(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        policyMapper.updateEnabled(tenantId, pid, true);
        log.info("Enabled data permission policy: pid={}", pid);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "dataPermissionRowFilter", allEntries = true),
            @CacheEvict(value = "dataPermissionMaskRules", allEntries = true)
    })
    public void disable(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        policyMapper.updateEnabled(tenantId, pid, false);
        log.info("Disabled data permission policy: pid={}", pid);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "dataPermissionRowFilter", allEntries = true),
            @CacheEvict(value = "dataPermissionMaskRules", allEntries = true)
    })
    public void bindToRole(String policyPid, String rolePid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        bindingMapper.insertBinding(tenantId, policyPid, rolePid);
        log.info("Bound policy to role: policyPid={}, rolePid={}", policyPid, rolePid);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "dataPermissionRowFilter", allEntries = true),
            @CacheEvict(value = "dataPermissionMaskRules", allEntries = true)
    })
    public void unbindFromRole(String policyPid, String rolePid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        bindingMapper.deleteBinding(tenantId, policyPid, rolePid);
        log.info("Unbound policy from role: policyPid={}, rolePid={}", policyPid, rolePid);
    }

    @Override
    public List<DataPermissionRoleBinding> listRoleBindings(String policyPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return bindingMapper.findByPolicyPid(tenantId, policyPid);
    }

    @Override
    public List<DataPermissionPolicy> getEffectivePolicies(Long tenantId, String modelCode, Long userId) {
        return policyMapper.findEffectivePolicies(tenantId, modelCode, userId);
    }

    @Override
    public String previewRowFilter(String modelCode, Long userId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (userId == null) {
            userId = MetaContext.getCurrentUserId();
        }
        return dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId);
    }
}
