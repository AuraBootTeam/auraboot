package com.auraboot.framework.permission.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.Permission;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @BeforeEach
    void cleanSlate() {
        revokeFromTestRole(MetaPermission.ROLE_READ);
        revokeFromTestRole(MetaPermission.ROLE_MANAGE);
        userPermissionService.evictUserPermissions(getTestUser().getId());
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
    }

    private void revokeFromTestRole(String code) {
        Permission permission = permissionMapper.findByCode(code);
        if (permission == null) {
            return;
        }
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, getTestRole().getId())
                .eq(RolePermission::getPermissionId, permission.getId()));
    }
}
