package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.plugin.service.BuiltinPluginImportService;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapProgressResponse;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest;
import com.auraboot.framework.saas.config.entity.BootstrapEntity;
import com.auraboot.framework.saas.config.mapper.BootstrapMapper;
import com.auraboot.framework.saas.config.mapper.SystemConfigMapper;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.constant.BootstrapStatus;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import com.auraboot.framework.saas.constant.SystemMode;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * Bootstrap Engine — orchestrates the 15-step system initialization pipeline.
 *
 * <p>Layer A (Steps 1-8): Core bootstrap within a single transaction.
 * <p>Layer B (Steps 9-12): Runtime setup, each step independent (non-transactional).
 * <p>Layer C (Steps 13-14): Optional setup (demo data, AuraBot).
 * <p>Step 15: Finalize — mark system as initialized.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BootstrapEngineService {

    private static final int TOTAL_STEPS = 15;

    private final SystemConfigService systemConfigService;
    private final SystemConfigMapper systemConfigMapper;
    private final BootstrapMapper bootstrapMapper;
    private final UserService userService;
    private final TenantService tenantService;
    private final TenantMemberService tenantMemberService;
    private final TenantBootstrapService tenantBootstrapService;
    private final BuiltinPluginImportService builtinPluginImportService;
    private final RoleService roleService;
    private final ObjectMapper objectMapper;

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Main entry point. Runs the full 15-step bootstrap pipeline.
     *
     * @return result containing success flag, JWT placeholder, tenant ID, or error
     */
    public BootstrapResult execute(BootstrapRequest request) {
        // Guard: already initialized
        if (systemConfigService.isInitialized()) {
            return BootstrapResult.failure("System is already initialized");
        }

        // Guard: another bootstrap in progress
        BootstrapEntity active = bootstrapMapper.findActiveBootstrap();
        if (active != null) {
            return BootstrapResult.failure("Another bootstrap is already in progress (id=" + active.getId() + ")");
        }

        // Create bootstrap record
        BootstrapEntity bootstrap = createBootstrapRecord(request);

        try {
            // Layer A: Core (transactional)
            CoreBootstrapResult coreResult = executeCoreBootstrap(bootstrap, request);

            // Layer B: Runtime (non-transactional, each step independent)
            executeRuntimeSetup(bootstrap, coreResult);

            // Layer C: Optional (non-transactional)
            executeOptionalSetup(bootstrap, request, coreResult);

            // Step 15: Finalize
            finalizeBootstrap(bootstrap, coreResult);

            return BootstrapResult.success(null, coreResult.defaultTenantId);
        } catch (Exception e) {
            log.error("Bootstrap failed at step: {}", bootstrap.getCurrentStep(), e);
            markFailed(bootstrap, e.getMessage());
            return BootstrapResult.failure(e.getMessage());
        }
    }

    /**
     * Returns current bootstrap progress.
     */
    public BootstrapProgressResponse getProgress() {
        BootstrapEntity active = bootstrapMapper.findActiveBootstrap();
        if (active == null) {
            return BootstrapProgressResponse.builder()
                    .status("idle")
                    .totalSteps(TOTAL_STEPS)
                    .completedSteps(0)
                    .build();
        }
        return BootstrapProgressResponse.builder()
                .status(active.getStatus())
                .currentStep(active.getCurrentStep())
                .totalSteps(active.getTotalSteps())
                .completedSteps(active.getCompletedSteps() != null ? active.getCompletedSteps() : 0)
                .error(active.getErrorMessage())
                .build();
    }

    // ── Layer A: Core Bootstrap (Transactional) ─────────────────────────

    @Transactional(rollbackFor = Exception.class)
    public CoreBootstrapResult executeCoreBootstrap(BootstrapEntity bootstrap, BootstrapRequest request) {
        CoreBootstrapResult result = new CoreBootstrapResult();

        // Step 1: Validate input
        updateProgress(bootstrap, 1, "validate_input");
        validateInput(request);

        // Step 2: Write system config
        updateProgress(bootstrap, 2, "write_system_config");
        writeSystemConfig(request);

        // Step 3: Create system tenant
        updateProgress(bootstrap, 3, "create_system_tenant");
        Tenant systemTenant = createSystemTenant();
        result.systemTenantId = systemTenant.getId();
        bootstrap.setSystemTenantId(systemTenant.getId());

        // Step 4: Platform Account (stub for Phase 5)
        updateProgress(bootstrap, 4, "create_platform_account");
        log.info("Step 4: Platform Account — stub for Phase 5");

        // Step 5: Create admin user
        updateProgress(bootstrap, 5, "create_admin_user");
        User adminUser = userService.signUp(
                request.getAdminEmail(),
                request.getAdminPassword(),
                request.getAdminDisplayName()
        );
        result.adminUserId = adminUser.getId();
        result.adminUserPid = adminUser.getPid();
        bootstrap.setAdminUserId(adminUser.getId());

        // Step 6: Create default tenant
        updateProgress(bootstrap, 6, "create_default_tenant");
        Tenant defaultTenant = createDefaultTenant(request);
        result.defaultTenantId = defaultTenant.getId();
        bootstrap.setDefaultTenantId(defaultTenant.getId());

        // Step 7: Add admin to both tenants
        updateProgress(bootstrap, 7, "add_admin_to_tenants");
        tenantMemberService.addMember(adminUser.getId(), defaultTenant.getId(), "active");
        tenantMemberService.addMember(adminUser.getId(), systemTenant.getId(), "active");

        // Step 8: Bootstrap default tenant (roles/permissions/menus)
        updateProgress(bootstrap, 8, "bootstrap_default_tenant");
        MetaContext.setContext(defaultTenant.getId(), adminUser.getId(), adminUser.getPid(), adminUser.getEmail());
        try {
            TenantBootstrapService.BootstrapResult tenantResult =
                    tenantBootstrapService.bootstrapTenant(defaultTenant.getId(), adminUser.getId());
            if (!tenantResult.isSuccess()) {
                throw new RuntimeException("Tenant bootstrap failed: " + tenantResult.getMessage());
            }
            log.info("Step 8: Tenant bootstrap complete — roles={}, menus={}, permissions={}",
                    tenantResult.getRolesCreated(), tenantResult.getMenusCreated(),
                    tenantResult.getPermissionsAssigned());
        } finally {
            MetaContext.clear();
        }

        // Step 8.5: Bootstrap System Tenant — create platform_admin role + assign to admin
        updateProgress(bootstrap, 8, "bootstrap_system_tenant");
        MetaContext.setContext(systemTenant.getId(), adminUser.getId(), adminUser.getPid(), adminUser.getEmail());
        try {
            bootstrapSystemTenant(systemTenant.getId(), adminUser.getId());
            log.info("Step 8.5: System tenant bootstrap complete — platform_admin role created");
        } finally {
            MetaContext.clear();
        }

        return result;
    }

    /**
     * Bootstrap the System Tenant with platform_admin role.
     * Platform Admin manages: tenant lifecycle, license, marketplace, system config.
     * Separate from tenant_admin which manages business within a single tenant.
     */
    private void bootstrapSystemTenant(Long systemTenantId, Long adminUserId) {
        // Create platform_admin role in System Tenant
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
        platformAdmin.setCreatedBy(adminUserId);
        platformAdmin.setUpdatedBy(adminUserId);

        Role created = roleService.createRole(platformAdmin);

        // Assign platform_admin role to admin user in System Tenant
        roleService.assignRoleToUser(adminUserId, created.getId(), systemTenantId, null);

        log.info("platform_admin role created (id={}) and assigned to admin user (id={}) in System Tenant (id={})",
                created.getId(), adminUserId, systemTenantId);
    }

    // ── Layer B: Runtime Setup (Non-Transactional) ──────────────────────

    private void executeRuntimeSetup(BootstrapEntity bootstrap, CoreBootstrapResult coreResult) {
        // Step 9: Import builtin plugins
        updateProgress(bootstrap, 9, "import_builtin_plugins");
        try {
            MetaContext.setContext(coreResult.defaultTenantId, coreResult.adminUserId,
                    coreResult.adminUserPid, "system");
            builtinPluginImportService.importForTenant(coreResult.defaultTenantId, coreResult.adminUserId);
            log.info("Step 9: Built-in plugins imported");
        } catch (Exception e) {
            log.warn("Step 9: Built-in plugin import failed (non-fatal): {}", e.getMessage());
        } finally {
            MetaContext.clear();
        }

        // Step 10: Marketplace categories (stub)
        updateProgress(bootstrap, 10, "marketplace_categories");
        log.info("Step 10: Marketplace categories — stub");

        // Step 11: i18n sync (stub)
        updateProgress(bootstrap, 11, "i18n_sync");
        log.info("Step 11: i18n sync — stub");

        // Step 12: License initialization (stub)
        updateProgress(bootstrap, 12, "license_init");
        log.info("Step 12: License initialization — stub");
    }

    // ── Layer C: Optional Setup (Non-Transactional) ─────────────────────

    private void executeOptionalSetup(BootstrapEntity bootstrap, BootstrapRequest request,
                                      CoreBootstrapResult coreResult) {
        // Step 13: Seed demo data (stub)
        updateProgress(bootstrap, 13, "seed_demo_data");
        if (Boolean.TRUE.equals(request.getSeedDemoData())) {
            log.info("Step 13: Demo data seeding — stub");
        } else {
            log.info("Step 13: Demo data seeding — skipped (not requested)");
        }

        // Step 14: AuraBot setup (stub)
        updateProgress(bootstrap, 14, "aurabot_setup");
        log.info("Step 14: AuraBot setup — stub");
    }

    // ── Step 15: Finalize ───────────────────────────────────────────────

    private void finalizeBootstrap(BootstrapEntity bootstrap, CoreBootstrapResult coreResult) {
        updateProgress(bootstrap, 15, "finalize");

        // Use initialize() (create-or-update) since these rows may not exist yet on a fresh DB
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_INITIALIZED, "true",
                "system", "boolean", "Whether the system has been bootstrapped", true);
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_DEFAULT_TENANT_ID,
                String.valueOf(coreResult.defaultTenantId),
                "system", "string", "Default tenant ID", true);
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_SETUP_AT, Instant.now().toString(),
                "system", "string", "System setup timestamp", true);

        bootstrap.setStatus(BootstrapStatus.COMPLETED.getCode());
        bootstrap.setCompletedSteps(TOTAL_STEPS);
        bootstrap.setCurrentStep("done");
        bootstrap.setCompletedAt(Instant.now());
        bootstrapMapper.updateById(bootstrap);

        log.info("Bootstrap completed successfully — defaultTenantId={}, adminUserId={}",
                coreResult.defaultTenantId, coreResult.adminUserId);
    }

    // ── Internal Helpers ────────────────────────────────────────────────

    private void validateInput(BootstrapRequest request) {
        if (!StringUtils.hasText(request.getAdminEmail())) {
            throw new IllegalArgumentException("adminEmail is required");
        }
        if (!StringUtils.hasText(request.getAdminPassword())) {
            throw new IllegalArgumentException("adminPassword is required");
        }
        if (!StringUtils.hasText(request.getCompanyName())) {
            throw new IllegalArgumentException("companyName is required");
        }

        // Validate systemMode if provided
        if (StringUtils.hasText(request.getSystemMode())) {
            SystemMode.fromCode(request.getSystemMode()); // throws if invalid
        }
    }

    private void writeSystemConfig(BootstrapRequest request) {
        String mode = StringUtils.hasText(request.getSystemMode())
                ? request.getSystemMode() : SystemMode.SINGLE.getCode();

        // Use initialize() (create-or-update) since these rows may not exist yet on a fresh DB
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_MODE, mode,
                "system", "string", "System mode (single/multi/hybrid)", true);
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_PLATFORM_NAME, request.getCompanyName(),
                "system", "string", "Platform display name", false);
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_ALLOW_SELF_REGISTRATION, "false",
                "system", "boolean", "Allow self-registration", false);

        // Instance identity: generate db_uuid via PostgreSQL gen_random_uuid() (immutable, created once)
        String existingDbUuid = systemConfigService.get(SystemConfigKeys.SYSTEM_DB_UUID).orElse(null);
        if (existingDbUuid == null || existingDbUuid.isBlank()) {
            String dbUuid = systemConfigMapper.generateDbUuid();
            systemConfigService.initialize(SystemConfigKeys.SYSTEM_DB_UUID, dbUuid,
                    "system", "string", "Database instance unique identifier (immutable)", true);
            log.info("Generated db_uuid via gen_random_uuid(): {}", dbUuid);
        }

        // Instance URL (user-provided or default, mutable)
        String instanceUrl = StringUtils.hasText(request.getInstanceUrl())
                ? request.getInstanceUrl() : "http://localhost:6443";
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_INSTANCE_URL, instanceUrl,
                "system", "string", "Instance base URL for fingerprint binding", false);
    }

    private Tenant createSystemTenant() {
        // Idempotent: check if system tenant already exists
        Tenant existing = tenantService.findByName("System");
        if (existing != null) {
            log.info("Step 3: System tenant already exists (id={}), reusing", existing.getId());
            return existing;
        }

        Tenant systemTenant = new Tenant();
        systemTenant.setPid(UniqueIdGenerator.generate());
        systemTenant.setName("System");
        systemTenant.setDisplayName("System");
        systemTenant.setStatus("active");
        return tenantService.createTenant(systemTenant);
    }

    private Tenant createDefaultTenant(BootstrapRequest request) {
        Tenant tenant = new Tenant();
        tenant.setPid(UniqueIdGenerator.generate());
        tenant.setName(request.getCompanyName());
        tenant.setDisplayName(request.getCompanyName());
        tenant.setStatus("active");
        tenant.setContactEmail(request.getAdminEmail());
        return tenantService.createTenant(tenant);
    }

    private BootstrapEntity createBootstrapRecord(BootstrapRequest request) {
        BootstrapEntity entity = new BootstrapEntity();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setBootstrapMode("full");
        entity.setStatus(BootstrapStatus.RUNNING.getCode());
        entity.setSystemMode(StringUtils.hasText(request.getSystemMode())
                ? request.getSystemMode() : SystemMode.SINGLE.getCode());
        entity.setTotalSteps(TOTAL_STEPS);
        entity.setCompletedSteps(0);
        entity.setCurrentStep("init");
        entity.setStartedAt(Instant.now());

        try {
            entity.setInputParams(objectMapper.writeValueAsString(request));
        } catch (Exception e) {
            log.warn("Failed to serialize bootstrap request params", e);
        }

        bootstrapMapper.insert(entity);
        return entity;
    }

    private void updateProgress(BootstrapEntity bootstrap, int step, String stepName) {
        bootstrap.setCompletedSteps(step - 1);
        bootstrap.setCurrentStep(stepName);
        bootstrapMapper.updateById(bootstrap);
        log.info("Bootstrap step {}/{}: {}", step, TOTAL_STEPS, stepName);
    }

    private void markFailed(BootstrapEntity bootstrap, String error) {
        bootstrap.setStatus(BootstrapStatus.FAILED.getCode());
        bootstrap.setErrorMessage(error);
        bootstrap.setCompletedAt(Instant.now());
        bootstrapMapper.updateById(bootstrap);
    }

    // ── Result Types ────────────────────────────────────────────────────

    /**
     * Internal result from Layer A core bootstrap.
     */
    static class CoreBootstrapResult {
        Long systemTenantId;
        Long defaultTenantId;
        Long adminUserId;
        String adminUserPid;
    }

    /**
     * Public result from the bootstrap engine.
     */
    public record BootstrapResult(boolean success, String jwt, Long tenantId, String error) {
        public static BootstrapResult success(String jwt, Long tenantId) {
            return new BootstrapResult(true, jwt, tenantId, null);
        }

        public static BootstrapResult failure(String error) {
            return new BootstrapResult(false, null, null, error);
        }
    }
}
