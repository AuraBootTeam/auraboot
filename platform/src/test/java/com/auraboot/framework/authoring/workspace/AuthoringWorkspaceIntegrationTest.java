package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.authoring.policy.AuthoringCapabilityRegistry;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyPatchRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ChangeSetView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.OpenSessionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.PatchResult;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReleaseView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReviewRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RevisionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RollbackRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.PageSchemaService;
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

import java.time.Instant;

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
    private AuthoringGovernanceService governanceService;

    @Autowired
    private AuthoringActiveReleaseResolver activeReleaseResolver;

    @Autowired
    private AuthoringCapabilityRegistry capabilityRegistry;

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    @Autowired
    private PageSchemaService pageSchemaService;

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
        mockMvc.perform(post("/api/authoring/change-sets/missing/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/authoring/change-sets/missing/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/authoring/releases/missing/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedChannelVersion\":1,\"reason\":\"test\"}"))
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
    void controllerAllowsReviewPermissionButStillRejectsPublish() throws Exception {
        PageSchema page = insertPage("normal");
        long environmentId = MetaContext.getCurrentEnvironmentId();
        SessionView opened;
        try {
            MetaContext.setContext(
                    testTenant.getId(), testUser.getId() + 100_000,
                    "author", "author");
            MetaContext.setEnvironmentId(environmentId);
            opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
            workspaceService.apply(opened.sessionPid(), new ApplyPatchRequest(
                    1, "table-1", "/props/pageSize", PatchOperation.ADD,
                    objectMapper.getNodeFactory().numberNode(20),
                    capabilityRegistry.find("table").orElseThrow().checksum()));
            governanceService.submit(opened.sessionPid(), new RevisionRequest(2));
        } finally {
            applyTestMetaContext();
        }

        grantPublisherManage();
        assertThat(userPermissionService.hasPermission(
                testUser.getId(), MetaPermission.PAGE_PUBLISH_MANAGE)).isTrue();
        mockMvc.perform(post("/api/authoring/change-sets/{changeSetPid}/approve",
                        opened.changeSetPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"reason\":\"reviewed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        mockMvc.perform(post("/api/authoring/change-sets/{changeSetPid}/publish",
                        opened.changeSetPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void controllerAllowsPublishOnlyWithPublishAdminPermission() throws Exception {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        patchDensity(opened, "compact");
        governanceService.submit(opened.sessionPid(), new RevisionRequest(2));

        grantPublisherAdmin();
        assertThat(userPermissionService.hasPermission(
                testUser.getId(), MetaPermission.PAGE_PUBLISH_ADMIN)).isTrue();
        mockMvc.perform(post("/api/authoring/change-sets/{changeSetPid}/publish",
                        opened.changeSetPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.channelVersion").value(1));
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

    @Test
    void directSubmissionPublishesImmutableReleaseWithoutLeakingDraft() {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        patchDensity(opened, "compact");

        ChangeSetView submitted = governanceService.submit(
                opened.sessionPid(), new RevisionRequest(2));
        assertThat(submitted.status()).isEqualTo("APPROVED");
        assertThat(submitted.validationState()).isEqualTo("VALID");
        assertThat(submitted.approvalState()).isEqualTo("NOT_REQUIRED");
        assertThat(submitted.publishState()).isEqualTo("READY");

        ReleaseView release = governanceService.publish(
                opened.changeSetPid(), new RevisionRequest(2));
        assertThat(release.status()).isEqualTo("ACTIVE");
        assertThat(release.previousReleasePid()).isNull();
        assertThat(release.channelVersion()).isEqualTo(1);

        AuthoringActiveReleaseResolver.ActiveRelease active =
                activeReleaseResolver.findByResource(
                        testTenant.getId(), MetaContext.getCurrentEnvironmentId(),
                        "PAGE_SCHEMA", page.getPid());
        assertThat(active).isNotNull();
        assertThat(active.releasePid()).isEqualTo(release.releasePid());
        assertThat(active.snapshot().at("/blocks/0/props/density").asText())
                .isEqualTo("compact");
        assertThat(pageSchemaMapper.selectByPid(page.getPid()).getBlocks())
                .contains("normal").doesNotContain("compact");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ab_authoring_release SET manifest = '{}'::jsonb WHERE pid = ?",
                release.releasePid()))
                .hasMessageContaining("authoring release content is immutable");
    }

    @Test
    void runtimeEndpointsExposeOnlyPublishedActiveReleaseSnapshot() throws Exception {
        grantPageSchemaRead();
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        patchDensity(opened, "compact");
        governanceService.submit(opened.sessionPid(), new RevisionRequest(2));
        ReleaseView release = governanceService.publish(
                opened.changeSetPid(), new RevisionRequest(2));

        mockMvc.perform(get("/api/pages/key/{pageKey}", page.getPageKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blocks[0].props.density").value("compact"))
                .andExpect(jsonPath("$.data.runtime.source").value("AUTHORING_RELEASE"))
                .andExpect(jsonPath("$.data.runtime.releasePid").value(release.releasePid()))
                .andExpect(jsonPath("$.data.runtime.channelVersion").value(1))
                .andExpect(jsonPath("$.data.runtime.snapshotChecksum").isNotEmpty())
                .andExpect(jsonPath("$.data.runtime.cacheKey")
                        .value(org.hamcrest.Matchers.startsWith("authoring-release:")));
        mockMvc.perform(get("/api/pages/runtime/{pid}", page.getPid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runtime.releasePid").value(release.releasePid()))
                .andExpect(jsonPath("$.data.blocks[0].props.density").value("compact"));
        mockMvc.perform(post("/api/pages/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageKeys\":[\"" + page.getPageKey() + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].runtime.releasePid").value(release.releasePid()))
                .andExpect(jsonPath("$.data[0].blocks[0].props.density").value("compact"));

        applyTestMetaContext();
        var mobileVersion = pageSchemaService.getVersionsSince(Instant.now().minusSeconds(60))
                .stream()
                .filter(version -> page.getPageKey().equals(version.getPageKey()))
                .findFirst()
                .orElseThrow();
        assertThat(mobileVersion.getRuntime().releasePid()).isEqualTo(release.releasePid());
        assertThat(mobileVersion.getRuntime().channelVersion()).isEqualTo(1);
        assertThat(pageSchemaMapper.selectByPid(page.getPid()).getBlocks())
                .contains("normal").doesNotContain("compact");
    }

    @Test
    void runtimeEndpointDoesNotExposeManagementDraft() throws Exception {
        grantPageSchemaRead();
        PageSchema page = insertPage("normal");
        page.setStatus("draft");
        pageSchemaMapper.updateById(page);

        mockMvc.perform(get("/api/pages/key/{pageKey}", page.getPageKey()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/pages/runtime/{pid}", page.getPid()))
                .andExpect(status().isNotFound());
    }

    @Test
    void reviewedChangeRequiresDifferentApproverBoundToExactRevision() {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        String checksum = capabilityRegistry.find("table").orElseThrow().checksum();
        workspaceService.apply(opened.sessionPid(), new ApplyPatchRequest(
                1, "table-1", "/props/pageSize", PatchOperation.ADD,
                objectMapper.getNodeFactory().numberNode(20), checksum));

        ChangeSetView submitted = governanceService.submit(
                opened.sessionPid(), new RevisionRequest(2));
        assertThat(submitted.status()).isEqualTo("IN_REVIEW");
        assertThat(submitted.approvalState()).isEqualTo("PENDING");
        assertThatThrownBy(() -> governanceService.approve(
                opened.changeSetPid(), new ReviewRequest(2, "self approval")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("four-eyes-required");

        long environmentId = MetaContext.getCurrentEnvironmentId();
        try {
            MetaContext.setContext(
                    testTenant.getId(), testUser.getId() + 100_000,
                    "reviewer", "reviewer");
            MetaContext.setEnvironmentId(environmentId);
            ChangeSetView approved = governanceService.approve(
                    opened.changeSetPid(), new ReviewRequest(2, "reviewed"));
            assertThat(approved.status()).isEqualTo("APPROVED");
            assertThat(approved.approvalState()).isEqualTo("APPROVED");
            assertThat(approved.publishState()).isEqualTo("READY");
        } finally {
            applyTestMetaContext();
        }

        ReleaseView release = governanceService.publish(
                opened.changeSetPid(), new RevisionRequest(2));
        assertThat(release.changeSetRevision()).isEqualTo(2);
    }

    @Test
    void activeReleaseMakesConcurrentLegacyBaseStale() {
        PageSchema page = insertPage("normal");
        SessionView first = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        SessionView concurrent = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        patchDensity(first, "compact");
        patchDensity(concurrent, "comfortable");

        governanceService.submit(first.sessionPid(), new RevisionRequest(2));
        governanceService.publish(first.changeSetPid(), new RevisionRequest(2));

        assertThatThrownBy(() -> governanceService.submit(
                concurrent.sessionPid(), new RevisionRequest(2)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("base-release-stale");
        assertThat(workspaceService.get(concurrent.sessionPid()).revision()).isEqualTo(2);
    }

    @Test
    void secondReleaseUsesActiveBaseAndRollbackAtomicallyRestoresPriorSnapshot() {
        PageSchema page = insertPage("normal");
        SessionView first = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        patchDensity(first, "compact");
        governanceService.submit(first.sessionPid(), new RevisionRequest(2));
        ReleaseView releaseOne = governanceService.publish(
                first.changeSetPid(), new RevisionRequest(2));

        SessionView second = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        assertThat(second.snapshot().at("/blocks/0/props/density").asText())
                .isEqualTo("compact");
        patchDensity(second, "comfortable");
        governanceService.submit(second.sessionPid(), new RevisionRequest(2));
        ReleaseView releaseTwo = governanceService.publish(
                second.changeSetPid(), new RevisionRequest(2));
        assertThat(releaseTwo.previousReleasePid()).isEqualTo(releaseOne.releasePid());
        assertThat(releaseTwo.channelVersion()).isEqualTo(2);

        ReleaseView restored = governanceService.rollback(
                releaseTwo.releasePid(), new RollbackRequest(2, "regression"));
        assertThat(restored.releasePid()).isEqualTo(releaseOne.releasePid());
        assertThat(restored.channelVersion()).isEqualTo(3);
        AuthoringActiveReleaseResolver.ActiveRelease active =
                activeReleaseResolver.findByResource(
                        testTenant.getId(), MetaContext.getCurrentEnvironmentId(),
                        "PAGE_SCHEMA", page.getPid());
        assertThat(active.releasePid()).isEqualTo(releaseOne.releasePid());
        assertThat(active.snapshot().at("/blocks/0/props/density").asText())
                .isEqualTo("compact");
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

    private PatchResult patchDensity(SessionView opened, String density) {
        return workspaceService.apply(opened.sessionPid(), new ApplyPatchRequest(
                opened.revision(), "table-1", "/props/density", PatchOperation.REPLACE,
                objectMapper.getNodeFactory().textNode(density),
                capabilityRegistry.find("table").orElseThrow().checksum()));
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

    private void grantPublisherManage() {
        grantCommittedPermissionToTestRole(
                MetaPermission.PAGE_PUBLISH_MANAGE,
                "meta",
                "publish",
                "review",
                "Page ChangeSet Review");
        userPermissionService.evictUserPermissions(getTestUser().getId());
    }

    private void grantPublisherAdmin() {
        grantCommittedPermissionToTestRole(
                MetaPermission.PAGE_PUBLISH_ADMIN,
                "meta",
                "publish",
                "admin",
                "Page ChangeSet Publish Admin");
        userPermissionService.evictUserPermissions(getTestUser().getId());
    }

    private void grantPageSchemaRead() {
        grantCommittedPermissionToTestRole(
                MetaPermission.PAGE_SCHEMA_READ,
                "meta",
                "page-schema",
                "read",
                "Page Schema Runtime Read");
        userPermissionService.evictUserPermissions(getTestUser().getId());
    }
}
