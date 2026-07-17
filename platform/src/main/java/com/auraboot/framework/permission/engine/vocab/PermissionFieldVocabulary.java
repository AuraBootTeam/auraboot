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
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Permission-domain vocabulary + {@link DecisionContext} builder for the condition-AST guard
 * layer (Permission Governance S1 Plan B).
 *
 * <p>A permission {@code condition_ast} addresses these scopes:
 * <ul>
 *   <li>{@link Scope#RECORD} — the operation target. Built via {@link DecisionContext.Builder#record(Map)},
 *       which wraps the data under {@code record.data.*} (matching the decision-runtime wire shape used
 *       across the platform, e.g. event policies).</li>
 *   <li>{@link Scope#ACTOR} — the current member. Exposes {@code actor.roles} (role codes).
 *       Department / approval-limit are intentionally NOT materialized in OSS: the tenant member
 *       model carries no such columns, so a condition referencing {@code actor.departmentId} or
 *       {@code actor.approvalLimit} resolves to a missing path → UNKNOWN → deny (default-deny, §7.1).
 *       Verticals that need those fields extend this vocabulary in the enterprise overlay.</li>
 *   <li>{@link Scope#META} — optional selector metadata supplied by the low-code host, such as
 *       {@code meta.virtualSources}; this is intentionally kept outside {@code record.data}.</li>
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
    public DecisionContext buildContext(Long memberId, Object record) {
        DecisionContext.Builder builder = DecisionContext.builder();
        buildScopes(memberId, record).forEach(builder::scope);
        return builder.build();
    }

    /**
     * Build the raw scoped fact map used by Rule Center bindings.
     */
    public Map<Scope, Map<String, Object>> buildScopes(Long memberId, Object record) {
        Map<String, Object> recordData = extractRecordData(record);
        Map<String, Object> meta = extractMeta(record);

        Map<Scope, Map<String, Object>> scopes = new EnumMap<>(Scope.class);
        scopes.put(Scope.RECORD, Map.of("data", recordData));
        scopes.put(Scope.ACTOR, buildActorScope(memberId));
        if (!meta.isEmpty()) {
            scopes.put(Scope.META, meta);
        }
        return Map.copyOf(scopes);
    }

    private Map<String, Object> extractRecordData(Object record) {
        if (!(record instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> recordData = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            String field = String.valueOf(key);
            if (!isMetaField(field)) {
                recordData.put(field, value);
            }
        });
        return Collections.unmodifiableMap(recordData);
    }

    private Map<String, Object> extractMeta(Object record) {
        if (!(record instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        for (String field : List.of("meta", "_meta", "ruleMeta")) {
            if (raw.containsKey(field)) {
                return copyStringKeyMap(raw.get(field));
            }
        }
        return Map.of();
    }

    private Map<String, Object> copyStringKeyMap(Object value) {
        if (!(value instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) {
                copy.put(String.valueOf(key), item);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private boolean isMetaField(String field) {
        return "meta".equals(field) || "_meta".equals(field) || "ruleMeta".equals(field);
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
