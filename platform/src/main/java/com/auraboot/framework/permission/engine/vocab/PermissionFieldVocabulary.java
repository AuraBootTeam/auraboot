package com.auraboot.framework.permission.engine.vocab;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Permission-domain vocabulary + {@link DecisionContext} builder for the condition-AST guard
 * layer (Permission Governance S1 Plan B).
 *
 * <p>A permission {@code condition_ast} addresses two scopes:
 * <ul>
 *   <li>{@link Scope#RECORD} — the operation target. Built via {@link DecisionContext.Builder#record(Map)},
 *       which wraps the data under {@code record.data.*} (matching the decision-runtime wire shape used
 *       across the platform, e.g. event policies).</li>
 *   <li>{@link Scope#ACTOR} — the current member. Exposes {@code actor.roles} (role codes).
 *       Department / approval-limit are intentionally NOT materialized in OSS: the tenant member
 *       model carries no such columns, so a condition referencing {@code actor.departmentId} or
 *       {@code actor.approvalLimit} resolves to a missing path → UNKNOWN → deny (default-deny, §7.1).
 *       Verticals that need those fields extend this vocabulary in the enterprise overlay.</li>
 * </ul>
 *
 * <p>This is a pure context assembler — it performs no evaluation and no authorization decision.
 */
@Component
@RequiredArgsConstructor
public class PermissionFieldVocabulary {

    private final UserRoleService userRoleService;
    private final RoleService roleService;

    /**
     * Build the {@link DecisionContext} for guarding {@code memberId}'s operation on {@code record}.
     *
     * @param memberId tenant member id (the actor)
     * @param record   the operation target as a flat field map; may be {@code null}
     * @return a context exposing {@code record.data.*} and {@code actor.roles}
     */
    @SuppressWarnings("unchecked")
    public DecisionContext buildContext(Long memberId, Object record) {
        Map<String, Object> recordData = (record instanceof Map<?, ?> m)
                ? (Map<String, Object>) m
                : Map.of();

        return DecisionContext.builder()
                .record(recordData)
                .scope(Scope.ACTOR, buildActorScope(memberId))
                .build();
    }

    /**
     * Assemble the actor scope. Currently exposes {@code roles} (role codes of the member).
     */
    private Map<String, Object> buildActorScope(Long memberId) {
        Map<String, Object> actor = new java.util.HashMap<>();
        actor.put("memberId", memberId);
        actor.put("roles", resolveRoleCodes(memberId));
        return actor;
    }

    private List<String> resolveRoleCodes(Long memberId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> roleIds = userRoleService.getRoleIdsByMemberIdAndTenantId(memberId, tenantId);
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        List<Role> roles = roleService.listByIds(roleIds);
        List<String> codes = new ArrayList<>(roles.size());
        for (Role role : roles) {
            if (role != null && role.getCode() != null) {
                codes.add(role.getCode());
            }
        }
        return codes;
    }
}
