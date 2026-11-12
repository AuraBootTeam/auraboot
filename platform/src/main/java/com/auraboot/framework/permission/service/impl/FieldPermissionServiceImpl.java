package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.DynamicDataQueryScope;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.service.MemberRoleContributor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Field Permission Service implementation.
 *
 * <p>Resolves field-level visibility and editability by inspecting
 * the DSL field metadata (extraProps.fieldPermission) and the member's roles.
 *
 * <p>Field permission format in extraProps:
 * <pre>
 * {
 *   "fieldPermission": {
 *     "view": ["admin", "sales_manager"],
 *     "edit": ["admin"]
 *   }
 * }
 * </pre>
 *
 * <p>If a field has no fieldPermission, it is viewable and editable by all roles.
 * Multi-role resolution uses UNION: if any of the member's roles is in the
 * view/edit list, the field is viewable/editable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("java/log-injection")
public class FieldPermissionServiceImpl implements FieldPermissionService {

    private final MetaModelService metaModelService;
    private final RoleMapper roleMapper;
    private final List<MemberRoleContributor> memberRoleContributors;

    @Override
    public FieldPermissionSet getFieldPermissions(Long memberId, String modelCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (!Objects.equals(memberId, MetaContext.getCurrentMemberId())) {
            return loadFieldPermissions(memberId, modelCode, tenantId);
        }
        return DynamicDataQueryScope.fieldPermissions(
                tenantId,
                modelCode,
                MetaContext.getCurrentUserId(),
                memberId,
                () -> loadFieldPermissions(memberId, modelCode, tenantId));
    }

    private FieldPermissionSet loadFieldPermissions(Long memberId, String modelCode, Long tenantId) {
        // Get model field definitions
        List<FieldDefinition> fields = metaModelService.getModelFields(modelCode);
        if (fields == null || fields.isEmpty()) {
            return FieldPermissionSet.allAllowed(Set.of());
        }

        // Roles cannot affect fields without a permission declaration. Avoid role IO entirely
        // for the common unrestricted model, while retaining the same evaluator for restricted fields.
        boolean hasFieldPermissions = fields.stream().map(this::extractFieldPermission)
                .anyMatch(permission -> permission != null && !permission.isEmpty());
        Set<String> memberRoleCodes = hasFieldPermissions
                ? resolveRoleCodes(memberId, tenantId)
                : Set.of();

        Set<String> viewableFields = new LinkedHashSet<>();
        Set<String> editableFields = new LinkedHashSet<>();
        Set<String> hiddenFields = new LinkedHashSet<>();

        for (FieldDefinition field : fields) {
            String fieldCode = field.getCode();
            Map<String, Object> fieldPermission = extractFieldPermission(field);

            if (fieldPermission == null || fieldPermission.isEmpty()) {
                // No field permission defined — fully accessible
                viewableFields.add(fieldCode);
                editableFields.add(fieldCode);
                continue;
            }

            boolean canView = checkRoleAccess(fieldPermission, "view", memberRoleCodes);
            boolean canEdit = checkRoleAccess(fieldPermission, "edit", memberRoleCodes);

            if (canView) {
                viewableFields.add(fieldCode);
                if (canEdit) {
                    editableFields.add(fieldCode);
                }
            } else {
                hiddenFields.add(fieldCode);
            }
        }

        // codeql[java/log-injection] Member/model identifiers are logged as structured diagnostic parameters only.
        log.debug("Field permissions for member {} on model {}: viewable={}, editable={}, hidden={}",
                memberId, modelCode, viewableFields.size(), editableFields.size(), hiddenFields.size());

        return new FieldPermissionSet(viewableFields, editableFields, hiddenFields);
    }

    /**
     * Read qualified role facts for the current actor without a cross-request authorization cache.
     */
    private Set<String> resolveRoleCodes(Long memberId, Long tenantId) {
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || tenantId <= 0 || userId == null || userId <= 0
                || memberId == null || memberId <= 0 || !Objects.equals(memberId, MetaContext.getCurrentMemberId())) {
            return Set.of();
        }
        Set<Long> contributed = new LinkedHashSet<>();
        for (MemberRoleContributor contributor : memberRoleContributors) {
            List<Long> ids = contributor.findRoleIds(memberId, tenantId);
            if (ids != null) {
                ids.stream().filter(id -> id != null && id > 0).forEach(contributed::add);
            }
        }
        return new LinkedHashSet<>(roleMapper.findEffectiveFieldRoleCodes(
                tenantId, userId, memberId, List.copyOf(contributed)));
    }

    /**
     * Extract the fieldPermission map from a field definition's extraProps.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractFieldPermission(FieldDefinition field) {
        Map<String, Object> extraProps = field.getExtraProps();
        if (extraProps == null) {
            return null;
        }
        Object fp = findFieldPermission(extraProps);
        if (fp == null) {
            return null;
        }
        if (!(fp instanceof Map<?, ?> permissions)) {
            throw invalidFieldPermission(field);
        }
        for (Map.Entry<?, ?> entry : permissions.entrySet()) {
            if ((!"view".equals(entry.getKey()) && !"edit".equals(entry.getKey()))
                    || !(entry.getValue() instanceof List<?> roles)
                    || roles.stream().anyMatch(role -> !(role instanceof String code) || code.isBlank())) {
                throw invalidFieldPermission(field);
            }
        }
        return (Map<String, Object>) permissions;
    }

    private MetaServiceException invalidFieldPermission(FieldDefinition field) {
        return new MetaServiceException("Invalid field permission declaration for field: " + field.getCode());
    }

    private Object findFieldPermission(Map<String, Object> extraProps) {
        Object direct = extraProps.get("fieldPermission");
        if (direct != null) {
            return direct;
        }
        Object nested = extraProps.get("extension");
        if (nested instanceof Map<?, ?> nestedMap) {
            Object nestedPermission = nestedMap.get("fieldPermission");
            if (nestedPermission != null) {
                return nestedPermission;
            }
        }
        Object dynamic = extraProps.get("dynamicProperties");
        if (dynamic instanceof Map<?, ?> dynamicMap) {
            Object dynamicDirect = dynamicMap.get("fieldPermission");
            if (dynamicDirect != null) {
                return dynamicDirect;
            }
            Object dynamicNested = dynamicMap.get("extension");
            if (dynamicNested instanceof Map<?, ?> dynamicNestedMap) {
                return dynamicNestedMap.get("fieldPermission");
            }
        }
        return null;
    }

    /**
     * Check if any of the member's role codes matches the allowed roles for a permission type.
     *
     * <p>If the permission type is not defined in fieldPermission, it defaults to allowed.
     *
     * @param fieldPermission the fieldPermission map
     * @param permType        "view" or "edit"
     * @param memberRoleCodes the member's role codes
     * @return true if access is allowed
     */
    private boolean checkRoleAccess(Map<String, Object> fieldPermission,
                                    String permType,
                                    Set<String> memberRoleCodes) {
        Object rolesObj = fieldPermission.get(permType);
        if (rolesObj == null) {
            // Permission type not defined — default to allowed
            return true;
        }
        if (rolesObj instanceof List<?> rolesList) {
            if (rolesList.isEmpty()) {
                // Empty list — default to allowed
                return true;
            }
            // UNION: if any of the member's roles is in the allowed list
            return memberRoleCodes.stream().anyMatch(rolesList::contains);
        }
        // Declarations are validated before role resolution; never grant on malformed input.
        throw new MetaServiceException("Invalid field permission action: " + permType);
    }
}
