package com.auraboot.framework.governance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.governance.dao.entity.MasterDataPolicy;
import com.auraboot.framework.governance.dao.mapper.MasterDataPolicyMapper;
import com.auraboot.framework.governance.dto.PolicyCreateDTO;
import com.auraboot.framework.governance.dto.PolicyResponse;
import com.auraboot.framework.governance.service.MasterDataPolicyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of master data governance policy service.
 * Manages per-model governance rules (approval, snapshot).
 */
@Slf4j
@Service
public class MasterDataPolicyServiceImpl implements MasterDataPolicyService {

    @Autowired
    private MasterDataPolicyMapper policyMapper;

    @Override
    @Transactional
    public PolicyResponse upsertPolicy(PolicyCreateDTO dto, Long tenantId) {
        if (dto.getModelCode() == null || dto.getModelCode().isBlank()) {
            throw new IllegalArgumentException("modelCode is required");
        }

        MasterDataPolicy existing = findByModelCode(dto.getModelCode(), tenantId);

        if (existing != null) {
            // Update existing policy
            existing.setRequireApproval(dto.getRequireApproval() != null ? dto.getRequireApproval() : false);
            existing.setAutoSnapshot(dto.getAutoSnapshot() != null ? dto.getAutoSnapshot() : false);
            existing.setApprovalChainId(dto.getApprovalChainId());
            existing.setAllowedEditors(dto.getAllowedEditors());
            existing.setUpdatedAt(new Date());
            policyMapper.updateById(existing);
            log.info("Updated governance policy for model={}", dto.getModelCode());
            return toResponse(existing);
        } else {
            // Create new policy
            MasterDataPolicy policy = new MasterDataPolicy();
            policy.setPid(UniqueIdGenerator.generate());
            policy.setTenantId(tenantId);
            policy.setModelCode(dto.getModelCode());
            policy.setRequireApproval(dto.getRequireApproval() != null ? dto.getRequireApproval() : false);
            policy.setAutoSnapshot(dto.getAutoSnapshot() != null ? dto.getAutoSnapshot() : false);
            policy.setApprovalChainId(dto.getApprovalChainId());
            policy.setAllowedEditors(dto.getAllowedEditors());
            policy.setCreatedAt(new Date());
            policy.setUpdatedAt(new Date());
            policyMapper.insert(policy);
            log.info("Created governance policy for model={}", dto.getModelCode());
            return toResponse(policy);
        }
    }

    @Override
    public List<PolicyResponse> listPolicies(Long tenantId) {
        QueryWrapper<MasterDataPolicy> qw = new QueryWrapper<>();
        qw.lambda().eq(MasterDataPolicy::getTenantId, tenantId)
                .orderByAsc(MasterDataPolicy::getModelCode);
        return policyMapper.selectList(qw).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public PolicyResponse getPolicy(String modelCode, Long tenantId) {
        MasterDataPolicy policy = findByModelCode(modelCode, tenantId);
        return policy != null ? toResponse(policy) : null;
    }

    @Override
    @Transactional
    public void deletePolicy(String pid, Long tenantId) {
        QueryWrapper<MasterDataPolicy> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(MasterDataPolicy::getPid, pid)
                .eq(MasterDataPolicy::getTenantId, tenantId);
        int deleted = policyMapper.delete(qw);
        if (deleted == 0) {
            throw new IllegalArgumentException("Policy not found: " + pid);
        }
        log.info("Deleted governance policy pid={}", pid);
    }

    @Override
    public boolean requiresApproval(String modelCode, Long tenantId) {
        MasterDataPolicy policy = findByModelCode(modelCode, tenantId);
        return policy != null && Boolean.TRUE.equals(policy.getRequireApproval());
    }

    @Override
    public boolean requiresAutoSnapshot(String modelCode, Long tenantId) {
        MasterDataPolicy policy = findByModelCode(modelCode, tenantId);
        return policy != null && Boolean.TRUE.equals(policy.getAutoSnapshot());
    }

    private MasterDataPolicy findByModelCode(String modelCode, Long tenantId) {
        QueryWrapper<MasterDataPolicy> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(MasterDataPolicy::getTenantId, tenantId)
                .eq(MasterDataPolicy::getModelCode, modelCode);
        return policyMapper.selectOne(qw);
    }

    private PolicyResponse toResponse(MasterDataPolicy entity) {
        PolicyResponse resp = new PolicyResponse();
        resp.setPid(entity.getPid());
        resp.setModelCode(entity.getModelCode());
        resp.setRequireApproval(entity.getRequireApproval());
        resp.setAutoSnapshot(entity.getAutoSnapshot());
        resp.setApprovalChainId(entity.getApprovalChainId());
        resp.setAllowedEditors(entity.getAllowedEditors());
        resp.setCreatedAt(entity.getCreatedAt());
        resp.setUpdatedAt(entity.getUpdatedAt());
        return resp;
    }
}
