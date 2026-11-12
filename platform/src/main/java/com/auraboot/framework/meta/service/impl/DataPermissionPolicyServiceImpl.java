package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.DataPermissionPolicyCreateRequest;
import com.auraboot.framework.meta.constant.DataPermissionPolicyVocabulary;
import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.auraboot.framework.meta.entity.DataPermissionRoleBinding;
import com.auraboot.framework.meta.mapper.DataPermissionPolicyMapper;
import com.auraboot.framework.meta.mapper.DataPermissionRoleBindingMapper;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.DataPermissionPolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import com.auraboot.framework.permission.service.DataPermissionAuthoringAccess;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import org.springframework.transaction.annotation.Isolation;
import java.util.concurrent.atomic.AtomicReference;
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
    private final com.auraboot.framework.permission.service.RoleAuthorizationWriteLock authorizationWriteLock;


    private final DataPermissionAuthoringAccess authoringAccess;
    private final DataPermissionPolicyMapper policyMapper;
    private final DataPermissionRoleBindingMapper bindingMapper;
    private final DataPermissionEngine dataPermissionEngine;

    @Override
    @Transactional
    @CacheEvict(value = "dataPermissionMaskRules", allEntries = true)
    public DataPermissionPolicy create(DataPermissionPolicyCreateRequest request) {
        authorizationWriteLock.mutate();
        authoringAccess.requireWrite();
        Long tenantId = MetaContext.getCurrentTenantId();
        validateRequest(request);
        String pid = UniqueIdGenerator.generate();

        DataPermissionPolicy entity = new DataPermissionPolicy();
        entity.setTenantId(tenantId);
        entity.setPid(pid);
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setModelCode(request.getModelCode());
        entity.setPolicyType(DataPermissionPolicyVocabulary.type(request.getPolicyType()));
        entity.setScopeType(request.getScopeType()==null?null:DataPermissionPolicyVocabulary.token(request.getScopeType()));
        entity.setScopeExpression(request.getScopeExpression());
        entity.setConditionAst(request.getConditionAst());
        entity.setFieldCode(request.getFieldCode());
        entity.setMaskType(request.getMaskType()==null?null:DataPermissionPolicyVocabulary.token(request.getMaskType()));
        entity.setMaskExpression(request.getMaskExpression());
        entity.setPriority(request.getPriority());
        entity.setEnabled(request.isEnabled());

        policyMapper.insert(entity);
        log.info("Created data permission policy: pid={}, name={}, model={}, type={}",
                pid, request.getName(), request.getModelCode(), request.getPolicyType());
        return entity;
    }

    @Override
    @Transactional(readOnly = true)
    public DataPermissionPolicy getByPid(String pid) {
        authoringAccess.requireRead();
        Long tenantId = MetaContext.getCurrentTenantId();
        return policyMapper.findByPid(tenantId, pid);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataPermissionPolicy> listByModelCode(String modelCode) {
        authoringAccess.requireRead();
        Long tenantId = MetaContext.getCurrentTenantId();
        return policyMapper.findByModelCode(tenantId, modelCode).stream().filter(p -> p.getManagedRolePid()==null).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataPermissionPolicy> listAll() {
        authoringAccess.requireRead();
        Long tenantId = MetaContext.getCurrentTenantId();
        return policyMapper.findAllEnabled(tenantId).stream().filter(p -> p.getManagedRolePid()==null).toList();
    }

    @Override
    @Transactional
    @CacheEvict(value = "dataPermissionMaskRules", allEntries = true)
    public DataPermissionPolicy update(String pid, DataPermissionPolicyCreateRequest request) {
        authorizationWriteLock.mutate();
        authoringAccess.requireWrite();
        Long tenantId = MetaContext.getCurrentTenantId();
        DataPermissionPolicy existing = policyMapper.findByPid(tenantId, pid);
        if (existing == null) {
            throw missing(pid);
        }
        requireUnmanaged(existing);

        validateRequest(request);
        existing.setName(request.getName());
        existing.setDescription(request.getDescription());
        existing.setModelCode(request.getModelCode());
        existing.setPolicyType(DataPermissionPolicyVocabulary.type(request.getPolicyType()));
        existing.setScopeType(request.getScopeType()==null?null:DataPermissionPolicyVocabulary.token(request.getScopeType()));
        existing.setScopeExpression(request.getScopeExpression());
        existing.setConditionAst(request.getConditionAst());
        existing.setFieldCode(request.getFieldCode());
        existing.setMaskType(request.getMaskType()==null?null:DataPermissionPolicyVocabulary.token(request.getMaskType()));
        existing.setMaskExpression(request.getMaskExpression());
        existing.setPriority(request.getPriority());
        existing.setEnabled(request.isEnabled());

        policyMapper.updateById(existing);
        log.info("Updated data permission policy: pid={}", pid);
        return existing;
    }

    @Override
    @Transactional
    @CacheEvict(value = "dataPermissionMaskRules", allEntries = true)
    public void delete(String pid) {
        authorizationWriteLock.mutate();
        authoringAccess.requireWrite();
        Long tenantId = MetaContext.getCurrentTenantId();
        requireUnmanaged(requirePolicy(tenantId,pid));
        bindingMapper.deleteByPolicyPid(tenantId, pid);
        policyMapper.deleteByPid(tenantId, pid);
        log.info("Deleted data permission policy and bindings: pid={}", pid);
    }

    @Override
    @Transactional
    @CacheEvict(value = "dataPermissionMaskRules", allEntries = true)
    public void enable(String pid) {
        authorizationWriteLock.mutate();
        authoringAccess.requireWrite();
        Long tenantId = MetaContext.getCurrentTenantId();
        requireUnmanaged(requirePolicy(tenantId,pid));
        policyMapper.updateEnabled(tenantId, pid, true);
        log.info("Enabled data permission policy: pid={}", pid);
    }

    @Override
    @Transactional
    @CacheEvict(value = "dataPermissionMaskRules", allEntries = true)
    public void disable(String pid) {
        authorizationWriteLock.mutate();
        authoringAccess.requireWrite();
        Long tenantId = MetaContext.getCurrentTenantId();
        requireUnmanaged(requirePolicy(tenantId,pid));
        policyMapper.updateEnabled(tenantId, pid, false);
        log.info("Disabled data permission policy: pid={}", pid);
    }

    @Override
    @Transactional
    @CacheEvict(value = "dataPermissionMaskRules", allEntries = true)
    public void bindToRole(String policyPid, String rolePid) {
        authorizationWriteLock.mutate();
        authoringAccess.requireWrite();
        Long tenantId = MetaContext.getCurrentTenantId();
        requireUnmanaged(requirePolicy(tenantId,policyPid));
        authoringAccess.requireRole(rolePid);
        bindingMapper.insertBinding(tenantId, policyPid, rolePid);
        log.info("Bound policy to role: policyPid={}, rolePid={}", policyPid, rolePid);
    }

    @Override
    @Transactional
    @CacheEvict(value = "dataPermissionMaskRules", allEntries = true)
    public void unbindFromRole(String policyPid, String rolePid) {
        authorizationWriteLock.mutate();
        authoringAccess.requireWrite();
        Long tenantId = MetaContext.getCurrentTenantId();
        requireUnmanaged(requirePolicy(tenantId,policyPid));
        authoringAccess.requireRole(rolePid);
        bindingMapper.deleteBinding(tenantId, policyPid, rolePid);
        log.info("Unbound policy from role: policyPid={}, rolePid={}", policyPid, rolePid);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataPermissionRoleBinding> listRoleBindings(String policyPid) {
        authoringAccess.requireRead();
        Long tenantId = MetaContext.getCurrentTenantId();
        requirePolicy(tenantId,policyPid);
        var bindings=bindingMapper.findByPolicyPid(tenantId,policyPid);
        for(var binding:bindings) authoringAccess.requireRole(binding.getRolePid());
        return bindings;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<DataPermissionPolicy> getEffectivePolicies(Long tenantId, String modelCode, Long userId) {
        authoringAccess.requireRead();authoringAccess.requireTenant(tenantId);
        var subject=authoringAccess.previewSubject(userId==null?MetaContext.getCurrentUserId():userId);
        return policyMapper.findEffectivePolicies(tenantId,modelCode,subject.memberId());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(String modelCode, Long userId) {
        authoringAccess.requireRead();
        Long target=userId==null?MetaContext.getCurrentUserId():userId;
        var subject=authoringAccess.previewSubject(target);
        var policies=policyMapper.findEffectivePolicies(subject.tenantId(),modelCode,subject.memberId());
        var filter=new AtomicReference<String>();
        // Strip command permits and administrative identity while computing the target's filter.
        // runWithSnapshot restores all original thread locals even when the evaluator throws.
        MetaContext.runWithSnapshot(subject,()->filter.set(dataPermissionEngine.buildRowFilter(subject.tenantId(),modelCode,target)));
        return new Preview(target,filter.get(),List.copyOf(policies));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String previewRowFilter(String modelCode, Long userId) {
        return preview(modelCode,userId).rowFilter();
    }

    private static void validateRequest(DataPermissionPolicyCreateRequest request) {
        if(request==null || request.getName()==null || request.getName().isBlank()
                || request.getModelCode()==null || request.getModelCode().isBlank() || request.getPriority()==null)
            throw new BusinessException(ResponseCode.BadParam,"Invalid data permission policy");
        String type=DataPermissionPolicyVocabulary.type(request.getPolicyType());
        if("row".equals(type)) {
            String scope=DataPermissionPolicyVocabulary.scope(request.getScopeType());
            if("custom".equals(scope) && request.getConditionAst()==null
                    && (request.getScopeExpression()==null || request.getScopeExpression().isBlank()))
                throw new BusinessException(ResponseCode.BadParam,"Custom row policy requires a condition");
        } else {
            if(request.getFieldCode()==null || request.getFieldCode().isBlank())
                throw new BusinessException(ResponseCode.BadParam,"Field policy requires a target field");
            if("column".equals(type)) DataPermissionPolicyVocabulary.mask(request.getMaskType());
        }
    }

    private DataPermissionPolicy requirePolicy(Long tenant,String pid) {
        var policy=policyMapper.findByPid(tenant,pid);
        if(policy==null) throw missing(pid);
        return policy;
    }

    private static void requireUnmanaged(DataPermissionPolicy policy) {
        if(policy.getManagedRolePid()!=null)throw new org.springframework.security.access.AccessDeniedException("Role-managed field restrictions must be changed through the owning role draft");
    }

    private static BusinessException missing(String pid) {
        return new BusinessException(ResponseCode.NOT_FOUND,"Data permission policy not found: "+pid);
    }
}
