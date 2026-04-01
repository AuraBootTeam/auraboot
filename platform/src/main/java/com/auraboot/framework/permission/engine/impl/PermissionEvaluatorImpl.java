package com.auraboot.framework.permission.engine.impl;

import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.engine.evaluator.*;
import com.auraboot.framework.permission.engine.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of the 5-step permission evaluation pipeline.
 *
 * <p>Pipeline order:
 * <ol>
 *   <li>RBAC (RolePermission) — DENY? return DENIED immediately</li>
 *   <li>ReBAC (RecordShare) — ALLOW? skip DataScope check</li>
 *   <li>DataScope — DENY? return DENIED</li>
 *   <li>Policy — DENY? return DENIED</li>
 *   <li>FieldPermission — record step only (does not affect grant/deny)</li>
 * </ol>
 *
 * <p>Deny by default: unimplemented layers return NOT_APPLICABLE (not ALLOW).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionEvaluatorImpl implements PermissionEvaluator {

    private final RolePermissionEvaluator rolePermissionEvaluator;
    private final RecordShareEvaluator recordShareEvaluator;
    private final DataScopeEvaluator dataScopeEvaluator;
    private final PolicyEvaluator policyEvaluator;
    private final FieldPermissionEvaluator fieldPermissionEvaluator;

    @Override
    public boolean canAction(Long memberId, String resource, String action) {
        EvaluationStep rbacStep = rolePermissionEvaluator.evaluate(memberId, resource, action);
        return rbacStep.verdict() == EvaluationVerdict.ALLOW;
    }

    @Override
    public DataScopeCondition getDataScopeCondition(Long memberId, String resource, String action) {
        // Step 1: RBAC check — if denied, no data visible
        EvaluationStep rbacStep = rolePermissionEvaluator.evaluate(memberId, resource, action);
        if (rbacStep.verdict() == EvaluationVerdict.DENY) {
            log.debug("getDataScopeCondition: RBAC denied for memberId={}, resource={}, action={}",
                    memberId, resource, action);
            return DataScopeCondition.none();
        }

        // Step 2: Get data scope condition
        return dataScopeEvaluator.getCondition(memberId, resource, action);
    }

    @Override
    public PermissionResult canOperate(Long memberId, String resource, String action, Object record) {
        List<EvaluationStep> steps = new ArrayList<>(5);

        // Step 1: RBAC
        EvaluationStep rbacStep = rolePermissionEvaluator.evaluate(memberId, resource, action);
        steps.add(rbacStep);
        if (rbacStep.verdict() == EvaluationVerdict.DENY) {
            return PermissionResult.deny(rbacStep.reason(), steps);
        }

        // Step 2: ReBAC (Record Share)
        EvaluationStep shareStep = recordShareEvaluator.evaluate(memberId, resource, action, record);
        steps.add(shareStep);
        boolean shareOverride = (shareStep.verdict() == EvaluationVerdict.ALLOW);

        // Step 3: DataScope (skipped if share explicitly allows)
        if (!shareOverride) {
            EvaluationStep dataScopeStep = dataScopeEvaluator.evaluate(memberId, resource, action, record);
            steps.add(dataScopeStep);
            if (dataScopeStep.verdict() == EvaluationVerdict.DENY) {
                return PermissionResult.deny(dataScopeStep.reason(), steps);
            }
        }

        // Step 4: Policy
        EvaluationStep policyStep = policyEvaluator.evaluate(memberId, resource, action, record);
        steps.add(policyStep);
        if (policyStep.verdict() == EvaluationVerdict.DENY) {
            return PermissionResult.deny(policyStep.reason(), steps);
        }

        // Step 5: FieldPermission (informational only, does not affect grant/deny)
        EvaluationStep fieldStep = fieldPermissionEvaluator.evaluate(memberId, resource, action, record);
        steps.add(fieldStep);

        return PermissionResult.allow(steps);
    }

    @Override
    public PermissionExplanation explain(Long memberId, String resource, String action, Long recordId) {
        // Run the full pipeline with null record — evaluators already handle null records gracefully.
        // Loading the actual record would require DynamicDataService which introduces circular dependency risk.
        PermissionResult result = canOperate(memberId, resource, action, null);
        return new PermissionExplanation(memberId, resource, action, recordId, result.granted(), result.steps());
    }
}
