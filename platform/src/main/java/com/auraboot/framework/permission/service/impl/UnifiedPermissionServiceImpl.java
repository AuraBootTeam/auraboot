package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.engine.model.PermissionExplanation;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.permission.service.PermissionPolicyService;
import com.auraboot.framework.permission.service.UnifiedPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Default implementation of {@link UnifiedPermissionService}.
 *
 * <p>Simple facade that delegates to the appropriate specialized service:
 * <ul>
 *   <li>Action/DataScope/Record checks → {@link PermissionEvaluator}</li>
 *   <li>Policy parameters → {@link PermissionPolicyService}</li>
 *   <li>Field permissions → {@link FieldPermissionService}</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UnifiedPermissionServiceImpl implements UnifiedPermissionService {

    private final PermissionEvaluator evaluator;
    private final PermissionPolicyService policyService;
    private final FieldPermissionService fieldPermissionService;

    @Override
    public boolean canAction(Long memberId, String resource, String action) {
        return evaluator.canAction(memberId, resource, action);
    }

    @Override
    public DataScopeCondition getDataScopeCondition(Long memberId, String resource, String action) {
        return evaluator.getDataScopeCondition(memberId, resource, action);
    }

    @Override
    public PermissionResult canOperate(Long memberId, String resource, String action, Object record) {
        return evaluator.canOperate(memberId, resource, action, record);
    }

    @Override
    public Map<String, Object> getEffectivePolicy(Long memberId, String permissionCode) {
        return policyService.getEffectivePolicy(memberId, permissionCode);
    }

    @Override
    public FieldPermissionSet getFieldPermissions(Long memberId, String modelCode) {
        return fieldPermissionService.getFieldPermissions(memberId, modelCode);
    }

    @Override
    public PermissionExplanation explain(Long memberId, String resource, String action, Long recordId) {
        return evaluator.explain(memberId, resource, action, recordId);
    }
}
