package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.service.UserPermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RBAC evaluator — checks role-based permission grants.
 *
 * <p>ACTIVE in Phase 1. This is the primary authorization gate.
 *
 * <p>Evaluation logic:
 * <ol>
 *   <li>Build permission code from resource + action</li>
 *   <li>Check if user has the permission via {@link UserPermissionService#hasPermission(Long, String)}</li>
 *   <li>Return ALLOW or DENY</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RolePermissionEvaluator {

    private static final String NAME = "RolePermission";

    private final UserPermissionService userPermissionService;

    /**
     * Evaluate whether the member has the action permission via RBAC.
     *
     * @param memberId member (user) ID
     * @param resource resource identifier (e.g. model code)
     * @param action   action identifier (e.g. "view", "create")
     * @return evaluation step with ALLOW or DENY verdict
     */
    public EvaluationStep evaluate(Long memberId, String resource, String action) {
        String permissionCode = resource + ":" + action;

        boolean hasPermission = userPermissionService.hasPermission(memberId, permissionCode);

        if (hasPermission) {
            log.debug("RBAC ALLOW: memberId={}, permissionCode={}", memberId, permissionCode);
            return new EvaluationStep(NAME, EvaluationVerdict.ALLOW,
                    "User has permission: " + permissionCode);
        } else {
            log.debug("RBAC DENY: memberId={}, permissionCode={}", memberId, permissionCode);
            return new EvaluationStep(NAME, EvaluationVerdict.DENY,
                    "User lacks permission: " + permissionCode);
        }
    }
}
