package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.auraboot.framework.plugin.service.BuiltinPluginImportService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.saas.config.mapper.SystemConfigMapper;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import com.auraboot.framework.saas.constant.SystemMode;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.auraboot.framework.saas.executor.SystemTenantContextExecutor.SYSTEM_TENANT_ID;

/**
 * Idempotent repair-step service for the bootstrap invariants.
 *
 * <p>Phase 2.2 of the bootstrap-unified plan
 * ({@code docs/plans/2026-05/bootstrap-unified.md}). Each public {@code repairXxx}
 * method enforces one invariant and is safe to call any number of times — it
 * checks for the invariant's source-of-truth row and returns
 * {@link RepairStepResult.Status#PRESENT} (no write) or
 * {@link RepairStepResult.Status#CREATED} (created) accordingly.
 *
 * <p><b>Behavior contract</b>: this service owns idempotent repair steps that
 * can be invoked one-by-one by {@code BootstrapEngineService} or by explicit
 * repair/admin flows. The first-install {@code /api/bootstrap/setup} path uses
 * only the minimal system bootstrap steps; plugin import is a separate script
 * responsibility.
 *
 * <p>What is <b>not</b> here (kept in {@code BootstrapEngineService}):
 * <ul>
 *   <li>{@code BootstrapEntity} progress tracking</li>
 *   <li>{@code TenantBootstrapService.bootstrapTenant} (creates tenant-scoped
 *       roles/permissions/menus for the business tenant — heavy, owned by
 *       {@code TenantBootstrapService})</li>
 *   <li>"already initialized" guard + final {@code system.initialized=true} write</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BootstrapRepairService {

    public static final String STEP_SYSTEM_CONFIG       = "system_config";
    public static final String STEP_SYSTEM_TENANT       = "system_tenant";
    public static final String STEP_PLATFORM_ADMIN_ROLE = "platform_admin_role";
    public static final String STEP_ADMIN_USER          = "admin_user";
    public static final String STEP_ADMIN_MEMBERSHIP    = "admin_membership";
    public static final String STEP_ADMIN_ROLE_GRANT    = "admin_role_grant";
    public static final String STEP_BUSINESS_TENANT     = "business_tenant";
    public static final String STEP_BUSINESS_TENANT_BOOTSTRAP = "business_tenant_bootstrap";
    public static final String STEP_BUILTIN_PLUGINS     = "builtin_plugins";
    public static final String STEP_JWT_SECRET          = "jwt_secret";

    /** Invariant 1: {@code system_config} must hold mode / platform_name / db_uuid / instance_url. */
    public static final List<String> ORDERED_STEPS = List.of(
            STEP_SYSTEM_CONFIG,
            STEP_SYSTEM_TENANT,
            STEP_PLATFORM_ADMIN_ROLE,
            STEP_ADMIN_USER,
            STEP_ADMIN_MEMBERSHIP,
            STEP_ADMIN_ROLE_GRANT,
            STEP_BUSINESS_TENANT,
            STEP_BUSINESS_TENANT_BOOTSTRAP,
            STEP_BUILTIN_PLUGINS,
            STEP_JWT_SECRET);

    private final SystemConfigService systemConfigService;
    private final SystemConfigMapper systemConfigMapper;
    private final UserService userService;
    private final TenantService tenantService;
    private final TenantMemberService tenantMemberService;
    private final BuiltinPluginImportService builtinPluginImportService;
    private final RoleService roleService;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final MenuMapper menuMapper;
    private final TenantBootstrapService tenantBootstrapService;

    // ────────────────────────────────────────────────────────────────────
    // Public step API — one method per invariant
    // ────────────────────────────────────────────────────────────────────

    /** Invariant 1: {@code system_config} rows present (mode / platform_name / db_uuid / instance_url). */
    public RepairStepResult repairSystemConfig(RepairOptions opts) {
        try {
            String mode = StringUtils.hasText(opts.systemMode())
                    ? opts.systemMode() : SystemMode.SINGLE.getCode();

            boolean wasMissing =
                    systemConfigService.get(SystemConfigKeys.SYSTEM_MODE).isEmpty()
                    || systemConfigService.get(SystemConfigKeys.SYSTEM_PLATFORM_NAME).isEmpty()
                    || systemConfigService.get(SystemConfigKeys.SYSTEM_DB_UUID).isEmpty()
                    || systemConfigService.get(SystemConfigKeys.SYSTEM_INSTANCE_URL).isEmpty();

            // initialize() is upsert-style; safe to call repeatedly.
            systemConfigService.initialize(SystemConfigKeys.SYSTEM_MODE, mode,
                    "system", "string", "System mode (single/multi/hybrid)", true);
            systemConfigService.initialize(SystemConfigKeys.SYSTEM_PLATFORM_NAME, opts.companyName(),
                    "system", "string", "Platform display name", false);
            systemConfigService.initialize(SystemConfigKeys.SYSTEM_ALLOW_SELF_REGISTRATION, "false",
                    "system", "boolean", "Allow self-registration", false);

            // db_uuid is immutable: only generate if missing.
            String existingDbUuid = systemConfigService.get(SystemConfigKeys.SYSTEM_DB_UUID).orElse(null);
            if (existingDbUuid == null || existingDbUuid.isBlank()) {
                String dbUuid = systemConfigMapper.generateDbUuid();
                systemConfigService.initialize(SystemConfigKeys.SYSTEM_DB_UUID, dbUuid,
                        "system", "string", "Database instance unique identifier (immutable)", true);
            }

            String instanceUrl = StringUtils.hasText(opts.instanceUrl())
                    ? opts.instanceUrl() : "http://localhost:6443";
            systemConfigService.initialize(SystemConfigKeys.SYSTEM_INSTANCE_URL, instanceUrl,
                    "system", "string", "Instance base URL for fingerprint binding", false);

            return wasMissing
                    ? RepairStepResult.created(STEP_SYSTEM_CONFIG, "system_config rows initialized (mode=" + mode + ")")
                    : RepairStepResult.present(STEP_SYSTEM_CONFIG, "system_config rows already present");
        } catch (Exception e) {
            log.error("repairSystemConfig failed", e);
            return RepairStepResult.error(STEP_SYSTEM_CONFIG, e.getMessage());
        }
    }

    /** Invariant 2: System Tenant exists (id={@value SystemTenantContextExecutor#SYSTEM_TENANT_ID}, name="System"). */
    public RepairStepResult repairSystemTenant(RepairOptions opts) {
        try {
            Tenant existing = tenantService.findByName("System");
            if (existing != null) {
                return RepairStepResult.present(STEP_SYSTEM_TENANT,
                        "System Tenant already exists (id=" + existing.getId() + ")");
            }
            Tenant systemTenant = new Tenant();
            systemTenant.setId(SYSTEM_TENANT_ID);
            systemTenant.setPid(UniqueIdGenerator.generate());
            systemTenant.setName("System");
            systemTenant.setDisplayName("System");
            systemTenant.setStatus("active");
            Tenant created = tenantService.createTenant(systemTenant);
            return RepairStepResult.created(STEP_SYSTEM_TENANT,
                    "System Tenant created (id=" + created.getId() + ")");
        } catch (Exception e) {
            log.error("repairSystemTenant failed", e);
            return RepairStepResult.error(STEP_SYSTEM_TENANT, e.getMessage());
        }
    }

    /**
     * Invariant 3: {@code platform_admin} role exists in System Tenant. Also seeds the
     * System Tenant's Platform Console menus on first creation (these menus belong with
     * the {@code platform_admin} bootstrap; not factored into a separate invariant since
     * they are display-only and re-import-safe).
     */
    public RepairStepResult repairPlatformAdminRole(RepairOptions opts) {
        try {
            Tenant systemTenant = tenantService.findByName("System");
            if (systemTenant == null) {
                return RepairStepResult.error(STEP_PLATFORM_ADMIN_ROLE,
                        "System Tenant missing — repairSystemTenant must run first");
            }
            Long systemTenantId = systemTenant.getId();
            if (roleMapper.existsByCode(systemTenantId, "platform_admin")) {
                return RepairStepResult.present(STEP_PLATFORM_ADMIN_ROLE,
                        "platform_admin role already present in System Tenant");
            }

            // No admin user yet at this point on a fresh DB? createdBy is required by
            // the Role audit columns. We accept the admin id as-of-now if present (look up by
            // email), otherwise fall back to id=null which createRole defaults to 0.
            User admin = userService.findByEmail(opts.adminEmail());
            Long createdBy = admin != null ? admin.getId() : 0L;

            Role platformAdmin = new Role();
            platformAdmin.setTenantId(systemTenantId);
            platformAdmin.setCode("platform_admin");
            platformAdmin.setName("Platform Admin");
            platformAdmin.setDescription("Platform administrator — manages tenants, licenses, marketplace, and system configuration");
            platformAdmin.setType("system");
            platformAdmin.setScopeType("global");
            platformAdmin.setPriority(0);
            platformAdmin.setIsDefault(true);
            platformAdmin.setIsSystem(true);
            platformAdmin.setStatus("active");
            platformAdmin.setCreatedBy(createdBy);
            platformAdmin.setUpdatedBy(createdBy);

            // RoleService.createRole runs inside MetaContext; require System Tenant context.
            boolean clearMeta = !MetaContext.exists();
            if (clearMeta) {
                MetaContext.setContext(systemTenantId, createdBy, "system", "system");
            }
            try {
                roleService.createRole(platformAdmin);
                createPlatformMenus(systemTenantId, createdBy);
            } finally {
                if (clearMeta) {
                    MetaContext.clear();
                }
            }
            return RepairStepResult.created(STEP_PLATFORM_ADMIN_ROLE,
                    "platform_admin role + Platform Console menus created");
        } catch (Exception e) {
            log.error("repairPlatformAdminRole failed", e);
            return RepairStepResult.error(STEP_PLATFORM_ADMIN_ROLE, e.getMessage());
        }
    }

    /** Invariant 4: admin user exists ({@code opts.adminEmail()}). */
    public RepairStepResult repairAdminUser(RepairOptions opts) {
        try {
            User existing = userService.findByEmail(opts.adminEmail());
            if (existing != null) {
                return RepairStepResult.present(STEP_ADMIN_USER,
                        "admin user already exists (id=" + existing.getId() + ")");
            }
            User created = userService.signUp(opts.adminEmail(), opts.adminPassword(),
                    opts.adminDisplayName());
            return RepairStepResult.created(STEP_ADMIN_USER,
                    "admin user created (id=" + created.getId() + ", email=" + opts.adminEmail() + ")");
        } catch (Exception e) {
            log.error("repairAdminUser failed", e);
            return RepairStepResult.error(STEP_ADMIN_USER, e.getMessage());
        }
    }

    /** Invariant 5: admin → System Tenant + Business Tenant memberships. */
    public RepairStepResult repairAdminMembership(RepairOptions opts) {
        try {
            User admin = userService.findByEmail(opts.adminEmail());
            if (admin == null) {
                return RepairStepResult.error(STEP_ADMIN_MEMBERSHIP,
                        "admin user missing — repairAdminUser must run first");
            }
            Tenant systemTenant = tenantService.findByName("System");
            if (systemTenant == null) {
                return RepairStepResult.error(STEP_ADMIN_MEMBERSHIP,
                        "System Tenant missing — repairSystemTenant must run first");
            }
            Tenant businessTenant = tenantService.findByName(opts.companyName());

            int created = 0;
            int present = 0;

            TenantMember sys = tenantMemberService.findByTenantIdAndUserId(systemTenant.getId(), admin.getId());
            if (sys == null) {
                tenantMemberService.addMember(admin.getId(), systemTenant.getId(), "active");
                created++;
            } else {
                present++;
            }

            if (businessTenant != null) {
                TenantMember biz = tenantMemberService.findByTenantIdAndUserId(businessTenant.getId(), admin.getId());
                if (biz == null) {
                    tenantMemberService.addMember(admin.getId(), businessTenant.getId(), "active");
                    created++;
                } else {
                    present++;
                }
            }

            if (created == 0) {
                return RepairStepResult.present(STEP_ADMIN_MEMBERSHIP,
                        "admin memberships already present (" + present + " tenant(s))");
            }
            return RepairStepResult.created(STEP_ADMIN_MEMBERSHIP,
                    "admin membership(s) created (created=" + created + ", present=" + present + ")");
        } catch (Exception e) {
            log.error("repairAdminMembership failed", e);
            return RepairStepResult.error(STEP_ADMIN_MEMBERSHIP, e.getMessage());
        }
    }

    /** Invariant 6: admin → {@code platform_admin} role grant. */
    public RepairStepResult repairAdminRoleGrant(RepairOptions opts) {
        try {
            User admin = userService.findByEmail(opts.adminEmail());
            if (admin == null) {
                return RepairStepResult.error(STEP_ADMIN_ROLE_GRANT,
                        "admin user missing — repairAdminUser must run first");
            }
            Tenant systemTenant = tenantService.findByName("System");
            if (systemTenant == null) {
                return RepairStepResult.error(STEP_ADMIN_ROLE_GRANT,
                        "System Tenant missing — repairSystemTenant must run first");
            }
            Long roleId = roleMapper.findIdByCode(systemTenant.getId(), "platform_admin");
            if (roleId == null) {
                return RepairStepResult.error(STEP_ADMIN_ROLE_GRANT,
                        "platform_admin role missing — repairPlatformAdminRole must run first");
            }
            TenantMember member = tenantMemberService.findByTenantIdAndUserId(
                    systemTenant.getId(), admin.getId());
            if (member == null) {
                return RepairStepResult.error(STEP_ADMIN_ROLE_GRANT,
                        "admin System Tenant membership missing — repairAdminMembership must run first");
            }
            UserRole existing = userRoleMapper.findByMemberIdAndRoleIdAndTenantId(
                    member.getId(), roleId, systemTenant.getId());
            if (existing != null) {
                return RepairStepResult.present(STEP_ADMIN_ROLE_GRANT,
                        "admin already has platform_admin grant");
            }
            roleService.assignRoleToMember(member.getId(), roleId, systemTenant.getId());
            return RepairStepResult.created(STEP_ADMIN_ROLE_GRANT,
                    "admin granted platform_admin role");
        } catch (Exception e) {
            log.error("repairAdminRoleGrant failed", e);
            return RepairStepResult.error(STEP_ADMIN_ROLE_GRANT, e.getMessage());
        }
    }

    /** Invariant 7: Business Tenant exists ({@code opts.companyName()}). */
    public RepairStepResult repairBusinessTenant(RepairOptions opts) {
        try {
            Tenant existing = tenantService.findByName(opts.companyName());
            if (existing != null) {
                return RepairStepResult.present(STEP_BUSINESS_TENANT,
                        "Business Tenant '" + opts.companyName() + "' already exists (id=" + existing.getId() + ")");
            }
            Tenant tenant = new Tenant();
            tenant.setPid(UniqueIdGenerator.generate());
            tenant.setName(opts.companyName());
            tenant.setDisplayName(opts.companyName());
            tenant.setStatus("active");
            tenant.setContactEmail(opts.adminEmail());
            Tenant created = tenantService.createTenant(tenant);
            return RepairStepResult.created(STEP_BUSINESS_TENANT,
                    "Business Tenant '" + opts.companyName() + "' created (id=" + created.getId() + ")");
        } catch (Exception e) {
            log.error("repairBusinessTenant failed", e);
            return RepairStepResult.error(STEP_BUSINESS_TENANT, e.getMessage());
        }
    }

    /**
     * Invariant 8: Business Tenant default template bootstrap has run before any
     * plugin/model import can auto-generate permissions.
     *
     * <p>This creates the default tenant roles and system/template permission
     * bindings through {@link TenantBootstrapService}. Keeping this before
     * {@link #repairBuiltinPlugins} preserves the normal auto-permission path:
     * model permissions are bound when they are created, not repaired afterward.
     */
    public RepairStepResult repairBusinessTenantBootstrap(RepairOptions opts) {
        try {
            User admin = userService.findByEmail(opts.adminEmail());
            Tenant businessTenant = tenantService.findByName(opts.companyName());
            if (admin == null || businessTenant == null) {
                return RepairStepResult.error(STEP_BUSINESS_TENANT_BOOTSTRAP,
                        "admin user or Business Tenant missing — earlier steps must run first");
            }

            List<Role> roles = roleService.findByTenantId(businessTenant.getId());
            Set<String> roleCodes = roles.stream()
                    .map(Role::getCode)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> requiredRoleCodes = Set.of("tenant_admin", "operator", "viewer");

            if (roleCodes.containsAll(requiredRoleCodes)) {
                return RepairStepResult.present(STEP_BUSINESS_TENANT_BOOTSTRAP,
                        "Business Tenant template roles already exist");
            }
            if (!roleCodes.isEmpty()) {
                return RepairStepResult.error(STEP_BUSINESS_TENANT_BOOTSTRAP,
                        "Business Tenant has partial template roles; refusing to run bootstrap over mixed state");
            }

            TenantBootstrapService.BootstrapResult result =
                    tenantBootstrapService.bootstrapTenant(businessTenant.getId(), admin.getId());
            if (!result.isSuccess()) {
                return RepairStepResult.error(STEP_BUSINESS_TENANT_BOOTSTRAP, result.getMessage());
            }
            return RepairStepResult.repaired(STEP_BUSINESS_TENANT_BOOTSTRAP,
                    "Business Tenant template bootstrap complete — roles=" + result.getRolesCreated()
                            + ", menus=" + result.getMenusCreated()
                            + ", permissions=" + result.getPermissionsAssigned());
        } catch (Exception e) {
            log.error("repairBusinessTenantBootstrap failed", e);
            return RepairStepResult.error(STEP_BUSINESS_TENANT_BOOTSTRAP, e.getMessage());
        }
    }

    /** Invariant 8: built-in plugins imported for the Business Tenant (idempotent — see {@link BuiltinPluginImportService}). */
    public RepairStepResult repairBuiltinPlugins(RepairOptions opts) {
        try {
            User admin = userService.findByEmail(opts.adminEmail());
            Tenant businessTenant = tenantService.findByName(opts.companyName());
            if (admin == null || businessTenant == null) {
                return RepairStepResult.error(STEP_BUILTIN_PLUGINS,
                        "admin user or Business Tenant missing — earlier steps must run first");
            }
            MetaContext.setContext(businessTenant.getId(), admin.getId(), admin.getPid(), "system");
            try {
                builtinPluginImportService.importForTenant(
                        businessTenant.getId(), admin.getId(), opts.includeDemoPlugins());
            } finally {
                MetaContext.clear();
            }
            return RepairStepResult.repaired(STEP_BUILTIN_PLUGINS,
                    "BuiltinPluginImportService.importForTenant invoked (includeDemo="
                            + opts.includeDemoPlugins() + "; skips up-to-date plugins internally)");
        } catch (Exception e) {
            log.warn("repairBuiltinPlugins failed (non-fatal)", e);
            return RepairStepResult.error(STEP_BUILTIN_PLUGINS, e.getMessage());
        }
    }

    /**
     * Invariant 10: JWT secret consistency. Today the secret is sourced from
     * {@code application.yml#security.jwt.secret} (env override
     * {@code JWT_SECRET}); there is no {@code system_config.jwt_secret} row in OSS.
     * This step is a structural placeholder that records the current source — Phase 2.4
     * may migrate the secret into {@code ab_system_config} for repair-anywhere semantics.
     */
    public RepairStepResult repairJwtSecret(RepairOptions opts) {
        return RepairStepResult.present(STEP_JWT_SECRET,
                "JWT secret sourced from security.jwt.secret (no system_config row required)");
    }

    // ────────────────────────────────────────────────────────────────────
    // Aggregate API
    // ────────────────────────────────────────────────────────────────────

    /**
     * Run all repair steps in their canonical order. Returns a {@link RepairReport}
     * with one {@link RepairStepResult} per step. Stops at the first error step (subsequent
     * steps may depend on the failed one).
     */
    public RepairReport repairAll(RepairOptions opts) {
        List<RepairStepResult> results = new ArrayList<>();
        results.add(repairSystemConfig(opts));
        if (lastIsError(results)) return RepairReport.from(results);
        results.add(repairSystemTenant(opts));
        if (lastIsError(results)) return RepairReport.from(results);
        results.add(repairAdminUser(opts));
        if (lastIsError(results)) return RepairReport.from(results);
        results.add(repairBusinessTenant(opts));
        if (lastIsError(results)) return RepairReport.from(results);
        results.add(repairAdminMembership(opts));
        if (lastIsError(results)) return RepairReport.from(results);
        results.add(repairBusinessTenantBootstrap(opts));
        if (lastIsError(results)) return RepairReport.from(results);
        // Order: platform_admin role + grant must come AFTER admin user + membership exist.
        results.add(repairPlatformAdminRole(opts));
        if (lastIsError(results)) return RepairReport.from(results);
        results.add(repairAdminRoleGrant(opts));
        if (lastIsError(results)) return RepairReport.from(results);
        results.add(repairBuiltinPlugins(opts));
        // Plugin import is non-fatal per existing BootstrapEngineService semantics — keep going.
        results.add(repairJwtSecret(opts));
        return RepairReport.from(results);
    }

    /**
     * Run a single named step for the explicit bootstrap/setup flow.
     */
    public RepairStepResult repair(String stepName, RepairOptions opts) {
        return switch (stepName) {
            case STEP_SYSTEM_CONFIG       -> repairSystemConfig(opts);
            case STEP_SYSTEM_TENANT       -> repairSystemTenant(opts);
            case STEP_PLATFORM_ADMIN_ROLE -> repairPlatformAdminRole(opts);
            case STEP_ADMIN_USER          -> repairAdminUser(opts);
            case STEP_ADMIN_MEMBERSHIP    -> repairAdminMembership(opts);
            case STEP_ADMIN_ROLE_GRANT    -> repairAdminRoleGrant(opts);
            case STEP_BUSINESS_TENANT     -> repairBusinessTenant(opts);
            case STEP_BUSINESS_TENANT_BOOTSTRAP -> repairBusinessTenantBootstrap(opts);
            case STEP_BUILTIN_PLUGINS     -> repairBuiltinPlugins(opts);
            case STEP_JWT_SECRET          -> repairJwtSecret(opts);
            default -> RepairStepResult.error(stepName, "unknown step: " + stepName);
        };
    }

    private static boolean lastIsError(List<RepairStepResult> results) {
        return !results.isEmpty()
                && results.get(results.size() - 1).status() == RepairStepResult.Status.ERROR;
    }

    // ────────────────────────────────────────────────────────────────────
    // Internal helpers (lifted from BootstrapEngineService)
    // ────────────────────────────────────────────────────────────────────

    private void createPlatformMenus(Long tenantId, Long adminUserId) {
        createMenu(tenantId, null, "platform_overview", "Overview", "/platform/overview", 1, 10, "ChartBarIcon", adminUserId);
        createMenu(tenantId, null, "platform_tenants", "Tenants", "/platform/tenants", 1, 20, "BuildingOfficeIcon", adminUserId);

        Menu commerce = createMenu(tenantId, null, "platform_commerce", "Commerce", null, 0, 30, null, adminUserId);
        createMenu(tenantId, commerce.getId(), "platform_marketplace", "Marketplace", "/platform/marketplace", 1, 31, "ShoppingBagIcon", adminUserId);
        createMenu(tenantId, commerce.getId(), "platform_licenses", "Licenses", "/platform/licenses", 1, 32, "KeyIcon", adminUserId);

        Menu infra = createMenu(tenantId, null, "platform_infra", "Platform", null, 0, 40, null, adminUserId);
        createMenu(tenantId, infra.getId(), "platform_cloud_config", "Cloud Config", "/platform/cloud-config", 1, 41, "CloudIcon", adminUserId);
        createMenu(tenantId, infra.getId(), "platform_templates", "Templates", "/platform/templates", 1, 42, "DocumentDuplicateIcon", adminUserId);
        createMenu(tenantId, infra.getId(), "platform_plugins", "Plugins", "/platform/plugins", 1, 43, "PuzzlePieceIcon", adminUserId);

        Menu system = createMenu(tenantId, null, "platform_system", "System", null, 0, 50, null, adminUserId);
        createMenu(tenantId, system.getId(), "platform_audit_logs", "Audit Log", "/platform/audit-logs", 1, 51, "ClipboardDocumentListIcon", adminUserId);
        createMenu(tenantId, system.getId(), "platform_settings", "Settings", "/platform/system-preferences", 1, 52, "Cog6ToothIcon", adminUserId);
    }

    private Menu createMenu(Long tenantId, Long parentId, String code, String name, String path,
                            int type, int orderNo, String icon, Long adminUserId) {
        // Idempotent: skip if menu with same (tenant_id, code) already exists.
        QueryWrapper<Menu> qw = new QueryWrapper<>();
        qw.eq("tenant_id", tenantId).eq("code", code);
        Menu existing = menuMapper.selectOne(qw);
        if (existing != null) {
            return existing;
        }
        Menu menu = new Menu();
        menu.setTenantId(tenantId);
        menu.setParentId(parentId);
        menu.setPid(UniqueIdGenerator.generate());
        menu.setCode(code);
        menu.setName(name);
        menu.setPath(path);
        menu.setType(type);
        menu.setOrderNo(orderNo);
        menu.setIcon(icon);
        menu.setVisible(true);
        menu.setCreatedBy(adminUserId);
        menu.setUpdatedBy(adminUserId);
        menuMapper.insert(menu);
        return menu;
    }

    // ────────────────────────────────────────────────────────────────────
    // Options DTO
    // ────────────────────────────────────────────────────────────────────

    /**
     * Inputs for the repair pipeline. {@link #fromBootstrapRequest} translates the
     * existing wizard payload; future callers (startup runner, admin endpoint) construct
     * directly via {@link #of}.
     */
    public record RepairOptions(
            String adminEmail,
            String adminPassword,
            String adminDisplayName,
            String companyName,
            String systemMode,
            String instanceUrl,
            boolean includeDemoPlugins) {

        /** 6-arg variant — defaults {@code includeDemoPlugins=false} (prod-safe). */
        public static RepairOptions of(String adminEmail, String adminPassword,
                                       String adminDisplayName, String companyName,
                                       String systemMode, String instanceUrl) {
            return new RepairOptions(adminEmail, adminPassword, adminDisplayName,
                    companyName, systemMode, instanceUrl, false);
        }

        /** 7-arg variant — explicit {@code includeDemoPlugins} (Phase 3 demo profile). */
        public static RepairOptions of(String adminEmail, String adminPassword,
                                       String adminDisplayName, String companyName,
                                       String systemMode, String instanceUrl,
                                       boolean includeDemoPlugins) {
            return new RepairOptions(adminEmail, adminPassword, adminDisplayName,
                    companyName, systemMode, instanceUrl, includeDemoPlugins);
        }

        public static RepairOptions fromBootstrapRequest(
                com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest req) {
            return new RepairOptions(
                    req.getAdminEmail(),
                    req.getAdminPassword(),
                    req.getAdminDisplayName(),
                    req.getCompanyName(),
                    req.getSystemMode(),
                    req.getInstanceUrl(),
                    Boolean.TRUE.equals(req.getSeedDemoData()));
        }
    }
}
