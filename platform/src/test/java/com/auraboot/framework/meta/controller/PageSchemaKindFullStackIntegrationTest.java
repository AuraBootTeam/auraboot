package com.auraboot.framework.meta.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack integration test for {@code POST /api/pages} kind validation.
 *
 * <p>Replaces the Plan 3a T7 standaloneSetup compromise.  This test extends
 * {@link BaseIntegrationTest} and exercises the complete Spring pipeline:
 * real PostgreSQL, real Redis, PermissionInterceptor, and Spring Security —
 * satisfying the testing-backend red line that every Controller must have a
 * real-stack IntegrationTest.
 *
 * <p>Auth pattern: per-request servlet filter injects both
 * {@link MetaContext} and {@link SecurityContextHolder} (same pattern used in
 * {@code TeamScopeControllerIntegrationTest}).  The test role is granted the
 * {@code page.page.manage} permission so the interceptor passes, allowing
 * Bean Validation to fire and reject {@code kind=dashboard}.
 */
@DisplayName("PageSchemaController kind validation - Full-stack IT (Plan 3a T7 replacement)")
class PageSchemaKindFullStackIntegrationTest extends BaseIntegrationTest {

    private static final String PERMISSION_CODE = "page.page.manage";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private UserPermissionService userPermissionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        // Grant page.page.manage permission to the test role so PermissionInterceptor passes.
        grantPermissionToTestRole(
                PERMISSION_CODE,
                "page",
                "page",
                "manage",
                "Page Manage"
        );
        userPermissionService.evictUserPermissions(getTestUser().getId());

        // Per-request filter: injects MetaContext + SecurityContextHolder.
        // This is the established pattern (TeamScopeControllerIntegrationTest).
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

    // ── FS-VAL-01 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FS-VAL-01: POST /api/pages with kind=dashboard returns 400 through full pipeline")
    void createPage_withKindDashboard_returns400() throws Exception {
        Map<String, Object> payload = Map.of(
                "pageKey", "test_dashboard_" + System.currentTimeMillis(),
                "name", "Test Dashboard",
                "title", "Test Dashboard Title",
                "kind", "dashboard",
                "blocks", List.of()
        );

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.context.kind", containsString("Invalid kind")));
    }

    // ── FS-VAL-02 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FS-VAL-02: POST /api/pages with kind=list succeeds through full pipeline")
    void createPage_withKindList_succeeds() throws Exception {
        Map<String, Object> payload = Map.of(
                "pageKey", "test_list_" + System.currentTimeMillis(),
                "name", "Test List Page",
                "title", "Test List Page Title",
                "kind", "list",
                "blocks", List.of(Map.of("blockType", "table"))
        );

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andDo(print())
                .andExpect(status().is2xxSuccessful());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Grants a permission to the test role (idempotent — skips if already granted).
     * Pattern adapted from TeamScopeControllerIntegrationTest.
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

        // Check if already assigned to avoid duplicate key errors
        boolean alreadyAssigned = rolePermissionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RolePermission>()
                        .eq(RolePermission::getRoleId, getTestRole().getId())
                        .eq(RolePermission::getPermissionId, permission.getId())
                        .eq(RolePermission::getDeletedFlag, false)
        ).isEmpty();

        if (alreadyAssigned) {
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
