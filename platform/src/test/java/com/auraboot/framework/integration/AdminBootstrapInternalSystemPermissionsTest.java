package com.auraboot.framework.integration;

import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.SystemPermissionInitializer;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import com.auraboot.framework.tenant.service.TenantBootstrapService.BootstrapResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that tenant bootstrap creates the internal system model permissions
 * (e.g. {@code model.sys_user.read}) that are required by DynamicController's
 * {@code @RequirePermission("model.{pageKey}.read")} check when the frontend
 * MemberPicker / SmartSelect widgets resolve user references through
 * {@code GET /api/dynamic/sys_user/list}.
 *
 * <p>Before this fix, a freshly bootstrapped tenant left the admin user unable
 * to open any form that references {@code sys_user} — the SmartSelect returned
 * 403 because {@code model.sys_user.read} was never created (sys_user is an
 * internal system model and bypasses both SystemPermissionInitializer's
 * {@code system.*} hierarchy and AutoPermissionAssignmentService's model-publish
 * flow).
 *
 * <p>Privilege boundary: TENANT_ADMIN and DEVELOPER receive the read
 * permission; VIEWER must not, to avoid leaking the user directory through the
 * "viewer gets every .read leaf" template rule.
 */
@DisplayName("Admin bootstrap grants internal system model permissions (sys_user etc.)")
class AdminBootstrapInternalSystemPermissionsTest extends BaseIntegrationTest {

    @Autowired
    private TenantBootstrapService tenantBootstrapService;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private RoleService roleService;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private UserPermissionService userPermissionService;

    @Test
    @DisplayName("bootstrap creates model.sys_user.read and grants it to tenant_admin")
    void bootstrap_grants_sys_user_read_to_tenant_admin() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        BootstrapResult result = tenantBootstrapService.bootstrapTenant(tenantId, userId);
        assertThat(result.isSuccess()).isTrue();

        // 1. The permission row exists at all.
        Permission sysUserRead = permissionMapper.findByCode("model.sys_user.read");
        assertThat(sysUserRead)
            .as("model.sys_user.read must be created during bootstrap")
            .isNotNull();
        assertThat(sysUserRead.getResourceType()).isEqualTo("model");
        assertThat(sysUserRead.getResourceCode()).isEqualTo("sys_user");
        assertThat(sysUserRead.getAction()).isEqualTo("read");
        assertThat(sysUserRead.getLevel()).isEqualTo(3);

        // 2. tenant_admin has it — this is what unblocks MemberPicker/SmartSelect.
        Role tenantAdmin = findRole(tenantId, "tenant_admin");
        assertThat(tenantAdmin)
            .as("tenant_admin role must exist after bootstrap")
            .isNotNull();
        assertThat(rolePermissionMapper.findByRoleAndPermission(tenantAdmin.getId(), sysUserRead.getId()))
            .as("tenant_admin must be granted model.sys_user.read")
            .isNotNull();
    }

    @Test
    @DisplayName("viewer role does NOT receive model.sys_user.read (PII boundary)")
    void bootstrap_does_not_grant_sys_user_read_to_viewer() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        BootstrapResult result = tenantBootstrapService.bootstrapTenant(tenantId, userId);
        assertThat(result.isSuccess()).isTrue();

        Permission sysUserRead = permissionMapper.findByCode("model.sys_user.read");
        assertThat(sysUserRead).isNotNull();

        Role viewer = findRole(tenantId, "viewer");
        if (viewer == null) {
            // default-bootstrap.json may or may not materialize a viewer role;
            // if it does not exist there is nothing to assert on.
            return;
        }

        assertThat(rolePermissionMapper.findByRoleAndPermission(viewer.getId(), sysUserRead.getId()))
            .as("viewer must NOT be granted model.sys_user.read to avoid leaking the user directory")
            .isNull();
    }

    @Test
    @DisplayName("SystemPermissionInitializer exposes the internal model codes")
    void internal_system_model_codes_include_sys_user_read() {
        Set<String> codes = SystemPermissionInitializer.internalSystemModelActionCodes();
        assertThat(codes)
            .as("bootstrap API contract — sys_user read must be in the internal allowlist")
            .contains("model.sys_user.read");

        Set<String> whitelist = SystemPermissionInitializer.internalSystemModelRoleWhitelist();
        assertThat(whitelist)
            .as("tenant_admin must be permitted to receive internal system model permissions")
            .contains("tenant_admin");
        assertThat(whitelist)
            .as("viewer must NOT be permitted to receive internal system model permissions")
            .doesNotContain("viewer");
    }

    private Role findRole(Long tenantId, String code) {
        List<Role> roles = roleService.findByTenantId(tenantId);
        return roles.stream().filter(r -> code.equals(r.getCode())).findFirst().orElse(null);
    }
}
