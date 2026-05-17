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
import java.util.*;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * System Permission Initializer
 *
 * <p>Initializes system-level permissions for new tenants using a
 * Module → Resource → Action three-level hierarchy.
 *
 * <p>Hierarchy:
 * <ul>
 *   <li>Level 1: Module node (e.g., module.platform) — groups related resources</li>
 *   <li>Level 2: Resource node (e.g., system.model) — represents a system resource</li>
 *   <li>Level 3: Action node (e.g., system.model.read) — specific action on a resource</li>
 * </ul>
 *
 * <p>These permissions should be created during tenant initialization
 * and assigned to default roles (TENANT_ADMIN, DEVELOPER, VIEWER).
 *
 * @author AuraBoot Platform
 * @version 3.0.0
 * @since 2026-01-09
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemPermissionInitializer {

    private final PermissionMapper permissionMapper;

    // ========================================================================
    // Constants
    // ========================================================================

    private static final String SOURCE_SYSTEM = "system";
    private static final int LEVEL_MODULE = 1;
    private static final int LEVEL_RESOURCE = 2;
    private static final int LEVEL_ACTION = 3;

    // Standard action sets
    private static final String[] ACTIONS_CRUD = {"create", "read", "update", "delete"};
    private static final String[] ACTIONS_READ_ONLY = {"read"};
    private static final String[] ACTIONS_READ_ADMIN = {"read", "admin"};
    private static final String[] ACTIONS_CRUD_EXECUTE = {"create", "read", "update", "delete", "execute"};
    private static final String[] ACTIONS_CRUD_ADMIN = {"create", "read", "update", "delete", "admin"};
    private static final String[] ACTIONS_CRUD_EXECUTE_ADMIN = {"create", "read", "update", "delete", "execute", "admin"};

    /**
     * Resource types that require system-level permissions.
     * Kept for backward compatibility with PermissionCodeValidator.
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
        "module",
        // System resource type for hierarchy
        "system"
    };

    // ========================================================================
    // Resource definition record
    // ========================================================================

    /**
     * Defines a resource and its actions within a module.
     */
    private record ResourceDef(String resourceType, String resourceCode, String[] actions, String displayName) {}

    // ========================================================================
    // Module groups
    // ========================================================================

    /**
     * Module groups define the hierarchy:
     * module → list of resources with their actions.
     */
    private static final Map<String, List<ResourceDef>> MODULE_GROUPS = new LinkedHashMap<>();

    static {
        // Platform module — core DSL resources
        MODULE_GROUPS.put("platform", List.of(
            new ResourceDef("model", "model", ACTIONS_CRUD, "Model"),
            new ResourceDef("page", "page", ACTIONS_CRUD, "Page"),
            new ResourceDef("page", "designer", ACTIONS_CRUD_ADMIN, "Page Designer"),
            new ResourceDef("page", "publish", ACTIONS_CRUD_ADMIN, "Page Publish"),
            new ResourceDef("field", "field", ACTIONS_CRUD, "Field"),
            new ResourceDef("component", "component", ACTIONS_CRUD, "Component"),
            new ResourceDef("dict", "dict", ACTIONS_CRUD, "Dict"),
            new ResourceDef("query", "query", ACTIONS_CRUD, "Query"),
            new ResourceDef("command", "command", ACTIONS_CRUD_EXECUTE, "Command"),
            new ResourceDef("form", "form", ACTIONS_CRUD, "Form"),
            new ResourceDef("datasource", "datasource", ACTIONS_CRUD, "DataSource"),
            new ResourceDef("category", "category", ACTIONS_CRUD, "Category"),
            new ResourceDef("view", "saved_view", ACTIONS_CRUD, "Saved View"),
            new ResourceDef("state_graph", "state_graph", ACTIONS_CRUD, "State Graph"),
            new ResourceDef("decision", "decision", ACTIONS_CRUD_EXECUTE, "Decision"),
            new ResourceDef("invariant", "invariant", ACTIONS_CRUD, "Invariant"),
            new ResourceDef("template", "template", new String[]{"generate"}, "Template")
        ));

        // RBAC module — roles and permissions
        MODULE_GROUPS.put("rbac", List.of(
            new ResourceDef("rbac", "role", ACTIONS_CRUD, "Role"),
            new ResourceDef("rbac", "user_role", ACTIONS_CRUD, "User Role"),
            new ResourceDef("permission", "permission", ACTIONS_CRUD, "Permission")
        ));

        // Tenant module — tenant and menu management
        MODULE_GROUPS.put("tenant", List.of(
            new ResourceDef("tenant", "tenant", ACTIONS_CRUD_ADMIN, "Tenant"),
            new ResourceDef("menu", "menu", ACTIONS_CRUD, "Menu")
        ));

        // DevOps module — git and event sourcing
        MODULE_GROUPS.put("devops", List.of(
            new ResourceDef("git", "repo", ACTIONS_CRUD, "Git Repo"),
            new ResourceDef("git", "release", ACTIONS_CRUD, "Git Release"),
            new ResourceDef("event_store", "event_store", ACTIONS_READ_ADMIN, "Event Store")
        ));

        // Automation module — automation and workflow
        MODULE_GROUPS.put("automation", List.of(
            new ResourceDef("automation", "automation", ACTIONS_CRUD_ADMIN, "Automation"),
            new ResourceDef("workflow", "process", ACTIONS_CRUD_EXECUTE_ADMIN, "Workflow Process")
        ));

        // Analytics module — reports and dashboards
        MODULE_GROUPS.put("analytics", List.of(
            new ResourceDef("report", "template", ACTIONS_CRUD, "Report Template"),
            new ResourceDef("report", "report", new String[]{"generate"}, "Report"),
            new ResourceDef("dashboard", "dashboard", ACTIONS_CRUD, "Dashboard")
        ));
    }

    // ========================================================================
    // Internal system models (accessed via /api/dynamic/{code} but not registered
    // as user-facing MetaModels)
    // ========================================================================

    /**
     * Internal system model codes that are reachable through DynamicController
     * (e.g. {@code /api/dynamic/sys_user/list}) but are NOT registered as
     * user-facing {@code ab_meta_model} entries. Without explicit permissions,
     * the DynamicController's {@code @RequirePermission("model.{pageKey}.read")}
     * blocks every access — including the admin-facing MemberPicker/SmartSelect
     * that resolves user references.
     *
     * <p>Each entry here produces {@code model.{code}.{action}} permission codes
     * during tenant bootstrap. Privilege is intentionally narrow: only
     * TENANT_ADMIN and DEVELOPER receive these (see
     * {@link #INTERNAL_SYSTEM_MODEL_ROLE_WHITELIST}); VIEWER is excluded so we
     * do not accidentally leak user listings via the "viewer gets all read
     * actions" template rule.
     */
    private record InternalSystemModel(String code, String displayName, String[] actions) {}

    private static final List<InternalSystemModel> INTERNAL_SYSTEM_MODELS = List.of(
        // sys_user is referenced via refModelCode in plugin fields (applicant,
        // assignee, owner, ...). The SmartSelect/MemberPicker widget calls
        // GET /api/dynamic/sys_user/list to populate dropdowns. Only read is
        // needed — mutations still go through the dedicated user/member APIs.
        new InternalSystemModel("sys_user", "System User", ACTIONS_READ_ONLY)
    );

    /**
     * Role codes that receive internal system model permissions during bootstrap.
     * Deliberately excludes VIEWER to avoid leaking user directory data through
     * the {@link RolePermissionTemplate#VIEWER} read-all-leaves filter.
     */
    private static final Set<String> INTERNAL_SYSTEM_MODEL_ROLE_WHITELIST =
        Set.of("tenant_admin", "developer");

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Initialize system-level permissions for a tenant using module hierarchy.
     *
     * <p>Creates a three-level hierarchy:
     * <ol>
     *   <li>Module nodes (level=1): platform, rbac, tenant, devops, automation, analytics</li>
     *   <li>Resource nodes (level=2): model, page, field, ... with parentId → module</li>
     *   <li>Action nodes (level=3): create, read, update, delete, ... with parentId → resource</li>
     * </ol>
     *
     * @param tenantId Tenant ID
     * @return List of all created permissions (modules + resources + actions)
     */
    @Transactional
    public List<Permission> initializeSystemPermissions(Long tenantId) {
        log.info("Initializing system permissions with hierarchy: tenantId={}", tenantId);

        List<Permission> allPermissions = new ArrayList<>();
        int[] counts = {0, 0}; // [created, skipped]

        for (Map.Entry<String, List<ResourceDef>> entry : MODULE_GROUPS.entrySet()) {
            String moduleKey = entry.getKey();
            List<ResourceDef> resources = entry.getValue();

            // Level 1: Module node
            Permission moduleNode = createOrSkipPermission(
                tenantId, "module", moduleKey, null,
                capitalize(moduleKey) + " Module",
                "Module group: " + moduleKey,
                LEVEL_MODULE, null,
                allPermissions, counts
            );
            Long moduleId = moduleNode != null ? moduleNode.getId() : resolveExistingId("module", moduleKey, null);

            // Level 2 + 3: Resource and Action nodes
            for (ResourceDef rd : resources) {
                String resourceCode = buildResourceCode(rd.resourceType(), rd.resourceCode());

                Permission resourceNode = createOrSkipPermission(
                    tenantId, "system", resourceCode, null,
                    rd.displayName(),
                    "System resource: " + rd.displayName(),
                    LEVEL_RESOURCE, moduleId,
                    allPermissions, counts
                );
                Long resourceId = resourceNode != null ? resourceNode.getId() : resolveExistingId("system", resourceCode, null);

                // Level 3: Action nodes
                for (String action : rd.actions()) {
                    createOrSkipPermission(
                        tenantId, "system", resourceCode, action,
                        rd.displayName() + " " + capitalize(action),
                        getActionDescription(rd.displayName(), action),
                        LEVEL_ACTION, resourceId,
                        allPermissions, counts
                    );
                }
            }
        }

        // Internal system model permissions (model.sys_user.read, …) — parented
        // under the existing "platform" module so the tree remains navigable.
        Long platformModuleId = resolveExistingId("module", "platform", null);
        for (InternalSystemModel m : INTERNAL_SYSTEM_MODELS) {
            // Level 2: model.{code} resource node
            Permission resourceNode = createOrSkipPermission(
                tenantId, "model", m.code(), null,
                m.displayName(),
                "Internal system model: " + m.displayName(),
                LEVEL_RESOURCE, platformModuleId,
                allPermissions, counts
            );
            Long resourceId = resourceNode != null
                ? resourceNode.getId()
                : resolveExistingId("model", m.code(), null);

            // Level 3: action nodes
            for (String action : m.actions()) {
                createOrSkipPermission(
                    tenantId, "model", m.code(), action,
                    m.displayName() + " " + capitalize(action),
                    getActionDescription(m.displayName(), action),
                    LEVEL_ACTION, resourceId,
                    allPermissions, counts
                );
            }
        }

        log.info("System permission initialization complete: tenantId={}, created={}, skipped={}, total={}",
            tenantId, counts[0], counts[1], allPermissions.size());

        return allPermissions;
    }

    /**
     * Expose the set of internal system model permission codes (level=3 action
     * nodes) for use by the bootstrap role-assignment step, which must grant
     * them narrowly to admin/developer only (not viewer).
     *
     * @return set of codes like {@code model.sys_user.read}
     */
    public static Set<String> internalSystemModelActionCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (InternalSystemModel m : INTERNAL_SYSTEM_MODELS) {
            for (String action : m.actions()) {
                codes.add("model." + m.code() + "." + action);
            }
        }
        return codes;
    }

    /**
     * Role codes authorized to receive internal system model permissions.
     */
    public static Set<String> internalSystemModelRoleWhitelist() {
        return INTERNAL_SYSTEM_MODEL_ROLE_WHITELIST;
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    /**
     * Build a composite resource code for resources that have sub-resources.
     * For most resources, resourceType and resourceCode are the same (e.g., model/model).
     * For sub-resources, they differ (e.g., page/designer → page_designer).
     */
    private String buildResourceCode(String resourceType, String resourceCode) {
        if (resourceType.equals(resourceCode)) {
            return resourceCode;
        }
        return resourceType + "_" + resourceCode;
    }

    /**
     * Create a permission node or skip if already exists.
     * Returns the created permission, or null if skipped.
     */
    private Permission createOrSkipPermission(
            Long tenantId, String resourceType, String resourceCode, String action,
            String name, String description,
            int level, Long parentId,
            List<Permission> collector, int[] counts) {

        String code = buildCode(resourceType, resourceCode, action);

        Permission existing = permissionMapper.findByCode(code);
        if (existing != null) {
            log.debug("System permission exists, skipping: code={}, id={}", code, existing.getId());
            // Still add to collector so callers get a complete list
            collector.add(existing);
            counts[1]++;
            return null;
        }

        Permission permission = new Permission();
        permission.setPid(UniqueIdGenerator.generate());
        permission.setTenantId(tenantId);
        permission.setCode(code);
        permission.setName(name);
        permission.setDescription(description);
        permission.setResourceType(resourceType);
        permission.setResourceCode(resourceCode);
        permission.setAction(action);
        permission.setSource(SOURCE_SYSTEM);
        permission.setLevel(level);
        permission.setParentId(parentId);
        permission.setStatus(StatusConstants.ACTIVE);
        permission.setDeletedFlag(false);
        permission.setCreatedAt(Instant.now());
        permission.setUpdatedAt(Instant.now());

        permissionMapper.insert(permission);

        log.debug("Created system permission: code={}, level={}, parentId={}", code, level, parentId);

        collector.add(permission);
        counts[0]++;
        return permission;
    }

    /**
     * Build permission code. For module/resource nodes (no action), uses resourceType.resourceCode format.
     * For action nodes, uses resourceType.resourceCode.action format.
     */
    private String buildCode(String resourceType, String resourceCode, String action) {
        if (action == null) {
            return resourceType + "." + resourceCode;
        }
        return PermissionCodeValidator.build(resourceType, resourceCode, action, null);
    }

    /**
     * Resolve the ID of an existing permission by code (for parent references).
     */
    private Long resolveExistingId(String resourceType, String resourceCode, String action) {
        String code = buildCode(resourceType, resourceCode, action);
        Permission existing = permissionMapper.findByCode(code);
        return existing != null ? existing.getId() : null;
    }

    /**
     * Get human-readable action description.
     */
    private String getActionDescription(String displayName, String action) {
        return switch (action) {
            case "create" -> String.format("Create %s", displayName);
            case "read" -> String.format("View %s", displayName);
            case "update" -> String.format("Update %s", displayName);
            case "delete" -> String.format("Delete %s", displayName);
            case "execute" -> String.format("Execute %s operations", displayName);
            case "admin" -> String.format("Admin operations for %s", displayName);
            case "generate" -> String.format("Generate %s", displayName);
            default -> String.format("%s %s", action, displayName);
        };
    }

    /**
     * Capitalize first letter of string.
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /**
     * Get the module groups (for testing/inspection).
     */
    public static Map<String, List<ResourceDef>> getModuleGroups() {
        return Collections.unmodifiableMap(MODULE_GROUPS);
    }
}
