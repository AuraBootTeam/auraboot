package com.auraboot.framework.permission.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Real-stack permission-enforcement matrix for {@code /api/permission/capabilities}, exercising the
 * actual {@code PermissionInterceptor} (not a mocked deny). Verifies the no-permission deny path the
 * matrix-vs-capability authoring tests don't cover: GET requires ROLE_READ, PUT requires ROLE_MANAGE,
 * and a role lacking the code is rejected with 403 (not silently allowed).
 *
 * <p>Each test starts from a clean slate (both codes revoked + permission cache evicted), grants only
 * what it needs, evicts again, then asserts via the full Spring pipeline.
 */
@DisplayName("CapabilityController — permission enforcement matrix")
class CapabilityControllerEnforcementIT extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private PermissionMapper permissionMapper;
    @Autowired
    private RolePermissionMapper rolePermissionMapper;
    @Autowired
    private UserPermissionService userPermissionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AdminRoleChecker adminRoleChecker;

    @BeforeEach
    void cleanSlate() {
        grantTenantAdminRoleToTestUser();
        // Register the required codes before revoking grants. An unregistered code exercises
        // the distinct tenant-admin bootstrap contract, not the registered permission deny path.
        grantToTestRole(MetaPermission.ROLE_READ);
        grantToTestRole(MetaPermission.ROLE_MANAGE);
        revokeFromTestRole(MetaPermission.ROLE_READ);
        revokeFromTestRole(MetaPermission.ROLE_MANAGE);
        userPermissionService.evictPermissionDefinitions(getTestTenant().getId());
        userPermissionService.evictRoleUsers(getTestTenant().getId(), getTestRole().getId());
    }

    private MockMvc mvc() {
        Filter contextFilter = (request, response, chain) -> {
            try {
                applyTestMetaContext();
                CustomUserDetails userDetails = new CustomUserDetails(
                        getTestUser().getUserName(), "test-password",
                        getTestUser().getId(), getTestUser().getPid(),
                        AuthorityUtils.createAuthorityList("role_admin"),
                        true, true, true, true);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
                SecurityContextHolder.clearContext();
            }
        };
        return MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilter(contextFilter, "/*").build();
    }

    private String url() {
        return "/api/permission/capabilities?rolePid=" + getTestRole().getPid();
    }

    @Test
    @DisplayName("GET is denied (403) for a role without org.role.read")
    void getWithoutRoleRead_isForbidden() throws Exception {
        mvc().perform(get(url())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET is allowed (200) once the role has org.role.read")
    void getWithRoleRead_isOk() throws Exception {
        grantToTestRole(MetaPermission.ROLE_READ);
        userPermissionService.evictUserPermissions(getTestUser().getId());
        mvc().perform(get(url())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT is denied (403) for a role without org.role.update (manage)")
    void putWithoutRoleManage_isForbidden() throws Exception {
        // Even granting read does not unlock the manage-gated write.
        grantToTestRole(MetaPermission.ROLE_READ);
        userPermissionService.evictUserPermissions(getTestUser().getId());
        mvc().perform(put(url()).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT is allowed (200) once the role has org.role.update")
    void putWithRoleManage_isOk() throws Exception {
        grantToTestRole(MetaPermission.ROLE_MANAGE);
        userPermissionService.evictUserPermissions(getTestUser().getId());
        mvc().perform(put(url()).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk());
    }

    @Test
    void previewWithoutRoleRead_isForbidden() throws Exception {
        mvc().perform(post("/api/permission/capabilities/preview?rolePid=" + getTestRole().getPid())
                .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isForbidden());
    }

    @Test
    void previewWithRoleRead_isOkWithoutManage() throws Exception {
        grantToTestRole(MetaPermission.ROLE_READ);
        userPermissionService.evictUserPermissions(getTestUser().getId());
        mvc().perform(post("/api/permission/capabilities/preview?rolePid=" + getTestRole().getPid())
                .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A non-admin role reader can inspect grants and scope but cannot write either")
    void roleReaderCanInspectMatrixWithoutPermissionManagement() throws Exception {
        // Register then revoke the management code so this cannot pass through the
        // tenant-admin bootstrap behavior for an unregistered permission.
        grantToTestRole(MetaPermission.PERMISSION_MANAGE);
        revokeFromTestRole(MetaPermission.PERMISSION_MANAGE);
        grantToTestRole(MetaPermission.ROLE_READ);
        jdbcTemplate.update("""
                UPDATE ab_user_role SET deleted_flag = TRUE, status = 'disabled'
                WHERE tenant_id = ? AND member_id = ? AND role_id IN (
                    SELECT id FROM ab_role WHERE tenant_id = ? AND code = ?)
                """, getTestTenant().getId(), getTestTenantMember().getId(),
                getTestTenant().getId(), RoleCodes.TENANT_ADMIN);
        adminRoleChecker.invalidateAll();
        userPermissionService.evictUserPermissions(getTestUser().getId());
        assertFalse(adminRoleChecker.hasRole(getTestTenant().getId(), getTestUser().getId(),
                RoleCodes.TENANT_ADMIN), "The reader fixture must not retain tenant-admin privileges");

        String matrixUrl = "/api/permissions/matrix/" + getTestRole().getPid();
        mvc().perform(get(url())).andExpect(status().isOk());
        mvc().perform(get(matrixUrl)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modules").isArray());
        mvc().perform(get(matrixUrl + "/default-scope")).andExpect(status().isOk());
        mvc().perform(put(url()).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isForbidden());
        mvc().perform(put(matrixUrl + "/batch")
                .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isForbidden());
        mvc().perform(put(matrixUrl + "/default-scope")
                .contentType(MediaType.APPLICATION_JSON).content("{\"scopeType\":\"all\"}"))
                .andExpect(status().isForbidden());

        revokeFromTestRole(MetaPermission.ROLE_READ);
        userPermissionService.evictUserPermissions(getTestUser().getId());
        mvc().perform(get(matrixUrl)).andExpect(status().isForbidden());
        mvc().perform(get(matrixUrl + "/default-scope")).andExpect(status().isForbidden());
    }

    private void grantTenantAdminRoleToTestUser() {
        Long tenantId = getTestTenant().getId();
        Long memberId = getTestTenantMember().getId();
        Long roleId = jdbcTemplate.query("""
                SELECT id FROM ab_role
                WHERE tenant_id = ? AND code = ? AND (deleted_flag = FALSE OR deleted_flag IS NULL)
                ORDER BY id LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null, tenantId, RoleCodes.TENANT_ADMIN);
        if (roleId == null) {
            roleId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
            jdbcTemplate.update("""
                    INSERT INTO ab_role (
                        id, pid, tenant_id, code, name, type, scope_type, status,
                        is_default, is_system, deleted_flag, priority, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, 'tenant', 'tenant', 'active',
                            FALSE, FALSE, FALSE, 0, NOW(), NOW())
                    """, roleId, UniqueIdGenerator.generate(), tenantId, RoleCodes.TENANT_ADMIN, "Tenant Admin");
        }
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_user_role
                WHERE member_id = ? AND role_id = ? AND tenant_id = ?
                  AND status = 'active' AND (deleted_flag = FALSE OR deleted_flag IS NULL)
                """, Integer.class, memberId, roleId, tenantId);
        if (existing == null || existing == 0) {
            jdbcTemplate.update("""
                    INSERT INTO ab_user_role (
                        id, pid, member_id, role_id, tenant_id, assign_type, status,
                        deleted_flag, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, 'direct', 'active', FALSE, NOW(), NOW())
                    """, com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                    UniqueIdGenerator.generate(), memberId, roleId, tenantId);
        }
        adminRoleChecker.invalidateAll();
    }

    private void grantToTestRole(String code) {
        Permission permission = permissionMapper.findByCode(code);
        if (permission == null) {
            String[] parts = code.split("\\.");
            permission = new Permission();
            permission.setPid(com.auraboot.framework.common.util.UniqueIdGenerator.generate());
            permission.setCode(code);
            permission.setName(code);
            permission.setResourceType(parts.length > 0 ? parts[0] : "system");
            permission.setResourceCode(parts.length > 1 ? parts[1] : code);
            permission.setAction(parts.length > 2 ? parts[2] : "read");
            permission.setSource("manual");
            permission.setStatus("active");
            permission.setDeletedFlag(false);
            permission.setTenantId(getTestTenant().getId());
            permission.setCreatedAt(java.time.Instant.now());
            permission.setUpdatedAt(java.time.Instant.now());
            permissionMapper.insert(permission);
        }
        // Revocation is a logical delete; reactivate the existing fixture row rather than
        // inserting a duplicate against the tenant/role/permission unique constraint.
        jdbcTemplate.update("""
                UPDATE ab_role_permission SET deleted_flag = FALSE, status = 'active', grant_type = 'grant'
                WHERE tenant_id = ? AND role_id = ? AND permission_id = ?
                """, getTestTenant().getId(), getTestRole().getId(), permission.getId());
        boolean notAssigned = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>()
                        .eq(RolePermission::getRoleId, getTestRole().getId())
                        .eq(RolePermission::getPermissionId, permission.getId())
                        .eq(RolePermission::getDeletedFlag, false)).isEmpty();
        if (notAssigned) {
            RolePermission rp = new RolePermission();
            rp.setPid(com.auraboot.framework.common.util.UniqueIdGenerator.generate());
            rp.setRoleId(getTestRole().getId());
            rp.setPermissionId(permission.getId());
            rp.setGrantType("grant");
            rp.setStatus("active");
            rp.setDeletedFlag(false);
            rp.setTenantId(getTestTenant().getId());
            rp.setCreatedAt(java.time.Instant.now());
            rp.setUpdatedAt(java.time.Instant.now());
            rolePermissionMapper.insert(rp);
        }
        userPermissionService.evictPermissionDefinitions(getTestTenant().getId());
        userPermissionService.evictRoleUsers(getTestTenant().getId(), getTestRole().getId());
    }

    private void revokeFromTestRole(String code) {
        applyTestMetaContext();
        Permission permission = permissionMapper.findByCode(code);
        if (permission == null) {
            return;
        }
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, getTestRole().getId())
                .eq(RolePermission::getPermissionId, permission.getId()));
        userPermissionService.evictRoleUsers(getTestTenant().getId(), getTestRole().getId());
    }
}
