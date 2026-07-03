package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.plugin.service.BuiltinPluginImportService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.saas.config.mapper.SystemConfigMapper;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BootstrapRepairServiceTenantBootstrapTest {

    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private SystemConfigMapper systemConfigMapper;
    @Mock
    private UserService userService;
    @Mock
    private TenantService tenantService;
    @Mock
    private TenantMemberService tenantMemberService;
    @Mock
    private BuiltinPluginImportService builtinPluginImportService;
    @Mock
    private RoleService roleService;
    @Mock
    private RoleMapper roleMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private MenuMapper menuMapper;
    @Mock
    private TenantBootstrapService tenantBootstrapService;

    @InjectMocks
    private BootstrapRepairService service;

    @AfterEach
    void clearMetaContext() {
        MetaContext.clear();
    }

    @Test
    void repairBusinessTenantBootstrapRunsTemplateBootstrapBeforePluginImportCanCreateModelPermissions() {
        User admin = new User();
        admin.setId(42L);
        admin.setPid("usr-admin");

        Tenant tenant = new Tenant();
        tenant.setId(7L);
        tenant.setName("Acme");

        when(userService.findByEmail("admin@auraboot.com")).thenReturn(admin);
        when(tenantService.findByName("Acme")).thenReturn(tenant);
        when(roleService.findByTenantId(7L)).thenReturn(List.of());
        when(tenantBootstrapService.bootstrapTenant(7L, 42L))
            .thenReturn(TenantBootstrapService.BootstrapResult.success(3, 0, 80, 12));

        RepairStepResult result = service.repairBusinessTenantBootstrap(options());

        assertThat(result.status()).isEqualTo(RepairStepResult.Status.REPAIRED);
        verify(tenantBootstrapService).bootstrapTenant(7L, 42L);
    }

    @Test
    void orderedStepsRunTenantBootstrapBeforeBuiltinPluginImport() {
        assertThat(BootstrapRepairService.ORDERED_STEPS)
            .containsSubsequence(
                BootstrapRepairService.STEP_BUSINESS_TENANT_BOOTSTRAP,
                BootstrapRepairService.STEP_BUILTIN_PLUGINS
            );
    }

    @Test
    void repairBusinessTenantBootstrapIsNoOpWhenPlatformBaselineRolesPresent() {
        User admin = new User();
        admin.setId(42L);
        admin.setPid("usr-admin");

        Tenant tenant = new Tenant();
        tenant.setId(7L);
        tenant.setName("Acme");

        when(userService.findByEmail("admin@auraboot.com")).thenReturn(admin);
        when(tenantService.findByName("Acme")).thenReturn(tenant);
        // A correctly bootstrapped platform-baseline tenant (post-#1167): tenant_admin + tenant_member.
        // The stale required set {tenant_admin, operator, viewer} would have mis-flagged this as a
        // "partial template roles" error; the corrected set treats it as already present (no-op).
        when(roleService.findByTenantId(7L)).thenReturn(List.of(role("tenant_admin"), role("tenant_member")));

        RepairStepResult result = service.repairBusinessTenantBootstrap(options());

        assertThat(result.status()).isEqualTo(RepairStepResult.Status.PRESENT);
        verify(tenantBootstrapService, never()).bootstrapTenant(anyLong(), anyLong());
    }

    private static Role role(String code) {
        Role role = new Role();
        role.setCode(code);
        return role;
    }

    private static BootstrapRepairService.RepairOptions options() {
        return BootstrapRepairService.RepairOptions.of(
            "admin@auraboot.com",
            "Test2026x",
            "Admin",
            "Acme",
            "single",
            "http://localhost:6443",
            true
        );
    }
}
