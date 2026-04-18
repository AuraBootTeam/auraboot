package com.auraboot.framework.meta.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack integration test for {@code GET /api/meta/models/{code}/capabilities}.
 *
 * <p>Exercises the complete Spring pipeline with real PostgreSQL/Redis,
 * PermissionInterceptor, and Spring Security. Follows the pattern established
 * in {@code PageSchemaKindFullStackIntegrationTest}.
 *
 * <p>Part of P1 virtual model backend plan (Task 5/12).
 */
@DisplayName("ModelCapabilitiesController - Integration Tests (P1 T5)")
class ModelCapabilitiesControllerIntegrationTest extends BaseIntegrationTest {

    private static final String PERMISSION_CODE = "system.model.read";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private MetaModelService metaModelService;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private UserPermissionService userPermissionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setupMvc() {
        grantPermissionToTestRole(
                PERMISSION_CODE,
                "system",
                "model",
                "read",
                "Model Read"
        );
        userPermissionService.evictUserPermissions(getTestUser().getId());

        Filter contextFilter = (request, response, chain) -> {
            try {
                MetaContext.setContext(
                        getTestTenant().getId(),
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        getTestUser().getUserName()
                );
                CustomUserDetails userDetails = new CustomUserDetails(
                        getTestUser().getUserName(),
                        "test-password",
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        AuthorityUtils.createAuthorityList("role_admin"),
                        true, true, true, true
                );
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
                SecurityContextHolder.clearContext();
            }
        };

        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(contextFilter, "/*")
                .build();
    }

    @Test
    @DisplayName("returns normalized virtual read-only capabilities with sortable/filterable whitelist")
    void get_capabilities_for_virtual_model_returns_normalized_whitelist() throws Exception {
        String code = "p1_t5_caps_" + System.currentTimeMillis();
        metaModelService.saveDefinition(ModelDefinition.builder()
                .code(code)
                .displayName("T5 capabilities test")
                .sourceType("namedQuery")
                .sourceRef("queries/t5.sql")
                .primaryKey("id")
                .fields(List.of(
                        FieldDefinition.builder().code("id").dataType("bigint").build(),
                        FieldDefinition.builder().code("name").dataType("string")
                                .sortable(true).filterable(true).build()
                ))
                .capabilities(ModelCapabilities.virtualReadOnly())
                .build());

        mockMvc.perform(get("/api/meta/models/{code}/capabilities", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.list").value(true))
                .andExpect(jsonPath("$.data.detail").value(true))
                .andExpect(jsonPath("$.data.create").value(false))
                .andExpect(jsonPath("$.data.update").value(false))
                .andExpect(jsonPath("$.data.delete").value(false))
                .andExpect(jsonPath("$.data.sort").value(true))
                .andExpect(jsonPath("$.data.filter").value(true))
                .andExpect(jsonPath("$.data.sortableFields", hasItem("name")))
                .andExpect(jsonPath("$.data.filterableFields", hasItem("name")));
    }

    @Test
    @DisplayName("returns 404 when the requested model code does not exist")
    void returns_404_for_unknown_model() throws Exception {
        String unknown = "nonexistent_xyz_" + System.currentTimeMillis();

        mockMvc.perform(get("/api/meta/models/{code}/capabilities", unknown))
                .andExpect(status().isNotFound());
    }

    /**
     * Grants a permission to the test role (idempotent). Pattern adapted from
     * PageSchemaKindFullStackIntegrationTest / TeamScopeControllerIntegrationTest.
     */
    private void grantPermissionToTestRole(String code, String resourceType,
                                           String resourceCode, String action, String name) {
        Permission permission = permissionMapper.findByCode(code);
        if (permission == null) {
            permission = new Permission();
            permission.setPid(UniqueIdGenerator.generate());
            permission.setCode(code);
            permission.setName(name);
            permission.setResourceType(resourceType);
            permission.setResourceCode(resourceCode);
            permission.setAction(action);
            permission.setSource("manual");
            permission.setStatus("active");
            permission.setDeletedFlag(false);
            permission.setTenantId(getTestTenant().getId());
            permission.setCreatedAt(Instant.now());
            permission.setUpdatedAt(Instant.now());
            permissionMapper.insert(permission);
        }

        boolean notAssigned = rolePermissionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RolePermission>()
                        .eq(RolePermission::getRoleId, getTestRole().getId())
                        .eq(RolePermission::getPermissionId, permission.getId())
                        .eq(RolePermission::getDeletedFlag, false)
        ).isEmpty();

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
