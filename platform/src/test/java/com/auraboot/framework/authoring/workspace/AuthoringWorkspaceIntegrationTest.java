package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.authoring.policy.AuthoringCapabilityRegistry;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyPatchRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.OpenSessionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.PatchResult;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthoringWorkspaceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AuthoringWorkspaceService workspaceService;

    @Autowired
    private AuthoringCapabilityRegistry capabilityRegistry;

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserPermissionService userPermissionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        Filter contextFilter = (request, response, chain) -> {
            try {
                applyTestMetaContext();
                CustomUserDetails userDetails = new CustomUserDetails(
                        getTestUser().getUserName(),
                        "test-password",
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        AuthorityUtils.createAuthorityList("role_admin"),
                        true, true, true, true);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities()));
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
                SecurityContextHolder.clearContext();
            }
        };
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilter(contextFilter, "/*")
                .build();
    }

    @Test
    void controllerDeniesCapabilitiesAndSessionsWithoutScopedPermissions() throws Exception {
        userPermissionService.evictUserPermissions(getTestUser().getId());

        mockMvc.perform(get("/api/authoring/capabilities"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/authoring/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pagePid\":\"hidden\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void controllerSeparatesCapabilityReadFromSessionWritePermission() throws Exception {
        grantDesignerRead();

        mockMvc.perform(get("/api/authoring/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checksum").isNotEmpty())
                .andExpect(jsonPath("$.data.manifests").isArray());
        mockMvc.perform(post("/api/authoring/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pagePid\":\"hidden\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void controllerOpensSessionWithManagePermissionAndDropsUnknownContext() throws Exception {
        grantDesignerRead();
        grantDesignerManage();
        PageSchema page = insertPage("normal");

        mockMvc.perform(post("/api/authoring/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pagePid":"%s",
                                  "interactionContext":{
                                    "route":"/orders",
                                    "recordPid":"record-1",
                                    "secret":"must-not-return"
                                  }
                                }
                                """.formatted(page.getPid())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionPid").isNotEmpty())
                .andExpect(jsonPath("$.data.changeSetPid").isNotEmpty())
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.interactionContext.route").value("/orders"))
                .andExpect(jsonPath("$.data.interactionContext.secret").doesNotExist());
    }

    @Test
    void controllerRejectsUnsafeResumeRouteWithoutCreatingSession() throws Exception {
        grantDesignerManage();
        PageSchema page = insertPage("normal");
        Integer before = authoringSessionCount();

        mockMvc.perform(post("/api/authoring/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pagePid":"%s",
                                  "interactionContext":{"route":"javascript:alert(1)"}
                                }
                                """.formatted(page.getPid())))
                .andExpect(status().isUnprocessableEntity());

        applyTestMetaContext();
        assertThat(authoringSessionCount()).isEqualTo(before);
    }

    @Test
    void controllerRechecksManagePermissionBeforeApplyingPatch() throws Exception {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        grantDesignerRead();

        mockMvc.perform(patch("/api/authoring/sessions/{sessionPid}/patches", opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody(opened, "/props/density", "compact")))
                .andExpect(status().isForbidden());

        applyTestMetaContext();
        assertThat(workspaceService.get(opened.sessionPid()).revision()).isEqualTo(1);
    }

    @Test
    void controllerRejectsIdentityMutationWithoutChangingDraft() throws Exception {
        grantDesignerManage();
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));

        mockMvc.perform(patch("/api/authoring/sessions/{sessionPid}/patches", opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody(opened, "/id", "stolen")))
                .andExpect(status().isUnprocessableEntity());

        applyTestMetaContext();
        SessionView reloaded = workspaceService.get(opened.sessionPid());
        assertThat(reloaded.revision()).isEqualTo(1);
        assertThat(reloaded.snapshot().at("/blocks/0/id").asText()).isEqualTo("table-1");
    }

    @Test
    void openAndPatchPersistsIsolatedRevisionedDraft() throws Exception {
        PageSchema page = insertPage("normal");
        ObjectNode context = objectMapper.createObjectNode();
        context.put("route", "/orders");
        context.put("recordPid", "record-1");
        context.put("secret", "must-not-persist");

        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), context));
        PatchResult result = workspaceService.apply(opened.sessionPid(), new ApplyPatchRequest(
                1,
                "table-1",
                "/props/density",
                PatchOperation.REPLACE,
                objectMapper.getNodeFactory().textNode("compact"),
                capabilityRegistry.find("table").orElseThrow().checksum()));

        assertThat(opened.revision()).isEqualTo(1);
        assertThat(opened.interactionContext().has("secret")).isFalse();
        assertThat(result.session().revision()).isEqualTo(2);
        assertThat(result.session().snapshot().at("/blocks/0/props/density").asText())
                .isEqualTo("compact");
        assertThat(result.changeItemPid()).hasSize(26);
        assertThat(count("ab_authoring_change_set", opened.changeSetPid())).isEqualTo(1);
        assertThat(count("ab_authoring_config_session", opened.sessionPid())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_authoring_change_item WHERE change_set_id = "
                        + "(SELECT id FROM ab_authoring_change_set WHERE pid = ?)",
                Integer.class,
                opened.changeSetPid())).isEqualTo(1);

        PageSchema unchanged = pageSchemaMapper.selectByPid(page.getPid());
        assertThat(unchanged.getBlocks()).contains("normal").doesNotContain("compact");
    }

    @Test
    void staleRevisionCannotOverwriteNewerDraft() {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        String manifestChecksum = capabilityRegistry.find("table").orElseThrow().checksum();
        workspaceService.apply(opened.sessionPid(), new ApplyPatchRequest(
                1,
                "table-1",
                "/props/density",
                PatchOperation.REPLACE,
                objectMapper.getNodeFactory().textNode("compact"),
                manifestChecksum));

        assertThatThrownBy(() -> workspaceService.apply(opened.sessionPid(), new ApplyPatchRequest(
                1,
                "table-1",
                "/props/density",
                PatchOperation.REPLACE,
                objectMapper.getNodeFactory().textNode("comfortable"),
                manifestChecksum)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.revision.conflict");

        SessionView reloaded = workspaceService.get(opened.sessionPid());
        assertThat(reloaded.revision()).isEqualTo(2);
        assertThat(reloaded.snapshot().at("/blocks/0/props/density").asText())
                .isEqualTo("compact");
    }

    @Test
    void sessionLookupIsTenantScopedAndDoesNotRevealExistence() {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        long originalEnvironment = MetaContext.getCurrentEnvironmentId();

        try {
            MetaContext.setContext(
                    testTenant.getId() + 100_000,
                    testUser.getId(),
                    testUser.getPid(),
                    testUser.getUserName());
            MetaContext.setEnvironmentId(originalEnvironment);

            assertThatThrownBy(() -> workspaceService.get(opened.sessionPid()))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("authoring.session.not-found");
        } finally {
            applyTestMetaContext();
        }
    }

    @Test
    void sessionLookupRejectsDifferentActorInsideSameTenant() {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        long originalEnvironment = MetaContext.getCurrentEnvironmentId();

        try {
            MetaContext.setContext(
                    testTenant.getId(),
                    testUser.getId() + 100_000,
                    "different-actor",
                    "different-actor");
            MetaContext.setEnvironmentId(originalEnvironment);

            assertThatThrownBy(() -> workspaceService.get(opened.sessionPid()))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("authoring.session.actor-mismatch");
        } finally {
            applyTestMetaContext();
        }
    }

    private PageSchema insertPage(String density) {
        String pid = UniqueIdGenerator.generate();
        PageSchema page = new PageSchema();
        page.setPid(pid);
        page.setTenantId(testTenant.getId());
        page.setEnvId(MetaContext.getCurrentEnvironmentId());
        page.setPageKey("authoring_" + pid.toLowerCase());
        page.setModelCode("test_model");
        page.setName("Authoring " + pid);
        page.setKind("list");
        page.setSchemaVersion(2);
        page.setProfile("admin");
        page.setTitle("{\"en-US\":\"Orders\"}");
        page.setLayout("{}");
        page.setBlocks("[{\"id\":\"table-1\",\"blockType\":\"table\","
                + "\"props\":{\"density\":\"" + density + "\"}}]");
        page.setStatus("published");
        page.setVersion(1);
        page.setSemver("1.0.0");
        page.setRowVersion(1);
        page.setIsCurrent(true);
        page.setDeletedFlag(false);
        pageSchemaMapper.insert(page);
        return page;
    }

    private int count(String table, String pid) {
        if (!table.equals("ab_authoring_change_set")
                && !table.equals("ab_authoring_config_session")) {
            throw new IllegalArgumentException("Unexpected table");
        }
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE pid = ?",
                Integer.class,
                pid);
        return value == null ? 0 : value;
    }

    private int authoringSessionCount() {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_authoring_config_session WHERE tenant_id = ? AND env_id = ?",
                Integer.class,
                testTenant.getId(),
                MetaContext.getCurrentEnvironmentId());
        return value == null ? 0 : value;
    }

    private String patchBody(SessionView opened, String propertyPath, String value) {
        return """
                {
                  "expectedRevision":%d,
                  "blockId":"table-1",
                  "propertyPath":"%s",
                  "operation":"REPLACE",
                  "value":"%s",
                  "manifestChecksum":"%s"
                }
                """.formatted(
                opened.revision(),
                propertyPath,
                value,
                capabilityRegistry.find("table").orElseThrow().checksum());
    }

    private void grantDesignerRead() {
        grantCommittedPermissionToTestRole(
                MetaPermission.PAGE_DESIGNER_READ,
                "meta",
                "designer",
                "read",
                "Page Designer Read");
        userPermissionService.evictUserPermissions(getTestUser().getId());
    }

    private void grantDesignerManage() {
        grantCommittedPermissionToTestRole(
                MetaPermission.PAGE_DESIGNER_MANAGE,
                "meta",
                "designer",
                "update",
                "Page Designer Manage");
        userPermissionService.evictUserPermissions(getTestUser().getId());
    }
}
