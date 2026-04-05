package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.enums.RolePermissionTemplate;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.validator.PermissionCodeValidator;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Auto Permission Assignment Service
 *
 * <p>Automatically creates hierarchical permissions when models are created/published.
 *
 * <p>Generates a 3-level permission hierarchy:
 * <ul>
 *   <li>Level 1 (Module): module.{moduleCode} — groups related models</li>
 *   <li>Level 2 (Resource): model.{modelCode} — represents the model itself</li>
 *   <li>Level 3 (Action): model.{modelCode}.{action} — fine-grained actions derived from commands</li>
 * </ul>
 *
 * <p>Actions are derived from model commands via {@link CommandActionDeriver},
 * not hardcoded. "read" is always included.
 *
 * @author AuraBoot Platform
 * @version 2.0.0
 * @since 2025-01-08
 * @see CommandActionDeriver
 * @see RolePermissionTemplate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoPermissionAssignmentService {

    private final PermissionService permissionService;
    private final PermissionMapper permissionMapper;
    private final RoleService roleService;
    private final RolePermissionMapper rolePermissionMapper;
    private final CommandActionDeriver commandActionDeriver;

    /**
     * Auto-assign hierarchical permissions for a model.
     *
     * <p>Creates a 3-level hierarchy: Module (level=1) → Resource (level=2) → Action (level=3).
     * Actions are derived from the model's command definitions.
     *
     * @param modelCode  the model code (e.g., "crm_lead")
     * @param moduleCode the module code (e.g., "crm"); if null, derived from modelCode prefix
     */
    @Transactional
    public void autoAssignPermissions(String modelCode, String moduleCode) {
        log.info("Auto-assigning hierarchical permissions: modelCode={}, moduleCode={}",
                modelCode, moduleCode);

        if (!isValidResourceCode(modelCode)) {
            log.warn("Invalid modelCode, skipping auto-assignment: modelCode={}", modelCode);
            return;
        }

        // Resolve moduleCode
        String resolvedModule = moduleCode != null ? moduleCode : deriveModuleFromModelCode(modelCode);

        // 1. Derive actions from model commands
        List<String> actions = commandActionDeriver.deriveActions(modelCode);
        log.info("Derived {} actions for model {}: {}", actions.size(), modelCode, actions);

        // 2. Ensure Module node (level=1)
        Permission modulePermission = ensureModulePermission(resolvedModule);

        // 3. Ensure Resource node (level=2)
        Permission resourcePermission = ensureResourcePermission(modelCode, modulePermission, resolvedModule);

        // 4. Ensure Action nodes (level=3) and collect for role assignment
        List<Permission> actionPermissions = new ArrayList<>();
        for (String action : actions) {
            Permission actionPerm = ensureActionPermission(modelCode, action, resourcePermission, resolvedModule);
            actionPermissions.add(actionPerm);
        }

        if (actionPermissions.isEmpty()) {
            log.warn("No action permissions created for model: {}", modelCode);
            return;
        }

        // 5. Assign level-3 action permissions to roles
        assignPermissionsToRoles(actionPermissions);

        log.info("Auto-assignment completed: modelCode={}, moduleCode={}, actionCount={}",
                modelCode, resolvedModule, actionPermissions.size());
    }

    /**
     * Derive module code from model code by extracting the prefix before the first underscore.
     * E.g., "crm_lead" → "crm", "pm_project" → "pm", "standalone" → "standalone"
     */
    String deriveModuleFromModelCode(String modelCode) {
        int idx = modelCode.indexOf('_');
        return idx > 0 ? modelCode.substring(0, idx) : modelCode;
    }

    /**
     * Ensure a Module permission node exists at level=1.
     * Code format: "module.{moduleCode}"
     */
    private Permission ensureModulePermission(String moduleCode) {
        String code = "module." + moduleCode;
        Permission existing = permissionMapper.findByCode(code);
        if (existing != null) {
            log.debug("Module permission already exists: code={}, id={}", code, existing.getId());
            return existing;
        }

        Permission permission = new Permission();
        permission.setPid(UniqueIdGenerator.generate());
        permission.setCode(code);
        permission.setName(capitalize(moduleCode) + " Module");
        permission.setDescription("Module: " + moduleCode);
        permission.setResourceType("module");
        permission.setResourceCode(moduleCode);
        permission.setAction(null);
        permission.setSource("generated");
        permission.setSourceRef(moduleCode);
        permission.setLevel(1);
        permission.setParentId(null);
        permission.setStatus(StatusConstants.ACTIVE);
        permission.setCreatedAt(Instant.now());
        permission.setUpdatedAt(Instant.now());

        permissionMapper.insert(permission);
        log.info("Created module permission: code={}, id={}", code, permission.getId());
        return permission;
    }

    /**
     * Ensure a Resource permission node exists at level=2.
     * Code format: "model.{modelCode}"
     */
    private Permission ensureResourcePermission(String modelCode, Permission modulePermission, String moduleCode) {
        String code = "model." + modelCode;
        Permission existing = permissionMapper.findByCode(code);
        if (existing != null) {
            log.debug("Resource permission already exists: code={}, id={}", code, existing.getId());
            return existing;
        }

        Permission permission = new Permission();
        permission.setPid(UniqueIdGenerator.generate());
        permission.setCode(code);
        permission.setName(capitalize(modelCode) + " Resource");
        permission.setDescription("Resource: " + modelCode);
        permission.setResourceType("model");
        permission.setResourceCode(modelCode);
        permission.setAction(null);
        permission.setSource("generated");
        permission.setSourceRef(moduleCode);
        permission.setLevel(2);
        permission.setParentId(modulePermission.getId());
        permission.setStatus(StatusConstants.ACTIVE);
        permission.setCreatedAt(Instant.now());
        permission.setUpdatedAt(Instant.now());

        permissionMapper.insert(permission);
        log.info("Created resource permission: code={}, id={}, parentId={}", code, permission.getId(), modulePermission.getId());
        return permission;
    }

    /**
     * Ensure an Action permission node exists at level=3.
     * Code format: "model.{modelCode}.{action}"
     */
    private Permission ensureActionPermission(String modelCode, String action,
                                               Permission resourcePermission, String moduleCode) {
        String code = PermissionCodeValidator.build("model", modelCode, action, null);
        Permission existing = permissionMapper.findByCode(code);
        if (existing != null) {
            log.debug("Action permission already exists: code={}, id={}", code, existing.getId());
            return existing;
        }

        Permission permission = new Permission();
        permission.setPid(UniqueIdGenerator.generate());
        permission.setCode(code);
        permission.setName(capitalize(modelCode) + " " + capitalize(action));
        permission.setDescription(capitalize(action) + " " + modelCode);
        permission.setResourceType("model");
        permission.setResourceCode(modelCode);
        permission.setAction(action);
        permission.setSource("generated");
        permission.setSourceRef(moduleCode);
        permission.setLevel(3);
        permission.setParentId(resourcePermission.getId());
        permission.setStatus(StatusConstants.ACTIVE);
        permission.setCreatedAt(Instant.now());
        permission.setUpdatedAt(Instant.now());

        permissionMapper.insert(permission);
        log.info("Created action permission: code={}, id={}, parentId={}", code, permission.getId(), resourcePermission.getId());
        return permission;
    }

    /**
     * Validate resource code.
     * Must start with a lowercase letter, contain only lowercase letters, digits, and underscores.
     */
    private boolean isValidResourceCode(String resourceCode) {
        if (resourceCode == null || resourceCode.isEmpty()) {
            log.warn("Resource code is null or empty");
            return false;
        }

        boolean isValid = resourceCode.matches("^[a-z][a-z0-9_]*$");

        if (!isValid) {
            log.warn("Resource code does not match pattern ^[a-z][a-z0-9_]*$: {}", resourceCode);
        }

        return isValid;
    }

    /**
     * Assign permissions to default roles based on templates.
     * VIEWER gets only .read actions, others get all.
     */
    private void assignPermissionsToRoles(List<Permission> permissions) {
        Long tenantId = MetaContext.getCurrentTenantId();

        if (tenantId == null) {
            log.warn("Tenant ID is null, skipping role assignment");
            return;
        }

        List<Role> roles = roleService.findByTenantId(tenantId);

        if (roles.isEmpty()) {
            log.warn("No roles found for tenant: tenantId={}", tenantId);
            return;
        }

        log.debug("Found {} roles for tenant: tenantId={}", roles.size(), tenantId);

        for (Role role : roles) {
            assignPermissionsToRole(role, permissions);
        }
    }

    private void assignPermissionsToRole(Role role, List<Permission> permissions) {
        String roleCode = role.getCode();

        if (roleCode == null) {
            log.warn("Role code is null, skipping: roleId={}, roleName={}",
                    role.getId(), role.getName());
            return;
        }

        RolePermissionTemplate template = RolePermissionTemplate.findByRoleCode(roleCode);

        if (template == null) {
            log.debug("No template found for role: roleCode={}, roleName={}",
                    roleCode, role.getName());
            return;
        }

        for (Permission permission : permissions) {
            if (shouldAssignPermission(template, permission)) {
                bindPermissionToRole(role.getId(), permission.getId());
            }
        }
    }

    /**
     * Delegate to template's filter-based shouldAssign method.
     * VIEWER gets read actions + non-leaf nodes; others get all.
     */
    private boolean shouldAssignPermission(RolePermissionTemplate template, Permission permission) {
        return template.shouldAssign(permission);
    }

    private void bindPermissionToRole(Long roleId, Long permissionId) {
        RolePermission existing = rolePermissionMapper.findByRoleAndPermission(roleId, permissionId);

        if (existing != null) {
            log.debug("Role-Permission binding already exists: roleId={}, permissionId={}",
                    roleId, permissionId);
            return;
        }

        RolePermission rolePermission = new RolePermission();
        rolePermission.setPid(UniqueIdGenerator.generate());
        rolePermission.setTenantId(MetaContext.getCurrentTenantId());
        rolePermission.setRoleId(roleId);
        rolePermission.setPermissionId(permissionId);
        rolePermission.setGrantType(StatusConstants.GRANT);
        rolePermission.setCreatedAt(Instant.now());
        rolePermission.setUpdatedAt(Instant.now());

        rolePermissionMapper.insert(rolePermission);

        log.debug("Created Role-Permission binding: roleId={}, permissionId={}",
                roleId, permissionId);
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
