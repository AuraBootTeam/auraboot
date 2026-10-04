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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import com.auraboot.framework.plugin.validation.PageSchemaRenderProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Set;
import java.util.UUID;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
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
 * Bean Validation to allow V3 {@code kind=dashboard} pages.
 */
@Import(PageSchemaKindFullStackIntegrationTest.StorefrontHost.class)
@DisplayName("PageSchemaController kind validation - Full-stack IT")
class PageSchemaKindFullStackIntegrationTest extends BaseIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class StorefrontHost {
        @Bean
        PageSchemaRenderProfile storefrontAuthoringProfile() {
            return new PageSchemaRenderProfile("storefront-authoring", Set.of("plp"),
                    Set.of("search-bar", "facet-panel", "product-grid", "pagination"));
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.auraboot.framework.meta.mapper.PageSchemaMapper pageSchemaMapper;

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
        grantCommittedPermissionToTestRole(
                PERMISSION_CODE,
                "page",
                "page",
                "manage",
                "Page Manage"
        );
        grantCommittedPermissionToTestRole("page.page.read", "page", "page", "read", "Page Read");
        userPermissionService.evictUserPermissions(getTestUser().getId());

        // Per-request filter: injects MetaContext + SecurityContextHolder.
        // This is the established pattern (TeamScopeControllerIntegrationTest).
        Filter contextFilter = (request, response, chain) -> {
            try {
                applyTestMetaContext();
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
    @DisplayName("FS-VAL-01: POST /api/pages with kind=dashboard succeeds through full pipeline")
    void createPage_withKindDashboard_succeeds() throws Exception {
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
                .andExpect(status().is2xxSuccessful());
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
                // Block must carry a stable id + blockType under the structural
                // integrity guard (PageSchemaBlockStructureValidator).
                "blocks", List.of(Map.of("id", "blk_table_1", "blockType", "table"))
        );

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andDo(print())
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void registeredProfileCreateAndPartialUpdatePreserveCanonicalStorageAndRejectEscape() throws Exception {
        String key = "storefront_authoring_" + UUID.randomUUID().toString().replace("-", "");
        List<Object> blocks = List.of(Map.of("id", "products", "blockType", "product-grid"));
        Map<String, Object> payload = Map.of("pageKey", key, "name", key,
                "title", "Storefront Products", "kind", "plp", "profile", "storefront-authoring",
                "schemaVersion", 4, "layout", Map.of("type", "stack"), "blocks", blocks);
        String response = mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        String pid = objectMapper.readTree(response).path("data").path("pid").asText();
        assertThat(pid).isNotBlank();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT kind FROM ab_page_schema WHERE pid = ? AND deleted_flag = false", String.class, pid))
                .isEqualTo("plp");
        assertThat(objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT blocks::text FROM ab_page_schema WHERE pid = ? AND deleted_flag = false", String.class, pid)))
                .isEqualTo(objectMapper.valueToTree(blocks));
        // Missing profile on a partial update must resolve against the saved profile.
        mockMvc.perform(put("/api/pages/{pid}", pid).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("kind", "plp", "title", "Updated Products"))))
                .andExpect(status().is2xxSuccessful());
        assertThat(jdbcTemplate.queryForObject("SELECT title::text FROM ab_page_schema WHERE pid = ?", String.class, pid))
                .contains("Updated Products");
        String versionResponse = mockMvc.perform(post("/api/pages/{pid}/versions", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("operation", "update", "description", "Profile snapshot"))))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        var version = objectMapper.readTree(versionResponse).path("data");
        assertThat(version.path("snapshot").path("profile").asText()).isEqualTo("storefront-authoring");
        assertThat(version.path("snapshot").path("schemaVersion").asInt()).isEqualTo(4);
        long historyId = version.path("id").asLong();
        assertThat(historyId).isPositive();
        mockMvc.perform(put("/api/pages/{pid}", pid).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("kind", "list", "profile", "admin", "schemaVersion", 3,
                                "blocks", List.of(Map.of("id", "table", "blockType", "table"))))))
                .andExpect(status().is2xxSuccessful());
        assertThat(jdbcTemplate.queryForObject("SELECT profile FROM ab_page_schema WHERE pid = ?", String.class, pid))
                .isEqualTo("admin");
        assertThat(jdbcTemplate.queryForObject("SELECT kind FROM ab_page_schema WHERE pid = ?", String.class, pid))
                .isEqualTo("list");
        assertThat(jdbcTemplate.queryForObject("SELECT schema_version FROM ab_page_schema WHERE pid = ?", Integer.class, pid))
                .isEqualTo(3);
        assertThat(objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT blocks::text FROM ab_page_schema WHERE pid = ?", String.class, pid)))
                .isEqualTo(objectMapper.valueToTree(List.of(Map.of("id", "table", "blockType", "table"))));
        mockMvc.perform(post("/api/pages/{pid}/rollback/{historyId}", pid, historyId).param("reason", "Restore profile"))
                .andExpect(status().is2xxSuccessful());
        assertThat(jdbcTemplate.queryForObject("SELECT profile FROM ab_page_schema WHERE pid = ?", String.class, pid))
                .isEqualTo("storefront-authoring");
        assertThat(jdbcTemplate.queryForObject("SELECT schema_version FROM ab_page_schema WHERE pid = ?", Integer.class, pid))
                .isEqualTo(4);
        String before = jdbcTemplate.queryForObject(
                "SELECT row_to_json(p)::text FROM ab_page_schema p WHERE pid = ? AND deleted_flag = false", String.class, pid);
        for (Map<String, Object> escape : List.of(Map.<String, Object>of("profile", "admin"),
                Map.<String, Object>of("profile", "unregistered"),
                Map.<String, Object>of("blocks", List.of(Map.of("id", "alien", "blockType", "table"))),
                Map.<String, Object>of("blocks", List.of("not-a-block")))) {
            mockMvc.perform(put("/api/pages/{pid}", pid).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(escape)))
                    .andExpect(status().is4xxClientError());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT row_to_json(p)::text FROM ab_page_schema p WHERE pid = ? AND deleted_flag = false", String.class, pid))
                    .isEqualTo(before);
        }
        mockMvc.perform(post("/api/pages/{pid}/publish", pid)).andExpect(status().is2xxSuccessful());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ab_page_schema WHERE pid = ?", String.class, pid))
                .isEqualTo("published");
        String runtimeResponse = mockMvc.perform(get("/api/pages/runtime/{pid}", pid))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        var runtime = objectMapper.readTree(runtimeResponse).path("data");
        assertThat(runtime.path("kind").asText()).isEqualTo("plp");
        assertThat(runtime.path("profile").asText()).isEqualTo("storefront-authoring");
        assertThat(runtime.path("schemaVersion").asInt()).isEqualTo(4);
        assertThat(runtime.path("blocks")).isEqualTo(objectMapper.valueToTree(blocks));
    }

    @Test
    void emptyRegisteredDraftRemainsEditableButCannotPublish() throws Exception {
        String key = "empty_storefront_" + UUID.randomUUID().toString().replace("-", "");
        String response = mockMvc.perform(post("/api/pages").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("pageKey", key, "name", key,
                                "title", "Empty Storefront", "kind", "plp", "profile", "storefront-authoring",
                                "schemaVersion", 4, "blocks", List.of()))))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        String pid = objectMapper.readTree(response).path("data").path("pid").asText();
        assertThat(pid).isNotBlank();
        String before = jdbcTemplate.queryForObject("SELECT row_to_json(p)::text FROM ab_page_schema p WHERE pid = ?", String.class, pid);
        mockMvc.perform(post("/api/pages/{pid}/publish", pid)).andExpect(status().is4xxClientError());
        assertThat(jdbcTemplate.queryForObject("SELECT row_to_json(p)::text FROM ab_page_schema p WHERE pid = ?", String.class, pid))
                .isEqualTo(before);
        mockMvc.perform(put("/api/pages/{pid}", pid).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("blocks", List.of(Map.of("id", "products", "blockType", "product-grid"))))))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(post("/api/pages/{pid}/publish", pid)).andExpect(status().is2xxSuccessful());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ab_page_schema WHERE pid = ?", String.class, pid))
                .isEqualTo("published");
    }

    @Test
    void rollbackDefaultProfileSnapshotClearsRegisteredProfileInDatabase() throws Exception {
        String key = "default_profile_" + UUID.randomUUID().toString().replace("-", "");
        List<Object> originalBlocks = List.of(Map.of("id", "table", "blockType", "table"));
        String response = mockMvc.perform(post("/api/pages").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("pageKey", key, "name", key,
                                "title", "Default Products", "kind", "list", "schemaVersion", 4, "blocks", originalBlocks))))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        String pid = objectMapper.readTree(response).path("data").path("pid").asText();
        assertThat(pid).isNotBlank();
        // Reproduce a nullable legacy row through the mapper, scoped to this freshly created fixture.
        applyTestMetaContext();
        try {
            assertThat(pageSchemaMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.auraboot.framework.meta.entity.PageSchema>()
                            .eq("pid", pid).set("profile", null))).isEqualTo(1);
        } finally {
            MetaContext.clear();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT profile FROM ab_page_schema WHERE pid = ?", String.class, pid)).isNull();
        String versionResponse = mockMvc.perform(post("/api/pages/{pid}/versions", pid).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("operation", "update", "description", "Default profile snapshot"))))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        var version = objectMapper.readTree(versionResponse).path("data");
        assertThat(version.path("snapshot").has("profile")).isTrue();
        assertThat(version.path("snapshot").path("profile").isNull()).isTrue();
        long historyId = version.path("id").asLong();
        assertThat(historyId).isPositive();
        mockMvc.perform(put("/api/pages/{pid}", pid).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("kind", "plp", "profile", "storefront-authoring",
                                "blocks", List.of(Map.of("id", "products", "blockType", "product-grid"))))))
                .andExpect(status().is2xxSuccessful());
        assertThat(jdbcTemplate.queryForObject("SELECT profile FROM ab_page_schema WHERE pid = ?", String.class, pid))
                .isEqualTo("storefront-authoring");
        mockMvc.perform(post("/api/pages/{pid}/rollback/{historyId}", pid, historyId).param("reason", "Restore default profile"))
                .andExpect(status().is2xxSuccessful());
        assertThat(jdbcTemplate.queryForObject("SELECT profile FROM ab_page_schema WHERE pid = ?", String.class, pid)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT kind FROM ab_page_schema WHERE pid = ?", String.class, pid)).isEqualTo("list");
        assertThat(objectMapper.readTree(jdbcTemplate.queryForObject("SELECT blocks::text FROM ab_page_schema WHERE pid = ?", String.class, pid)))
                .isEqualTo(objectMapper.valueToTree(originalBlocks));
        mockMvc.perform(post("/api/pages/{pid}/publish", pid)).andExpect(status().is2xxSuccessful());
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
