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
 * Bootstrap Engine — orchestrates the minimal system initialization pipeline.
 *
 * <p>The setup API is the first-install authority for platform minimum data:
 * system config, System tenant, admin user, Business tenant, tenant membership,
 * Business tenant roles/permissions, and System tenant platform_admin grant.
 * Plugin import, marketplace catalog sync, and demo seeds are explicitly owned
 * by reset/init scripts.
 *
 * <p>The actual repair work for the bootstrap invariants lives in
 * {@link BootstrapRepairService}. This service retains the existing public
 * contract:
 * <ul>
 *   <li>Guards "already initialized" + "another in progress"</li>
 *   <li>Creates / updates the {@code ab_bootstrap} progress row</li>
 *   <li>Calls {@link BootstrapRepairService} step-by-step inside a single
 *       Layer-A transaction (matches pre-2.2 behavior)</li>
 *   <li>Runs {@code TenantBootstrapService.bootstrapTenant} for the business
 *       tenant roles/permissions/menus</li>
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

    private static final int TOTAL_STEPS = 9;

    private final SystemConfigService systemConfigService;
    private final BootstrapMapper bootstrapMapper;
    private final UserService userService;
    private final TenantService tenantService;
    private final TenantBootstrapService tenantBootstrapService;
    private final BootstrapRepairService bootstrapRepairService;
    private final ObjectMapper objectMapper;

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Main entry point. Runs the minimal bootstrap pipeline.
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

        // Step 4: Create admin user
        updateProgress(bootstrap, 4, "create_admin_user");
        throwIfError(bootstrapRepairService.repairAdminUser(opts));
        User adminUser = userService.findByEmail(request.getAdminEmail());
        if (adminUser == null) {
            throw new IllegalStateException("admin user not found after repair");
        }
        result.adminUserId = adminUser.getId();
        result.adminUserPid = adminUser.getPid();
        bootstrap.setAdminUserId(adminUser.getId());

        // Step 5: Create default (business) tenant
        updateProgress(bootstrap, 5, "create_default_tenant");
        throwIfError(bootstrapRepairService.repairBusinessTenant(opts));
        Tenant defaultTenant = tenantService.findByName(request.getCompanyName());
        if (defaultTenant == null) {
            throw new IllegalStateException("Business Tenant not found after repair");
        }
        result.defaultTenantId = defaultTenant.getId();
        bootstrap.setDefaultTenantId(defaultTenant.getId());

        // Step 6: Add admin to both tenants
        updateProgress(bootstrap, 6, "add_admin_to_tenants");
        throwIfError(bootstrapRepairService.repairAdminMembership(opts));

        // Step 7: Bootstrap default tenant roles/permissions/menus.
        updateProgress(bootstrap, 7, "bootstrap_default_tenant");
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

        // Step 8: Bootstrap System Tenant — platform_admin role + grant + menus
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

    // ── Step 9: Finalize ────────────────────────────────────────────────

    private void finalizeBootstrap(BootstrapEntity bootstrap, CoreBootstrapResult coreResult) {
        updateProgress(bootstrap, 9, "finalize");

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
