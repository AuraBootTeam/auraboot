package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapProgressResponse;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest;
import com.auraboot.framework.saas.config.entity.BootstrapEntity;
import com.auraboot.framework.saas.config.mapper.BootstrapMapper;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.constant.BootstrapStatus;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import com.auraboot.framework.saas.constant.SystemMode;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
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
 * <p>As of Phase 2.2 (bootstrap-unified plan §7), the actual repair work for the
 * 9 invariants lives in {@link BootstrapRepairService}. This service retains the
 * existing public contract:
 * <ul>
 *   <li>Guards "already initialized" + "another in progress"</li>
 *   <li>Creates / updates the {@code ab_bootstrap} progress row</li>
 *   <li>Calls {@link BootstrapRepairService} step-by-step inside a single
 *       Layer-A transaction (matches pre-2.2 behavior)</li>
 *   <li>Runs {@code TenantBootstrapService.bootstrapTenant} for the business
 *       tenant (heavy roles/permissions/menus seeding — kept here, not extracted)</li>
 *   <li>Finalizes by writing {@code system.initialized=true}</li>
 * </ul>
 *
 * <p>The public {@link #execute(BootstrapRequest)} contract — including the
 * "fail-if-already-initialized" guard relied on by the wizard UI — is unchanged
 * from pre-2.2. Inverting that contract is Phase 2.3.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BootstrapEngineService {

    private static final int TOTAL_STEPS = 15;

    private final SystemConfigService systemConfigService;
    private final BootstrapMapper bootstrapMapper;
    private final UserService userService;
    private final TenantService tenantService;
    private final TenantBootstrapService tenantBootstrapService;
    private final BootstrapRepairService bootstrapRepairService;
    private final ObjectMapper objectMapper;

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Main entry point. Runs the full 15-step bootstrap pipeline.
     *
     * @return result containing success flag, JWT placeholder, tenant ID, or error
     */
    public BootstrapResult execute(BootstrapRequest request) {
        if (systemConfigService.isInitialized()) {
            return BootstrapResult.failure("System is already initialized");
        }

        BootstrapEntity active = bootstrapMapper.findActiveBootstrap();
        if (active != null) {
            return BootstrapResult.failure("Another bootstrap is already in progress (id=" + active.getId() + ")");
        }

        BootstrapEntity bootstrap = createBootstrapRecord(request);

        try {
            CoreBootstrapResult coreResult = executeCoreBootstrap(bootstrap, request);
            executeRuntimeSetup(bootstrap, coreResult, request);
            executeOptionalSetup(bootstrap, request, coreResult);
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
        BootstrapRepairService.RepairOptions opts =
                BootstrapRepairService.RepairOptions.fromBootstrapRequest(request);

        // Step 1: Validate input
        updateProgress(bootstrap, 1, "validate_input");
        validateInput(request);

        // Step 2: Write system config
        updateProgress(bootstrap, 2, "write_system_config");
        throwIfError(bootstrapRepairService.repairSystemConfig(opts));

        // Step 3: Create system tenant
        updateProgress(bootstrap, 3, "create_system_tenant");
        throwIfError(bootstrapRepairService.repairSystemTenant(opts));
        Tenant systemTenant = tenantService.findByName("System");
        if (systemTenant == null) {
            throw new IllegalStateException("System Tenant not found after repair");
        }
        result.systemTenantId = systemTenant.getId();
        bootstrap.setSystemTenantId(systemTenant.getId());

        // Step 4: Platform Account (stub for Phase 5)
        updateProgress(bootstrap, 4, "create_platform_account");
        log.info("Step 4: Platform Account — stub for Phase 5");

        // Step 5: Create admin user
        updateProgress(bootstrap, 5, "create_admin_user");
        throwIfError(bootstrapRepairService.repairAdminUser(opts));
        User adminUser = userService.findByEmail(request.getAdminEmail());
        if (adminUser == null) {
            throw new IllegalStateException("admin user not found after repair");
        }
        result.adminUserId = adminUser.getId();
        result.adminUserPid = adminUser.getPid();
        bootstrap.setAdminUserId(adminUser.getId());

        // Step 6: Create default (business) tenant
        updateProgress(bootstrap, 6, "create_default_tenant");
        throwIfError(bootstrapRepairService.repairBusinessTenant(opts));
        Tenant defaultTenant = tenantService.findByName(request.getCompanyName());
        if (defaultTenant == null) {
            throw new IllegalStateException("Business Tenant not found after repair");
        }
        result.defaultTenantId = defaultTenant.getId();
        bootstrap.setDefaultTenantId(defaultTenant.getId());

        // Step 7: Add admin to both tenants
        updateProgress(bootstrap, 7, "add_admin_to_tenants");
        throwIfError(bootstrapRepairService.repairAdminMembership(opts));

        // Step 8: Bootstrap default tenant (roles/permissions/menus). Heavy seed —
        // owned by TenantBootstrapService, NOT one of the 9 invariants.
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

        // Step 8.5: Bootstrap System Tenant — platform_admin role + grant + menus
        updateProgress(bootstrap, 8, "bootstrap_system_tenant");
        MetaContext.setContext(systemTenant.getId(), adminUser.getId(), adminUser.getPid(), adminUser.getEmail());
        try {
            throwIfError(bootstrapRepairService.repairPlatformAdminRole(opts));
            throwIfError(bootstrapRepairService.repairAdminRoleGrant(opts));
            log.info("Step 8.5: System tenant bootstrap complete — platform_admin role created");
        } finally {
            MetaContext.clear();
        }

        return result;
    }

    // ── Layer B: Runtime Setup (Non-Transactional) ──────────────────────

    private void executeRuntimeSetup(BootstrapEntity bootstrap, CoreBootstrapResult coreResult,
                                     BootstrapRequest request) {
        // Step 9: Import builtin plugins (non-fatal on failure — matches pre-2.2 behavior)
        updateProgress(bootstrap, 9, "import_builtin_plugins");
        BootstrapRepairService.RepairOptions opts =
                BootstrapRepairService.RepairOptions.fromBootstrapRequest(request);
        RepairStepResult plugins = bootstrapRepairService.repairBuiltinPlugins(opts);
        if (plugins.status() == RepairStepResult.Status.ERROR) {
            log.warn("Step 9: Built-in plugin import failed (non-fatal): {}", plugins.detail());
        } else {
            log.info("Step 9: Built-in plugins imported — {}", plugins.detail());
        }

        // Steps 10-12: stubs (kept here for progress reporting parity)
        updateProgress(bootstrap, 10, "marketplace_categories");
        log.info("Step 10: Marketplace categories — stub");
        updateProgress(bootstrap, 11, "i18n_sync");
        log.info("Step 11: i18n sync — stub");
        updateProgress(bootstrap, 12, "license_init");
        log.info("Step 12: License initialization — stub");
    }

    // ── Layer C: Optional Setup (Non-Transactional) ─────────────────────

    private void executeOptionalSetup(BootstrapEntity bootstrap, BootstrapRequest request,
                                      CoreBootstrapResult coreResult) {
        updateProgress(bootstrap, 13, "seed_demo_data");
        if (Boolean.TRUE.equals(request.getSeedDemoData())) {
            log.info("Step 13: Demo data seeding — stub");
        } else {
            log.info("Step 13: Demo data seeding — skipped (not requested)");
        }
        updateProgress(bootstrap, 14, "aurabot_setup");
        log.info("Step 14: AuraBot setup — stub");
    }

    // ── Step 15: Finalize ───────────────────────────────────────────────

    private void finalizeBootstrap(BootstrapEntity bootstrap, CoreBootstrapResult coreResult) {
        updateProgress(bootstrap, 15, "finalize");

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

    private static void throwIfError(RepairStepResult result) {
        if (result.status() == RepairStepResult.Status.ERROR) {
            throw new RuntimeException(
                    "Repair step '" + result.stepName() + "' failed: " + result.detail());
        }
    }

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
        if (StringUtils.hasText(request.getSystemMode())) {
            SystemMode.fromCode(request.getSystemMode());
        }
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

    /** Internal result from Layer A core bootstrap. */
    static class CoreBootstrapResult {
        Long systemTenantId;
        Long defaultTenantId;
        Long adminUserId;
        String adminUserPid;
    }

    /** Public result from the bootstrap engine. */
    public record BootstrapResult(boolean success, String jwt, Long tenantId, String error) {
        public static BootstrapResult success(String jwt, Long tenantId) {
            return new BootstrapResult(true, jwt, tenantId, null);
        }

        public static BootstrapResult failure(String error) {
            return new BootstrapResult(false, null, null, error);
        }
    }
}
