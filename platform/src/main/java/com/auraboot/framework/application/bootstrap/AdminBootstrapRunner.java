package com.auraboot.framework.application.bootstrap;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.plugin.service.BuiltinPluginImportService;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * First-run bootstrap runner for Docker deployments.
 * <p>
 * Only activates when {@code auraboot.bootstrap.enabled=true}
 * (env var {@code AURABOOT_BOOTSTRAP_ENABLED=true}).
 * <p>
 * If the {@code ab_user} table is empty, creates the default admin user,
 * a demo tenant, and bootstraps roles/permissions/menus.
 * If users already exist, logs a skip message and exits.
 */
@Slf4j
@Component
@Order(2) // After PlatformSeedRunner (@Order(1))
@ConditionalOnProperty(name = "auraboot.bootstrap.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String ADMIN_PASSWORD = "Test2026x";
    private static final String ADMIN_DISPLAY_NAME = "Admin";
    private static final String TENANT_NAME = "AuraBoot Demo";

    private final UserMapper userMapper;
    private final UserService userService;
    private final TenantService tenantService;
    private final TenantMemberService tenantMemberService;
    private final TenantBootstrapService tenantBootstrapService;
    private final BuiltinPluginImportService builtinPluginImportService;
    private final SystemConfigService systemConfigService;

    @Override
    public void run(ApplicationArguments args) {
        // Check for real human users (exclude system/agent template users with @system.auraboot.local emails)
        long humanUserCount = userMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>()
                        .notLike("email", "@system.auraboot.local")
        );
        if (humanUserCount > 0) {
            log.info("AdminBootstrapRunner: skipped — {} human user(s) already exist.", humanUserCount);
            return;
        }

        log.info("AdminBootstrapRunner: no users found, creating default admin and tenant...");

        try {
            // 1. Register admin user
            User admin = userService.signUp(ADMIN_EMAIL, ADMIN_PASSWORD, ADMIN_DISPLAY_NAME);
            log.info("AdminBootstrapRunner: admin user created (id={}, pid={})", admin.getId(), admin.getPid());

            // 2. Create default tenant
            Tenant tenant = new Tenant();
            tenant.setPid(UniqueIdGenerator.generate());
            tenant.setName(TENANT_NAME);
            tenant.setDisplayName(TENANT_NAME);
            tenant.setIndustry("technology");
            tenant.setStatus(StatusConstants.ACTIVE);
            tenant.setCreatedBy(admin.getId());
            tenant.setUpdatedBy(admin.getId());
            Tenant createdTenant = tenantService.createTenant(tenant);
            log.info("AdminBootstrapRunner: tenant created (id={}, name={})", createdTenant.getId(), createdTenant.getName());

            // 3. Add admin as tenant member
            TenantMember member = tenantMemberService.addMember(admin.getId(), createdTenant.getId(), StatusConstants.ACTIVE);
            log.info("AdminBootstrapRunner: admin added as tenant member (id={})", member.getId());

            // 4. Set MetaContext so tenant-scoped operations work
            MetaContext.setContext(createdTenant.getId(), admin.getId(), admin.getPid(), admin.getEmail());

            try {
                // 5. Bootstrap tenant RBAC (roles, permissions, menus)
                TenantBootstrapService.BootstrapResult result = tenantBootstrapService.bootstrapTenant(
                        createdTenant.getId(), admin.getId());
                if (result.isSuccess()) {
                    log.info("AdminBootstrapRunner: tenant bootstrap complete — {} roles, {} menus, {} permissions in {}ms",
                            result.getRolesCreated(), result.getMenusCreated(),
                            result.getPermissionsAssigned(), result.getDurationMs());
                } else {
                    log.error("AdminBootstrapRunner: tenant bootstrap failed — {}", result.getMessage());
                }

                // 6. Import built-in plugins (non-critical)
                try {
                    builtinPluginImportService.importForTenant(createdTenant.getId(), admin.getId());
                    log.info("AdminBootstrapRunner: built-in plugins imported.");
                } catch (Exception e) {
                    log.warn("AdminBootstrapRunner: built-in plugin import failed (can be done later): {}", e.getMessage());
                }
            } finally {
                MetaContext.clear();
            }

            // 7. Mark the system as initialized so frontend root loader stops
            //    redirecting to /setup. Without this, SystemConfigService.isInitialized()
            //    returns false and /api/bootstrap/status reports initialized=false
            //    even though the admin user and tenant already exist.
            systemConfigService.initialize(
                    SystemConfigKeys.SYSTEM_INITIALIZED,
                    "true",
                    "system",
                    "boolean",
                    "Set by AdminBootstrapRunner after first-run admin+tenant creation",
                    true);
            log.info("AdminBootstrapRunner: system.initialized flag set.");

            log.info("AdminBootstrapRunner: first-run bootstrap complete. Login with {} / {}", ADMIN_EMAIL, ADMIN_PASSWORD);

        } catch (Exception e) {
            log.error("AdminBootstrapRunner: bootstrap failed", e);
            throw new RuntimeException("Admin bootstrap failed — check logs for details", e);
        }
    }
}
