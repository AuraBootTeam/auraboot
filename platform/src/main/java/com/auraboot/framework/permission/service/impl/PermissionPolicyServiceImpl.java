package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.PermissionPolicyService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Permission Policy Service Implementation
 *
 * <p>Manages parameterized permission policies stored in ab_role_permission.conditions JSONB.
 *
 * @author AuraBoot Platform
 * @since V4
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionPolicyServiceImpl implements PermissionPolicyService {

    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;
    private final UserRoleService userRoleService;
    private final ObjectMapper objectMapper;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public Map<String, Object> getEffectivePolicy(Long memberId, String permissionCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> roleIds = userRoleService.getRoleIdsByMemberIdAndTenantId(memberId, tenantId);

        if (roleIds.isEmpty()) {
            return null;
        }

        // Find the permission by code
        Permission permission = permissionMapper.findByCode(permissionCode);
        if (permission == null) {
            return null;
        }

        // Collect conditions from all role-permission bindings
        // Use direct SQL to read JSONB reliably (type handler on Object field is unreliable)
        List<Map<String, Object>> allPolicies = new ArrayList<>();
        for (Long roleId : roleIds) {
            Long rpId = findRolePermissionId(roleId, permission.getId());
            if (rpId != null) {
                String conditionsJson = rolePermissionMapper.getConditionsById(rpId);
                if (conditionsJson != null && !conditionsJson.isBlank()) {
                    Map<String, Object> parsed = convertToMap(conditionsJson);
                    if (parsed != null && !parsed.isEmpty()) {
                        allPolicies.add(parsed);
                    }
                }
            }
        }

        if (allPolicies.isEmpty()) {
            return null;
        }

        if (allPolicies.size() == 1) {
            return allPolicies.get(0);
        }

        return mergePolicies(allPolicies);
    }

    @Override
    public Map<String, Object> getPolicySchema(String permissionCode) {
        Permission permission = permissionMapper.findByCode(permissionCode);
        if (permission == null || permission.getPolicySchema() == null) {
            return null;
        }
        return convertToMap(permission.getPolicySchema());
    }

    @Override
    @Transactional
    public void setPolicy(Long roleId, Long permissionId, Map<String, Object> policyValues) {
        RolePermission rp = findRolePermission(roleId, permissionId);
        if (rp == null) {
            log.warn("No role-permission binding found: roleId={}, permissionId={}", roleId, permissionId);
            return;
        }

        // Use direct SQL update for JSONB column — MyBatis-Plus updateById
        // with JacksonTypeHandler on Object type doesn't reliably serialize JSONB.
        String jsonStr;
        try {
            jsonStr = objectMapper.writeValueAsString(policyValues);
        } catch (Exception e) {
            log.error("Failed to serialize policy values", e);
            return;
        }
        rolePermissionMapper.updateConditionsById(rp.getId(), jsonStr);

        log.info("Updated policy for role-permission: roleId={}, permissionId={}, keys={}",
                roleId, permissionId, policyValues.keySet());
    }

    @Override
    public Map<String, Object> getPolicy(Long roleId, Long permissionId) {
        String conditionsJson = rolePermissionMapper.getConditionsById(
                findRolePermissionId(roleId, permissionId));
        if (conditionsJson == null || conditionsJson.isBlank()) {
            return null;
        }
        return convertToMap(conditionsJson);
    }

    private Long findRolePermissionId(Long roleId, Long permissionId) {
        RolePermission rp = findRolePermission(roleId, permissionId);
        return rp != null ? rp.getId() : null;
    }

    // ========================================================================
    // Multi-role policy merge
    // ========================================================================

    /**
     * Merge policies from multiple roles using permissive rules:
     * - max* numeric fields: take MAX
     * - min* numeric fields: take MIN
     * - List fields: take UNION
     * - Boolean fields: OR
     * - Other fields: first non-null wins
     */
    private Map<String, Object> mergePolicies(List<Map<String, Object>> policies) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Set<String> allKeys = new LinkedHashSet<>();
        for (Map<String, Object> policy : policies) {
            allKeys.addAll(policy.keySet());
        }

        for (String key : allKeys) {
            List<Object> values = new ArrayList<>();
            for (Map<String, Object> policy : policies) {
                Object val = policy.get(key);
                if (val != null) {
                    values.add(val);
                }
            }

            if (values.isEmpty()) {
                continue;
            }

            merged.put(key, mergeValues(key, values));
        }

        return merged;
    }

    private Object mergeValues(String key, List<Object> values) {
        Object first = values.get(0);

        // Boolean fields: OR
        if (first instanceof Boolean) {
            return values.stream().anyMatch(v -> Boolean.TRUE.equals(v));
        }

        // Numeric fields: max*/min* prefix logic
        if (first instanceof Number) {
            String lowerKey = key.toLowerCase();
            if (lowerKey.startsWith("max")) {
                return values.stream()
                        .mapToDouble(v -> ((Number) v).doubleValue())
                        .max()
                        .orElse(((Number) first).doubleValue());
            }
            if (lowerKey.startsWith("min")) {
                return values.stream()
                        .mapToDouble(v -> ((Number) v).doubleValue())
                        .min()
                        .orElse(((Number) first).doubleValue());
            }
            // Default numeric: take MAX (most permissive)
            return values.stream()
                    .mapToDouble(v -> ((Number) v).doubleValue())
                    .max()
                    .orElse(((Number) first).doubleValue());
        }

        // List fields: UNION
        if (first instanceof List) {
            Set<Object> union = new LinkedHashSet<>();
            for (Object val : values) {
                if (val instanceof List<?> list) {
                    union.addAll(list);
                }
            }
            return new ArrayList<>(union);
        }

        // Default: first non-null
        return first;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Find role-permission using LambdaQueryWrapper (not @Select) to ensure
     * JacksonTypeHandler is applied to the 'conditions' JSONB column.
     */
    private RolePermission findRolePermission(Long roleId, Long permissionId) {
        return rolePermissionMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, roleId)
                .eq(RolePermission::getPermissionId, permissionId)
                .eq(RolePermission::getDeletedFlag, false)
                .last("LIMIT 1")
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertToMap(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        if (value instanceof String str) {
            if (str.isBlank()) {
                return null;
            }
            // CATCH: non-transactional, safe to handle — JSON parse from DB value
            try {
                return objectMapper.readValue(str, MAP_TYPE);
            } catch (Exception e) {
                log.warn("Failed to parse policy JSON: {}", str, e);
                return null;
            }
        }
        // CATCH: non-transactional, safe to handle — object conversion
        try {
            return objectMapper.convertValue(value, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to convert policy value: {}", value.getClass().getName(), e);
            return null;
        }
    }
}
