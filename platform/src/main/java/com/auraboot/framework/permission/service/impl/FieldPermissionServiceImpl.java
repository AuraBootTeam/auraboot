package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.rbac.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

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
public class FieldPermissionServiceImpl implements FieldPermissionService {

    private final MetaModelService metaModelService;
    private final UserRoleService userRoleService;
    private final RoleService roleService;

    @Override
    public FieldPermissionSet getFieldPermissions(Long memberId, String modelCode) {
        // Get member's role codes
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> roleIds = userRoleService.getRoleIdsByMemberIdAndTenantId(memberId, tenantId);
        Set<String> memberRoleCodes = resolveRoleCodes(roleIds);

        // Get model field definitions
        List<FieldDefinition> fields = metaModelService.getModelFields(modelCode);
        if (fields == null || fields.isEmpty()) {
            return FieldPermissionSet.allAllowed(Set.of());
        }

        Set<String> allFieldCodes = fields.stream()
                .map(FieldDefinition::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));

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
     * Resolve role codes from role IDs.
     */
    private Set<String> resolveRoleCodes(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        return roleIds.stream()
                .map(id -> {
                    Role role = roleService.getById(id);
                    return role != null ? role.getCode() : null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
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
        Object fp = extraProps.get("fieldPermission");
        if (fp instanceof Map) {
            return (Map<String, Object>) fp;
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
    @SuppressWarnings("unchecked")
    private boolean checkRoleAccess(Map<String, Object> fieldPermission,
                                    String permType,
                                    Set<String> memberRoleCodes) {
        Object rolesObj = fieldPermission.get(permType);
        if (rolesObj == null) {
            // Permission type not defined — default to allowed
            return true;
        }
        if (rolesObj instanceof List<?> rolesList) {
            List<String> allowedRoles = (List<String>) rolesList;
            if (allowedRoles.isEmpty()) {
                // Empty list — default to allowed
                return true;
            }
            // UNION: if any of the member's roles is in the allowed list
            return memberRoleCodes.stream().anyMatch(allowedRoles::contains);
        }
        // Unexpected format — default to allowed
        return true;
    }
}
