package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.decision.rule.ConditionSpec;
import com.auraboot.framework.decision.rule.DecisionBinding;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.framework.decision.rule.RuleValueSource;
import com.auraboot.framework.decision.dto.DecisionFactCatalogDTO;
import com.auraboot.framework.decision.dto.DecisionFactDTO;
import com.auraboot.framework.decision.dto.DecisionFactEntityDTO;
import com.auraboot.framework.decision.service.DecisionUsageIndexService;
import com.auraboot.framework.decision.service.DecisionModelFieldService;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.PermissionPolicyService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<DecisionUsageIndexService> usageIndexServiceProvider;
    private final DecisionModelFieldService decisionModelFieldService;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public List<ConditionGuard> getConditionGuards(Long memberId, String permissionCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> roleIds = userRoleService.getRoleIdsByMemberIdAndTenantId(memberId, tenantId);
        if (roleIds.isEmpty()) {
            return Collections.emptyList();
        }

        Permission permission = permissionMapper.findByCode(permissionCode);
        if (permission == null) {
            return Collections.emptyList();
        }

        List<RolePermissionMapper.RolePermissionConditionAstRow> rows =
                rolePermissionMapper.findConditionAstGrants(roleIds, permission.getId());
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<ConditionGuard> guards = new ArrayList<>(rows.size());
        for (var row : rows) {
            guards.add(new ConditionGuard(
                    row.getId(),
                    row.getConditionAstJson(),
                    row.getConditionsJson(),
                    runtimeValidationError(permission, row.getConditionsJson())));
        }
        return guards;
    }

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
        Permission permission = permissionMapper.selectById(permissionId);
        validatePolicyValues(policyValues, permission);

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
        refreshPermissionPolicyUsageIndex(rp);

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

    @Override
    public Map<Long, Map<String, Object>> getPoliciesByRoleId(Long roleId) {
        List<RolePermissionMapper.RolePermissionConditionsRow> rows =
            rolePermissionMapper.findConditionsByRoleId(roleId);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Map<String, Object>> result = new HashMap<>();
        for (var row : rows) {
            String json = row.getConditionsJson();
            if (json != null && !json.isBlank()) {
                Map<String, Object> map = convertToMap(json);
                if (map != null) {
                    result.put(row.getPermissionId(), map);
                }
            }
        }
        return result;
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

    private void validatePolicyValues(Map<String, Object> policyValues, Permission permission) {
        if (policyValues == null || policyValues.isEmpty()) {
            return;
        }
        JsonNode root = objectMapper.valueToTree(policyValues);
        PolicyFieldBoundary fieldBoundary = allowedPolicyFieldBoundary(permission, root);
        validateRuleCenterAbacNode("$", root, fieldBoundary);
    }

    private String runtimeValidationError(Permission permission, String conditionsJson) {
        if (conditionsJson == null || conditionsJson.isBlank() || "null".equals(conditionsJson.trim())) {
            return null;
        }
        Map<String, Object> policyValues = convertToMap(conditionsJson);
        if (policyValues == null || policyValues.isEmpty()) {
            return null;
        }
        try {
            validatePolicyValues(policyValues, permission);
            return null;
        } catch (RootUnCheckedException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            return "Invalid permission ABAC policy: " + e.getMessage();
        }
    }

    private void validateRuleCenterAbacNode(String path, JsonNode node, PolicyFieldBoundary fieldBoundary) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            validateNestedRuleCenterObject(
                    path + ".ruleBinding", node.get("ruleBinding"), RuleConsumerBinding.class, fieldBoundary);
            validateNestedRuleCenterObject(
                    path + ".decisionBinding", node.get("decisionBinding"), DecisionBinding.class, fieldBoundary);
            validateNestedRuleCenterObject(
                    path + ".conditionSpec", node.get("conditionSpec"), ConditionSpec.class, fieldBoundary);
            if (node.hasNonNull("decisionCode")) {
                validateNestedRuleCenterObject(path, node, DecisionBinding.class, fieldBoundary);
            }
            node.fields().forEachRemaining(entry ->
                    validateRuleCenterAbacNode(path + "." + entry.getKey(), entry.getValue(), fieldBoundary));
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                validateRuleCenterAbacNode(path + "[" + i + "]", node.get(i), fieldBoundary);
            }
        }
    }

    private <T> void validateNestedRuleCenterObject(
            String path, JsonNode node, Class<T> type, PolicyFieldBoundary fieldBoundary) {
        if (node == null || node.isNull()) {
            return;
        }
        try {
            T parsed = objectMapper.copy()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
                    .convertValue(node, type);
            if (parsed instanceof DecisionBinding binding
                    && (binding.decisionCode() == null || binding.decisionCode().isBlank())) {
                throw new IllegalArgumentException("decisionCode is required");
            }
            if (parsed instanceof DecisionBinding binding) {
                validateDecisionBindingFieldRefs(path, binding, fieldBoundary);
            }
            if (parsed instanceof RuleConsumerBinding binding
                    && binding.decisionBinding() != null
                    && (binding.decisionBinding().decisionCode() == null
                    || binding.decisionBinding().decisionCode().isBlank())) {
                throw new IllegalArgumentException("ruleBinding.decisionBinding.decisionCode is required");
            }
            if (parsed instanceof RuleConsumerBinding binding && binding.decisionBinding() != null) {
                validateDecisionBindingFieldRefs(path + ".decisionBinding", binding.decisionBinding(), fieldBoundary);
            }
        } catch (IllegalArgumentException e) {
            throw new RootUnCheckedException(ResponseCode.BadParam,
                    "Invalid permission ABAC policy at " + path + ": " + e.getMessage(), e);
        }
    }

    private PolicyFieldBoundary allowedPolicyFieldBoundary(Permission permission, JsonNode root) {
        Set<String> allowedRefs = new LinkedHashSet<>();
        Set<String> blockedMaskedRefs = new LinkedHashSet<>();
        String modelCode = policyFieldCatalogModelCode(permission);
        boolean fieldBoundaryDeclared = false;
        if (modelCode != null && !modelCode.isBlank()) {
            fieldBoundaryDeclared = true;
            DecisionFactCatalogDTO catalog = decisionModelFieldService.getFactCatalog(modelCode);
            if (catalog != null && catalog.getEntities() != null) {
                for (DecisionFactEntityDTO entity : catalog.getEntities()) {
                    if (entity == null || entity.getFacts() == null) {
                        continue;
                    }
                    for (DecisionFactDTO fact : entity.getFacts()) {
                        if (fact == null
                                || Boolean.FALSE.equals(fact.getVisible())
                                || fact.getScope() == null
                                || fact.getPath() == null
                                || fact.getPath().isBlank()) {
                            continue;
                        }
                        String ref = fact.getScope() + "." + fact.getPath();
                        if (Boolean.TRUE.equals(fact.getMasked())) {
                            blockedMaskedRefs.add(ref);
                            continue;
                        }
                        allowedRefs.add(ref);
                    }
                }
            }
        }
        fieldBoundaryDeclared |= addPolicySchemaFields(allowedRefs, blockedMaskedRefs, permission);
        fieldBoundaryDeclared |= addInlineSchemaFields(allowedRefs, blockedMaskedRefs, root);
        return fieldBoundaryDeclared ? new PolicyFieldBoundary(allowedRefs, blockedMaskedRefs) : null;
    }

    private String policyFieldCatalogModelCode(Permission permission) {
        if (permission == null) {
            return null;
        }
        Map<String, Object> schema = convertToMap(permission.getPolicySchema());
        Object dynamicAbac = schema == null ? null : schema.get("dynamicAbac");
        if (dynamicAbac instanceof Map<?, ?> map) {
            Object modelCode = map.get("fieldCatalogModelCode");
            return modelCode instanceof String str ? str : null;
        }
        return null;
    }

    private boolean addPolicySchemaFields(
            Set<String> allowedRefs,
            Set<String> blockedMaskedRefs,
            Permission permission) {
        if (permission == null) {
            return false;
        }
        Map<String, Object> schema = convertToMap(permission.getPolicySchema());
        Object dynamicAbac = schema == null ? null : schema.get("dynamicAbac");
        if (dynamicAbac instanceof Map<?, ?> map) {
            return addFieldRefsFromList(allowedRefs, blockedMaskedRefs, map.get("fields"));
        }
        return false;
    }

    private boolean addInlineSchemaFields(
            Set<String> allowedRefs,
            Set<String> blockedMaskedRefs,
            JsonNode root) {
        JsonNode dynamicAbac = root == null ? null : root.get("dynamicAbac");
        if (dynamicAbac != null && dynamicAbac.isObject()) {
            return addFieldRefsFromJson(allowedRefs, blockedMaskedRefs, dynamicAbac.get("fields"));
        }
        return false;
    }

    private boolean addFieldRefsFromList(
            Set<String> allowedRefs,
            Set<String> blockedMaskedRefs,
            Object rawFields) {
        if (!(rawFields instanceof List<?> fields)) {
            return false;
        }
        for (Object field : fields) {
            if (field instanceof Map<?, ?> map) {
                Object scope = map.get("scope");
                Object path = map.get("path");
                if (scope instanceof String scopeText
                        && path instanceof String pathText
                        && !scopeText.isBlank()
                        && !pathText.isBlank()) {
                    if (Boolean.FALSE.equals(map.get("visible"))) {
                        continue;
                    }
                    String ref = scopeText + "." + pathText;
                    if (Boolean.TRUE.equals(map.get("masked"))) {
                        blockedMaskedRefs.add(ref);
                        continue;
                    }
                    allowedRefs.add(ref);
                }
            }
        }
        return true;
    }

    private boolean addFieldRefsFromJson(
            Set<String> allowedRefs,
            Set<String> blockedMaskedRefs,
            JsonNode fieldsNode) {
        if (fieldsNode == null || !fieldsNode.isArray()) {
            return false;
        }
        for (JsonNode field : fieldsNode) {
            JsonNode scope = field.get("scope");
            JsonNode path = field.get("path");
            if (scope != null && scope.isTextual() && path != null && path.isTextual()) {
                JsonNode visible = field.get("visible");
                if (visible != null && visible.isBoolean() && !visible.booleanValue()) {
                    continue;
                }
                String ref = scope.asText() + "." + path.asText();
                JsonNode masked = field.get("masked");
                if (masked != null && masked.isBoolean() && masked.booleanValue()) {
                    blockedMaskedRefs.add(ref);
                    continue;
                }
                allowedRefs.add(ref);
            }
        }
        return true;
    }

    private void validateDecisionBindingFieldRefs(
            String path, DecisionBinding binding, PolicyFieldBoundary fieldBoundary) {
        if (fieldBoundary == null || binding == null) {
            return;
        }
        int index = 0;
        for (DecisionBinding.InputMapping mapping : binding.inputMappings()) {
            RuleValueSource source = mapping == null ? null : mapping.source();
            validateRuleValueSource(path + ".inputMappings[" + index + "].source", source, fieldBoundary);
            index++;
        }
        validateRuleValueSource(path + ".routingKeySource", binding.routingKeySource(), fieldBoundary);
        validateRuleValueSource(path + ".tenantSegmentSource", binding.tenantSegmentSource(), fieldBoundary);
    }

    private void validateRuleValueSource(String path, RuleValueSource source, PolicyFieldBoundary fieldBoundary) {
        if (source == null || source.kind() != RuleValueSource.Kind.FIELD) {
            return;
        }
        String fieldRef = source.fieldRef();
        if (fieldRef == null || fieldRef.isBlank()) {
            throw new IllegalArgumentException("field source is missing scope/path");
        }
        if (fieldBoundary.blockedMaskedRefs().contains(fieldRef)) {
            throw new IllegalArgumentException(fieldRef
                    + " is masked and cannot be used in permission ABAC policy at " + path);
        }
        if (!fieldBoundary.allowedRefs().contains(fieldRef)) {
            throw new IllegalArgumentException(fieldRef
                    + " is not available in permission ABAC fact catalog at " + path);
        }
    }

    private void refreshPermissionPolicyUsageIndex(RolePermission rp) {
        if (rp.getPid() == null || rp.getPid().isBlank()) {
            return;
        }
        DecisionUsageIndexService usageIndexService = usageIndexServiceProvider.getIfAvailable();
        if (usageIndexService == null) {
            return;
        }
        usageIndexService.refreshSource("PERMISSION_POLICY", rp.getPid());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertToMap(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            String wrappedJson = unwrapPgJsonWrapper(map);
            if (wrappedJson != null) {
                value = wrappedJson;
            } else {
                return (Map<String, Object>) value;
            }
        }
        if (value instanceof PGobject pgObject) {
            value = pgObject.getValue();
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

    private String unwrapPgJsonWrapper(Map<?, ?> map) {
        Object type = map.get("type");
        Object rawValue = map.get("value");
        if (!(rawValue instanceof String str) || str.isBlank()) {
            return null;
        }
        if (type instanceof String typeStr
                && ("jsonb".equalsIgnoreCase(typeStr) || "json".equalsIgnoreCase(typeStr))) {
            return str;
        }
        return null;
    }

    private record PolicyFieldBoundary(Set<String> allowedRefs, Set<String> blockedMaskedRefs) {
    }
}
