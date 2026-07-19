package com.auraboot.framework.permission.controller;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real HTTP boundary coverage for permission policy writes.
 */
@DisplayName("PermissionMatrixController policy API")
class PermissionMatrixPolicyControllerIT extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private PermissionMapper permissionMapper;
    @Autowired
    private RolePermissionMapper rolePermissionMapper;
    @Autowired
    private RoleMapper roleMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private UserPermissionService userPermissionService;
    @Autowired
    private AdminRoleChecker adminRoleChecker;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void grantPolicyManageAccess() {
        grantTenantAdminRoleToTestUser();
        grantToTestRole(MetaPermission.PERMISSION_MANAGE);
        userPermissionService.evictUserPermissions(getTestUser().getId());
    }

    @Test
    @DisplayName("PUT policy accepts Rule Center field sources from the permission fact schema")
    void putPolicyAcceptsFactCatalogFieldThroughHttp() throws Exception {
        Permission permission = insertPolicyPermission("accept-" + System.nanoTime());
        RolePermission binding = bindRolePermission(permission);

        mvc().perform(put(policyUrl(permission))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyPayload("data.wd_req_days")))
                .andExpect(status().isOk());

        JsonNode conditions = conditionsById(binding.getId());
        assertThat(conditions
                .at("/dynamicAbac/ruleBinding/decisionBinding/inputMappings/0/source/path")
                .asText()).isEqualTo("data.wd_req_days");
    }

    @Test
    @DisplayName("PUT policy rejects hand-written Rule Center field sources outside the fact schema")
    void putPolicyRejectsOutOfCatalogFieldThroughHttpAndDoesNotPersist() throws Exception {
        Permission permission = insertPolicyPermission("reject-" + System.nanoTime());
        RolePermission binding = bindRolePermission(permission);

        mvc().perform(put(policyUrl(permission))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyPayload("data.secret")))
                .andExpect(status().isBadRequest());

        assertThat(conditionsById(binding.getId())).isNull();
    }

    @Test
    @DisplayName("PUT policy rejects masked Rule Center field sources through HTTP")
    void putPolicyRejectsMaskedFieldThroughHttpAndDoesNotPersist() throws Exception {
        Permission permission = insertPolicyPermission(
                "reject-masked-" + System.nanoTime(),
                List.of(Map.of(
                        "scope", "record",
                        "path", "data.salary",
                        "masked", true)));
        RolePermission binding = bindRolePermission(permission);

        mvc().perform(put(policyUrl(permission))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyPayload("data.salary")))
                .andExpect(status().isBadRequest());

        assertThat(conditionsById(binding.getId())).isNull();
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

    private String policyUrl(Permission permission) {
        return "/api/permissions/matrix/" + getTestRole().getPid() + "/policy/" + permission.getPid();
    }

    private Permission insertPolicyPermission(String suffix) throws Exception {
        return insertPolicyPermission(suffix, List.of(Map.of(
                "scope", "record",
                "path", "data.wd_req_days")));
    }

    private Permission insertPolicyPermission(String suffix, List<Map<String, Object>> fields) throws Exception {
        Permission permission = new Permission();
        permission.setPid(UniqueIdGenerator.generate());
        permission.setTenantId(getTestTenant().getId());
        permission.setCode("model.wd_leave_request.permission_policy_api_" + suffix);
        permission.setName("Permission ABAC API " + suffix);
        permission.setResourceType("model");
        permission.setResourceCode("wd_leave_request");
        permission.setAction("approve");
        permission.setSource("integration_test");
        permission.setStatus("active");
        permission.setDeletedFlag(false);
        permission.setPolicySchema(Map.of(
                "dynamicAbac", Map.of(
                        "type", "rule-center",
                        "fieldCatalogModelCode", "wd_leave_request",
                        "fields", fields)));
        permission.setCreatedAt(Instant.now());
        permission.setUpdatedAt(Instant.now());
        permissionMapper.insert(permission);
        assertThat(permission.getId()).isNotNull();
        return permission;
    }

    private RolePermission bindRolePermission(Permission permission) {
        RolePermission rp = new RolePermission();
        rp.setPid(UniqueIdGenerator.generate());
        rp.setTenantId(getTestTenant().getId());
        rp.setRoleId(getTestRole().getId());
        rp.setPermissionId(permission.getId());
        rp.setGrantType("grant");
        rp.setStatus("active");
        rp.setDeletedFlag(false);
        rp.setCreatedAt(Instant.now());
        rp.setUpdatedAt(Instant.now());
        rolePermissionMapper.insert(rp);
        assertThat(rp.getId()).isNotNull();
        return rp;
    }

    private String policyPayload(String fieldPath) throws JsonProcessingException {
        return objectMapper.writeValueAsString(Map.of(
                "dynamicAbac", Map.of(
                        "ruleBinding", Map.of(
                                "bindingKind", "DECISION_REF",
                                "decisionBinding", Map.of(
                                        "decisionCode", "permission_leave_guard",
                                        "inputMappings", List.of(Map.of(
                                                "input", "days",
                                                "source", Map.of(
                                                        "kind", "FIELD",
                                                        "scope", "record",
                                                        "path", fieldPath))))))));
    }

    private JsonNode conditionsById(Long rolePermissionId) {
        try {
            applyTestMetaContext();
            RolePermission binding = rolePermissionMapper.selectById(rolePermissionId);
            return binding == null || binding.getConditions() == null
                    ? null
                    : objectMapper.valueToTree(binding.getConditions());
        } finally {
            MetaContext.clear();
        }
    }

    private void grantTenantAdminRoleToTestUser() {
        Long tenantId = getTestTenant().getId();
        Long memberId = getTestTenantMember().getId();
        Role role = roleMapper.findByTenantIdAndCode(tenantId, RoleCodes.TENANT_ADMIN);
        if (role == null) {
            role = new Role();
            role.setId(IdWorker.getId());
            role.setPid(UniqueIdGenerator.generate());
            role.setTenantId(tenantId);
            role.setCode(RoleCodes.TENANT_ADMIN);
            role.setName("Tenant Admin");
            role.setType("tenant");
            role.setScopeType("tenant");
            role.setStatus("active");
            role.setIsDefault(false);
            role.setIsSystem(false);
            role.setDeletedFlag(false);
            role.setPriority(0);
            role.setCreatedAt(Instant.now());
            role.setUpdatedAt(Instant.now());
            roleMapper.insert(role);
        }
        UserRole existing = userRoleMapper.findByMemberIdAndRoleIdAndTenantId(memberId, role.getId(), tenantId);
        if (existing == null) {
            UserRole userRole = new UserRole();
            userRole.setId(IdWorker.getId());
            userRole.setPid(UniqueIdGenerator.generate());
            userRole.setMemberId(memberId);
            userRole.setRoleId(role.getId());
            userRole.setTenantId(tenantId);
            userRole.setAssignType("direct");
            userRole.setStatus("active");
            userRole.setDeletedFlag(false);
            userRole.setCreatedAt(Instant.now());
            userRole.setUpdatedAt(Instant.now());
            userRoleMapper.insert(userRole);
        }
        adminRoleChecker.invalidateAll();
    }

    private void grantToTestRole(String code) {
        Permission permission = permissionMapper.findByCode(code);
        if (permission == null) {
            String[] parts = code.split("\\.");
            permission = new Permission();
            permission.setPid(UniqueIdGenerator.generate());
            permission.setCode(code);
            permission.setName(code);
            permission.setResourceType(parts.length > 0 ? parts[0] : "system");
            permission.setResourceCode(parts.length > 1 ? parts[1] : code);
            permission.setAction(parts.length > 2 ? parts[2] : "read");
            permission.setSource("manual");
            permission.setStatus("active");
            permission.setDeletedFlag(false);
            permission.setTenantId(getTestTenant().getId());
            permission.setCreatedAt(Instant.now());
            permission.setUpdatedAt(Instant.now());
            permissionMapper.insert(permission);
        }
        boolean notAssigned = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>()
                        .eq(RolePermission::getRoleId, getTestRole().getId())
                        .eq(RolePermission::getPermissionId, permission.getId())
                        .eq(RolePermission::getDeletedFlag, false)).isEmpty();
        if (notAssigned) {
            RolePermission rp = new RolePermission();
            rp.setPid(UniqueIdGenerator.generate());
            rp.setRoleId(getTestRole().getId());
            rp.setPermissionId(permission.getId());
            rp.setGrantType("grant");
            rp.setStatus("active");
            rp.setDeletedFlag(false);
            rp.setTenantId(getTestTenant().getId());
            rp.setCreatedAt(Instant.now());
            rp.setUpdatedAt(Instant.now());
            rolePermissionMapper.insert(rp);
        }
    }
}
