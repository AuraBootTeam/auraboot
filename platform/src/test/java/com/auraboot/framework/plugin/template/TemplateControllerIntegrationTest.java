package com.auraboot.framework.plugin.template;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for TemplateController and TemplateRegistry.
 *
 * <p>Tests the complete template workflow: list -> preview -> install,
 * plus TemplateRegistry resolution logic.</p>
 *
 * <p>Requires real PostgreSQL, real Redis. Template directories must exist
 * on disk under {@code plugins/templates/}.</p>
 */
@Slf4j
@DisplayName("TemplateController Integration Test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TemplateControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TemplateRegistry templateRegistry;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private UserPermissionService userPermissionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        grantPluginPermission();

        Filter metaContextFilter = new Filter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
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
            }
        };

        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(metaContextFilter, "/*")
                .build();
    }

    // ==================== TemplateRegistry Unit-Level Tests ====================

    @Test
    @Order(1)
    @DisplayName("TemplateRegistry resolves known template to absolute path")
    void testResolveKnownTemplate() {
        String path = templateRegistry.resolveAbsolutePath("crm-quick-start");
        assertThat(path).isNotNull();
        assertThat(path).contains("plugins/templates/crm-quick-start");
        assertThat(Paths.get(path).isAbsolute()).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("TemplateRegistry returns null for unknown template")
    void testResolveUnknownTemplate() {
        String path = templateRegistry.resolveAbsolutePath("nonexistent-template-xyz");
        assertThat(path).isNull();
    }

    @Test
    @Order(3)
    @DisplayName("TemplateRegistry lists all built-in templates")
    void testListAllTemplates() {
        List<TemplateRegistry.TemplateDef> templates = templateRegistry.listAll();
        assertThat(templates).isNotEmpty();
        assertThat(templates).hasSizeGreaterThanOrEqualTo(5);

        List<String> ids = templates.stream().map(TemplateRegistry.TemplateDef::id).toList();
        assertThat(ids).contains("crm-quick-start", "project-management", "hr-essentials");

        // Verify each template has required fields
        for (TemplateRegistry.TemplateDef def : templates) {
            assertThat(def.id()).isNotBlank();
            assertThat(def.name()).isNotBlank();
            assertThat(def.relativePath()).isNotBlank();
            assertThat(def.namespace()).isNotBlank();
        }
    }

    @Test
    @Order(4)
    @DisplayName("TemplateRegistry getTemplate returns definition for known template")
    void testGetTemplateKnown() {
        TemplateRegistry.TemplateDef def = templateRegistry.getTemplate("crm-quick-start");
        assertThat(def).isNotNull();
        assertThat(def.id()).isEqualTo("crm-quick-start");
        assertThat(def.name()).isEqualTo("CRM Quick Start");
        assertThat(def.namespace()).isEqualTo("tcrm");
    }

    @Test
    @Order(5)
    @DisplayName("TemplateRegistry getTemplate returns null for unknown template")
    void testGetTemplateUnknown() {
        TemplateRegistry.TemplateDef def = templateRegistry.getTemplate("does-not-exist");
        assertThat(def).isNull();
    }

    // ==================== Controller Endpoint Tests ====================

    @Test
    @Order(10)
    @DisplayName("GET /api/templates returns template list with correct structure")
    void testListTemplatesEndpoint() throws Exception {
        mockMvc.perform(get("/api/templates")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$.data[0].id").isNotEmpty())
                .andExpect(jsonPath("$.data[0].name").isNotEmpty())
                .andExpect(jsonPath("$.data[0].relativePath").isNotEmpty())
                .andExpect(jsonPath("$.data[0].namespace").isNotEmpty());
    }

    @Test
    @Order(11)
    @DisplayName("GET /api/templates/{id}/preview returns preview for valid template")
    void testPreviewValidTemplate() throws Exception {
        mockMvc.perform(get("/api/templates/crm-quick-start/preview")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importId").isNotEmpty())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.changes").isMap())
                .andExpect(jsonPath("$.changes").isNotEmpty());
    }

    @Test
    @Order(12)
    @DisplayName("GET /api/templates/{id}/preview returns 404 for unknown template")
    void testPreviewUnknownTemplate() throws Exception {
        mockMvc.perform(get("/api/templates/nonexistent-xyz/preview")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(13)
    @DisplayName("POST /api/templates/{id}/install returns 404 for unknown template")
    void testInstallUnknownTemplate() throws Exception {
        mockMvc.perform(post("/api/templates/nonexistent-xyz/install")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(20)
    @DisplayName("POST /api/templates/{id}/install installs template successfully")
    void testInstallTemplate() throws Exception {
        mockMvc.perform(post("/api/templates/crm-quick-start/install")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.importId").isNotEmpty())
                .andExpect(jsonPath("$.resourceCounts").isMap());
    }

    @Test
    @Order(21)
    @DisplayName("POST /api/templates/{id}/install accepts conflictStrategy override")
    void testInstallWithConflictStrategy() throws Exception {
        // Install with explicit SKIP strategy
        String body = objectMapper.writeValueAsString(
                java.util.Map.of("conflictStrategy", "SKIP"));

        mockMvc.perform(post("/api/templates/hr-essentials/install")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ==================== Helper Methods ====================

    /**
     * Grant plugin.plugin.manage permission to test user's role,
     * required by @RequirePermission on the install endpoint.
     */
    private void grantPluginPermission() {
        String permCode = "PLUGIN.plugin.manage";

        Permission perm = permissionMapper.findByCode(permCode);
        if (perm == null) {
            perm = new Permission();
            perm.setPid(UniqueIdGenerator.generate());
            perm.setCode(permCode);
            perm.setName("Plugin Management");
            perm.setResourceType("plugin");
            perm.setResourceCode("plugin");
            perm.setAction("manage");
            perm.setSource("manual");
            perm.setStatus("active");
            perm.setDeletedFlag(false);
            perm.setTenantId(getTestTenant().getId());
            perm.setCreatedAt(Instant.now());
            perm.setUpdatedAt(Instant.now());
            permissionMapper.insert(perm);
        }

        RolePermission rp = new RolePermission();
        rp.setPid(UniqueIdGenerator.generate());
        rp.setRoleId(getTestRole().getId());
        rp.setPermissionId(perm.getId());
        rp.setGrantType("grant");
        rp.setStatus("active");
        rp.setDeletedFlag(false);
        rp.setTenantId(getTestTenant().getId());
        rp.setCreatedAt(Instant.now());
        rp.setUpdatedAt(Instant.now());
        rolePermissionMapper.insert(rp);

        userPermissionService.evictUserPermissions(getTestUser().getId());
    }
}
