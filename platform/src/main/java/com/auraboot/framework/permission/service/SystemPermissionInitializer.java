package com.auraboot.framework.permission.service;

import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.validator.PermissionCodeValidator;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * System Permission Initializer
 * 
 * <p>Initializes system-level permissions for new tenants.
 * 
 * <p>System-level permissions are used to control access to system functions
 * (e.g., Model Management, Page Management), not specific resource instances.
 * 
 * <p>Format: {resource_type}.{resource_code}.{action}
 * <ul>
 *   <li>model.model.manage - Manage Model function (CRUD)</li>
 *   <li>model.model.read - View Model list</li>
 *   <li>page.page.manage - Manage Page function (CRUD)</li>
 *   <li>page.page.read - View Page list</li>
 * </ul>
 * 
 * <p>These permissions should be created during tenant initialization
 * and assigned to default roles (TENANT_ADMIN, DEVELOPER, VIEWER).
 * 
 * @author AuraBoot Platform
 * @version 2.2.2
 * @since 2026-01-09
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemPermissionInitializer {
    
    private final PermissionMapper permissionMapper;
    
    /**
     * Resource types that require system-level permissions
     *
     * Note: Must match the permission constants defined in MetaPermission.java
     */
    public static final String[] RESOURCE_TYPES = {
        // Meta resources
        "model", "page", "component", "dict", "field",
        "query", "command", "form", "menu", "template",
        // RBAC resources
        "permission", "rbac",
        // System resources
        "datasource", "category", "tenant",
        // Event sourcing resources
        "event_store", "state_graph", "decision", "invariant",
        // Git resources
        "git",
        // View resources (SavedView)
        "view",
        // Automation resources
        "automation",
        // Report resources
        "report",
        // Dashboard resources
        "dashboard",
        // Workflow resources (BPM)
        "workflow",
        // Dynamic data resources (auto-created on model publish)
        "dynamic",
        // Module grouping (auto-created for hierarchical permissions)
        "module"
    };
    
    /**
     * Initialize system-level permissions for a tenant
     *
     * <p>Creates manage and read permissions for each resource type.
     * Some resource types have special handling for additional actions or sub-resources.
     *
     * <p>Example permissions created:
     * <ul>
     *   <li>model.model.manage</li>
     *   <li>model.model.read</li>
     *   <li>page.page.manage, page.designer.manage, page.publish.manage</li>
     *   <li>rbac.role.manage, rbac.user_role.manage</li>
     *   <li>permission.permission.manage</li>
     *   <li>... (40+ permissions total)</li>
     * </ul>
     *
     * @param tenantId Tenant ID
     * @return List of created permissions
     */
    @Transactional
    public List<Permission> initializeSystemPermissions(Long tenantId) {
        log.info("开始初始化系统级Permission: tenantId={}", tenantId);

        List<Permission> permissions = new ArrayList<>();
        int[] counts = {0, 0}; // [successCount, skipCount]

        for (String resourceType : RESOURCE_TYPES) {
            switch (resourceType) {
                case "template":
                    createPermissionWithActions(tenantId, resourceType, "template",
                        new String[]{"generate"}, "Template", permissions, counts);
                    break;

                case "rbac":
                    // rbac has multiple sub-resources: role, user_role
                    createPermissionWithActions(tenantId, resourceType, "role",
                        new String[]{"manage", "read"}, "Role", permissions, counts);
                    createPermissionWithActions(tenantId, resourceType, "user_role",
                        new String[]{"manage", "read"}, "User Role", permissions, counts);
                    break;

                case "page":
                    // page has multiple sub-resources: page, designer, publish
                    createPermissionWithActions(tenantId, resourceType, "page",
                        new String[]{"manage", "read"}, "Page", permissions, counts);
                    createPermissionWithActions(tenantId, resourceType, "designer",
                        new String[]{"manage", "read", "admin"}, "Page Designer", permissions, counts);
                    createPermissionWithActions(tenantId, resourceType, "publish",
                        new String[]{"manage", "read", "admin"}, "Page Publish", permissions, counts);
                    break;

                case "git":
                    // git has multiple sub-resources: repo, release
                    createPermissionWithActions(tenantId, resourceType, "repo",
                        new String[]{"manage", "read"}, "Git Repo", permissions, counts);
                    createPermissionWithActions(tenantId, resourceType, "release",
                        new String[]{"manage", "read"}, "Git Release", permissions, counts);
                    break;

                case "command":
                    // command has additional execute action
                    createPermissionWithActions(tenantId, resourceType, "command",
                        new String[]{"manage", "read", "execute"}, "Command", permissions, counts);
                    break;

                case "decision":
                    // decision has additional execute action
                    createPermissionWithActions(tenantId, resourceType, "decision",
                        new String[]{"manage", "read", "execute"}, "Decision", permissions, counts);
                    break;

                case "event_store":
                    // event_store only has read and admin actions
                    createPermissionWithActions(tenantId, resourceType, "event_store",
                        new String[]{"read", "admin"}, "Event Store", permissions, counts);
                    break;

                case "view":
                    // view has saved_view sub-resource
                    createPermissionWithActions(tenantId, resourceType, "saved_view",
                        new String[]{"manage", "read"}, "Saved View", permissions, counts);
                    break;

                case "automation":
                    // automation has additional admin action
                    createPermissionWithActions(tenantId, resourceType, "automation",
                        new String[]{"manage", "read", "admin"}, "Automation", permissions, counts);
                    break;

                case "report":
                    // report has multiple sub-resources: template and report
                    createPermissionWithActions(tenantId, resourceType, "template",
                        new String[]{"manage", "read"}, "Report Template", permissions, counts);
                    createPermissionWithActions(tenantId, resourceType, "report",
                        new String[]{"generate"}, "Report", permissions, counts);
                    break;

                case "workflow":
                    // workflow has manage, read, execute, admin actions
                    createPermissionWithActions(tenantId, resourceType, "process",
                        new String[]{"manage", "read", "execute", "admin"}, "Workflow Process", permissions, counts);
                    break;

                default:
                    // Standard resources with manage and read actions
                    String resourceCode = resourceType.toLowerCase();
                    createPermissionWithActions(tenantId, resourceType, resourceCode,
                        new String[]{"manage", "read"}, capitalize(resourceCode), permissions, counts);
                    break;
            }
        }

        log.info("系统级Permission初始化完成: tenantId={}, 成功={}, 跳过={}, 总计={} ,详情={}",
            tenantId, counts[0], counts[1], permissions.size(),permissions);

        return permissions;
    }

    /**
     * Create permissions with specified actions for a resource
     */
    private void createPermissionWithActions(Long tenantId, String resourceType, String resourceCode,
                                            String[] actions, String displayName,
                                            List<Permission> permissions, int[] counts) {
        for (String action : actions) {

                String name = displayName + " " + capitalize(action);
                String description = getActionDescription(displayName, action);

                Permission permission = createSystemPermission(
                    tenantId, resourceType, resourceCode, action, name, description
                );

                if (permission != null) {
                    permissions.add(permission);
                    counts[0]++;
                    log.debug("创建系统级Permission成功: {}", permission.getCode());
                } else {
                    counts[1]++;
                }

        }
    }

    /**
     * Get action description
     */
    private String getActionDescription(String displayName, String action) {
        return switch (action) {
            case "manage" -> String.format("Manage %s (CRUD)", displayName);
            case "read" -> String.format("View %s list", displayName);
            case "execute" -> String.format("Execute %s operations", displayName);
            case "admin" -> String.format("Admin operations for %s", displayName);
            case "generate" -> String.format("Generate %s", displayName);
            default -> String.format("%s %s", action, displayName);
        };
    }
    
    /**
     * Create a single system-level permission
     * 
     * @param tenantId Tenant ID
     * @param resourceType Resource type (e.g., "model")
     * @param resourceCode Resource code (e.g., "model")
     * @param action Action (e.g., "manage", "read")
     * @param name Permission name
     * @param description Permission description
     * @return Created permission, or null if already exists
     */
    private Permission createSystemPermission(Long tenantId, String resourceType,
                                             String resourceCode, String action,
                                             String name, String description) {
        // Build permission code
        String code = PermissionCodeValidator.build(resourceType, resourceCode, action, null);
        
        // Check if permission already exists
        Permission existing = permissionMapper.findByCode(code);
        if (existing != null) {
            log.debug("系统级Permission已存在,跳过: code={}, id={}", code, existing.getId());
            return null;
        }
        
        // Create new permission
        Permission permission = new Permission();
        permission.setPid(UniqueIdGenerator.generate());
        permission.setTenantId(tenantId);
        permission.setCode(code);
        permission.setName(name);
        permission.setDescription(description);
        permission.setResourceType(resourceType);
        permission.setResourceCode(resourceCode);
        permission.setAction(action);
        permission.setSource("system");  // Mark as system-level
        permission.setStatus(StatusConstants.ACTIVE);
        permission.setCreatedAt(Instant.now());
        permission.setUpdatedAt(Instant.now());
        
        permissionMapper.insert(permission);
        
        log.debug("创建系统级Permission: code={}, id={}, pid={}",
            code, permission.getId(), permission.getPid());
        
        return permission;
    }
    
    /**
     * Capitalize first letter of string
     * 
     * @param str String to capitalize
     * @return Capitalized string
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
