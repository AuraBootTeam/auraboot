package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.application.tenant.MetaContext;
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
        Long userId = currentUserIdOr(memberId);
        for (String permissionCode : PermissionCodeCandidates.forResourceAction(resource, action)) {
            boolean hasPermission = userPermissionService.hasPermission(userId, permissionCode);
            if (hasPermission) {
                log.debug("RBAC ALLOW: memberId={}, userId={}, permissionCode={}", memberId, userId, permissionCode);
                return new EvaluationStep(NAME, EvaluationVerdict.ALLOW,
                        "User has permission: " + permissionCode);
            }
        }

        String attempted = String.join(", ", PermissionCodeCandidates.forResourceAction(resource, action));
        log.debug("RBAC DENY: memberId={}, userId={}, candidates={}", memberId, userId, attempted);
        return new EvaluationStep(NAME, EvaluationVerdict.DENY,
                "User lacks permission: " + attempted);
    }

    private Long currentUserIdOr(Long fallback) {
        try {
            if (MetaContext.exists() && MetaContext.getCurrentUserId() != null) {
                return MetaContext.getCurrentUserId();
            }
        } catch (Exception ignored) {
            // Unit tests and background paths may not have a full request context.
        }
        return fallback;
    }
}
