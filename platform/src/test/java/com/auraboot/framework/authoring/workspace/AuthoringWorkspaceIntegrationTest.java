package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.authoring.policy.AuthoringCapabilityRegistry;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyPatchRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ChangeSetView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.CreateHandoffRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.HandoffContextView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.HandoffCreatedView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.OpenSessionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.PatchResult;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReleaseView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReviewRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RevisionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RollbackRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ResumeEditingRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SplitChangeSetRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SplitChangeSetView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.StudioIntent;
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
import java.util.List;

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
    private AuthoringHandoffService handoffService;

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
        mockMvc.perform(post("/api/authoring/sessions/missing/handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"intent\":\"PAGE_STRUCTURE\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/authoring/change-sets/missing/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/authoring/change-sets/missing/review-workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/authoring/review-workspaces/missing"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/authoring/sessions/missing/writer-lease/takeover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/authoring/sessions/missing/change-items"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/authoring/sessions/missing/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":1,"itemPids":["item"],
                                 "title":"split","reason":"test"}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/authoring/sessions/missing/review/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/authoring/sessions/missing/approved/reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/authoring/handoffs/ctx_abcdefghijklmnopqrstuvwxyz123456/consume"))
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
    void changeSetSplitRequiresDesignerAdminRatherThanInlineManagePermission() throws Exception {
        grantDesignerManage();

        mockMvc.perform(get("/api/authoring/sessions/missing/change-items"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/authoring/sessions/missing/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":1,"itemPids":["item"],
                                 "title":"split","reason":"separate risk"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void designerAdminListsAndSplitsChangeItemsThroughTheHttpBoundary() throws Exception {
        grantDesignerAdmin();
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        PatchResult lowRisk = patchDensity(opened, "compact");
        PatchResult highRisk = workspaceService.applyStudio(
                opened.sessionPid(),
                new ApplyPatchRequest(
                        lowRisk.session().revision(), "table-1", "/dataSource",
                        PatchOperation.ADD, objectMapper.createObjectNode().put("model", "payments"),
                        capabilityRegistry.find("table").orElseThrow().checksum()));

        mockMvc.perform(get(
                        "/api/authoring/sessions/{sessionPid}/change-items",
                        opened.sessionPid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].changeItemPid").value(highRisk.changeItemPid()))
                .andExpect(jsonPath("$.data[1].riskLevel").value("L3"));

        ObjectNode request = objectMapper.createObjectNode();
        request.put("expectedRevision", highRisk.session().revision());
        request.putArray("itemPids").add(highRisk.changeItemPid());
        request.put("title", "支付数据源变更");
        request.put("reason", "将 L3 变更独立评审");
        mockMvc.perform(post(
                        "/api/authoring/sessions/{sessionPid}/split",
                        opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceSession.revision").value(4))
                .andExpect(jsonPath("$.data.sourceItems.length()").value(1))
                .andExpect(jsonPath("$.data.targetSession.revision").value(2))
                .andExpect(jsonPath("$.data.targetItems[0].sourceChangeItemPid")
                        .value(highRisk.changeItemPid()))
                .andExpect(jsonPath("$.data.lineage[0].changeSetPid")
                        .value(opened.changeSetPid()));
    }

    @Test
    void secondAdminObservesReadOnlyAndTakesOverTheSingleWriterLeaseWithAudit() throws Exception {
        grantDesignerAdmin();
        PageSchema page = insertPage("normal");
        long environmentId = MetaContext.getCurrentEnvironmentId();
        SessionView original;
        try {
            MetaContext.setContext(
                    testTenant.getId(), testUser.getId() + 100_000,
                    "original-author", "original-author");
            MetaContext.setEnvironmentId(environmentId);
            original = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        } finally {
            applyTestMetaContext();
        }

        String observerBody = mockMvc.perform(post(
                        "/api/authoring/change-sets/{changeSetPid}/sessions",
                        original.changeSetPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interactionContext\":{\"route\":\"/studio/review\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("READ_ONLY"))
                .andExpect(jsonPath("$.data.changeSetPid").value(original.changeSetPid()))
                .andExpect(jsonPath("$.data.writerLease.status").value("HELD_BY_OTHER"))
                .andExpect(jsonPath("$.data.interactionContext.route").value("/studio/review"))
                .andReturn().getResponse().getContentAsString();
        String observerSessionPid = objectMapper.readTree(observerBody).at("/data/sessionPid").asText();

        mockMvc.perform(post(
                        "/api/authoring/sessions/{sessionPid}/writer-lease/takeover",
                        observerSessionPid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"reason\":\"继续处理紧急变更\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionPid").value(observerSessionPid))
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.writerLease.status").value("OWNED"))
                .andExpect(jsonPath("$.data.writerLease.revision").value(2));

        applyTestMetaContext();
        PatchResult saved = workspaceService.apply(observerSessionPid, new ApplyPatchRequest(
                1, "table-1", "/props/density", PatchOperation.REPLACE,
                objectMapper.getNodeFactory().textNode("compact"),
                capabilityRegistry.find("table").orElseThrow().checksum()));
        assertThat(saved.session().revision()).isEqualTo(2);

        try {
            MetaContext.setContext(
                    testTenant.getId(), testUser.getId() + 100_000,
                    "original-author", "original-author");
            MetaContext.setEnvironmentId(environmentId);
            SessionView lost = workspaceService.get(original.sessionPid());
            assertThat(lost.state()).isEqualTo("READ_ONLY");
            assertThat(lost.writerLease().status()).isEqualTo("HELD_BY_OTHER");
            assertThatThrownBy(() -> workspaceService.apply(
                    original.sessionPid(),
                    new ApplyPatchRequest(
                            1, "table-1", "/props/density", PatchOperation.REPLACE,
                            objectMapper.getNodeFactory().textNode("comfortable"),
                            capabilityRegistry.find("table").orElseThrow().checksum())))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("authoring.writer-lease.lost");
        } finally {
            applyTestMetaContext();
        }

        Integer takeoverEvents = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_audit_event
                WHERE tenant_id = ? AND env_id = ? AND change_set_pid = ?
                  AND event_type = 'WRITER_LEASE_TAKEN_OVER'
                  AND result = 'ALLOW'
                  AND metadata ->> 'reason' = '继续处理紧急变更'
                """, Integer.class,
                testTenant.getId(), environmentId, original.changeSetPid());
        assertThat(takeoverEvents).isEqualTo(1);
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
                                    "filters":{"status":["OPEN"]},
                                    "sort":["createdAt:desc"],
                                    "scroll":{"x":12,"y":480},
                                    "viewport":{"width":1440,"height":900,"scale":2},
                                    "selection":"table-1",
                                    "outlinePath":["page-1","table-1"],
                                    "secret":"must-not-return"
                                  }
                                }
                                """.formatted(page.getPid())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionPid").isNotEmpty())
                .andExpect(jsonPath("$.data.changeSetPid").isNotEmpty())
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.interactionContext.route").value("/orders"))
                .andExpect(jsonPath("$.data.interactionContext.filters.status[0]").value("OPEN"))
                .andExpect(jsonPath("$.data.interactionContext.sort[0]").value("createdAt:desc"))
                .andExpect(jsonPath("$.data.interactionContext.scroll.y").value(480))
                .andExpect(jsonPath("$.data.interactionContext.viewport.width").value(1440))
                .andExpect(jsonPath("$.data.interactionContext.selection").value("table-1"))
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
    void handoffContextIsOpaqueActorBoundOneTimeAndFixedRoute() throws Exception {
        grantDesignerManage();
        PageSchema page = insertPage("normal");
        ObjectNode interactionContext = objectMapper.createObjectNode();
        interactionContext.put("route", "/orders");
        interactionContext.put("recordPid", "record-1");
        SessionView opened = workspaceService.open(
                new OpenSessionRequest(page.getPid(), interactionContext));

        String createBody = mockMvc.perform(post(
                        "/api/authoring/sessions/{sessionPid}/handoffs", opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision":1,
                                  "intent":"PAGE_STRUCTURE",
                                  "blockId":"table-1",
                                  "propertyPath":"/props/dataSource",
                                  "targetRoute":"https://evil.example/steal"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contextId")
                        .value(org.hamcrest.Matchers.matchesPattern(
                                "ctx_[A-Za-z0-9_-]{32,80}")))
                .andExpect(jsonPath("$.data.targetRoute").value("/unified-designer"))
                .andReturn().getResponse().getContentAsString();
        String contextId = objectMapper.readTree(createBody).at("/data/contextId").asText();

        String storedHash = jdbcTemplate.queryForObject(
                "SELECT nonce_hash FROM ab_authoring_handoff_context WHERE change_set_id = "
                        + "(SELECT id FROM ab_authoring_change_set WHERE pid = ?)",
                String.class,
                opened.changeSetPid());
        assertThat(storedHash).hasSize(64).doesNotContain(contextId);

        mockMvc.perform(post("/api/authoring/handoffs/{contextId}/consume", contextId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pagePid").value(page.getPid()))
                .andExpect(jsonPath("$.data.changeSetPid").value(opened.changeSetPid()))
                .andExpect(jsonPath("$.data.sessionPid").value(opened.sessionPid()))
                .andExpect(jsonPath("$.data.targetRoute").value("/unified-designer"))
                .andExpect(jsonPath("$.data.returnTo").value("/orders"))
                .andExpect(jsonPath("$.data.blockId").value("table-1"))
                .andExpect(jsonPath("$.data.propertyPath").value("/props/dataSource"))
                .andExpect(jsonPath("$.data.interactionContext.recordPid").value("record-1"));

        mockMvc.perform(post("/api/authoring/handoffs/{contextId}/consume", contextId))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/authoring/handoffs/{contextId}/consume", contextId + "x"))
                .andExpect(status().isNotFound());
        applyTestMetaContext();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_authoring_audit_event WHERE session_pid = ? "
                        + "AND event_type IN ('HANDOFF_CREATED', 'HANDOFF_CONSUMED')",
                Integer.class,
                opened.sessionPid())).isEqualTo(2);
    }

    @Test
    void handoffRejectsExpiredAndCrossTenantContextsWithoutDisclosure() {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        HandoffCreatedView expired = handoffService.create(
                opened.sessionPid(),
                new CreateHandoffRequest(1, StudioIntent.PAGE_STRUCTURE, "table-1", "/props/title"));
        jdbcTemplate.update("UPDATE ab_authoring_handoff_context SET expires_at = ? "
                        + "WHERE nonce_hash IS NOT NULL AND change_set_id = "
                        + "(SELECT id FROM ab_authoring_change_set WHERE pid = ?)",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                opened.changeSetPid());
        assertThatThrownBy(() -> handoffService.consume(expired.contextId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.handoff.expired");

        HandoffCreatedView tenantBound = handoffService.create(
                opened.sessionPid(),
                new CreateHandoffRequest(1, StudioIntent.PERMISSION, null, null));
        long originalEnvironment = MetaContext.getCurrentEnvironmentId();
        try {
            MetaContext.setContext(
                    testTenant.getId() + 100_000,
                    testUser.getId(),
                    testUser.getPid(),
                    testUser.getUserName());
            MetaContext.setEnvironmentId(originalEnvironment);
            assertThatThrownBy(() -> handoffService.consume(tenantBound.contextId()))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("authoring.handoff.not-found");
        } finally {
            applyTestMetaContext();
        }

        HandoffContextView consumed = handoffService.consume(tenantBound.contextId());
        assertThat(consumed.intent()).isEqualTo(StudioIntent.PERMISSION);
        assertThat(consumed.returnTo()).isEqualTo("/");
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
    void studioPatchRequiresAdminPermission() throws Exception {
        grantDesignerManage();
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));

        mockMvc.perform(patch("/api/authoring/sessions/{sessionPid}/studio-patches",
                        opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(studioPatchBody(opened)))
                .andExpect(status().isForbidden());
    }

    @Test
    void studioMoveRequiresAdminPermission() throws Exception {
        grantDesignerManage();
        PageSchema page = insertReorderPage();
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));

        mockMvc.perform(patch("/api/authoring/sessions/{sessionPid}/studio-moves",
                        opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(studioMoveBody(opened, "column-b", "column-a")))
                .andExpect(status().isForbidden());

        applyTestMetaContext();
        assertThat(workspaceService.get(opened.sessionPid()).revision()).isEqualTo(1);
    }

    @Test
    void studioPatchPersistsIntoTheSameChangeSetWithoutChangingLegacyPage() throws Exception {
        grantDesignerAdmin();
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));

        mockMvc.perform(patch("/api/authoring/sessions/{sessionPid}/studio-patches",
                        opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(studioPatchBody(opened)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session.changeSetPid").value(opened.changeSetPid()))
                .andExpect(jsonPath("$.data.session.revision").value(2))
                .andExpect(jsonPath("$.data.session.riskLevel").value("L3"))
                .andExpect(jsonPath("$.data.session.route").value("HANDOFF_STUDIO"))
                .andExpect(jsonPath("$.data.session.publishPolicy").value("STUDIO_APPROVAL"))
                .andExpect(jsonPath("$.data.session.snapshot.blocks[0].dataSource.model")
                        .value("payments"));

        applyTestMetaContext();
        PageSchema unchanged = pageSchemaMapper.selectByPid(page.getPid());
        assertThat(unchanged.getBlocks()).doesNotContain("payments");
    }

    @Test
    void studioMovePersistsStableSiblingOrderIntoTheSameChangeSet() throws Exception {
        grantDesignerAdmin();
        PageSchema page = insertReorderPage();
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));

        mockMvc.perform(patch("/api/authoring/sessions/{sessionPid}/studio-moves",
                        opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(studioMoveBody(opened, "column-b", "column-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session.changeSetPid").value(opened.changeSetPid()))
                .andExpect(jsonPath("$.data.session.revision").value(2))
                .andExpect(jsonPath("$.data.session.riskLevel").value("L1"))
                .andExpect(jsonPath("$.data.session.route").value("GUIDED_INLINE"))
                .andExpect(jsonPath("$.data.session.publishPolicy").value("DEFAULT_REVIEW"))
                .andExpect(jsonPath("$.data.session.snapshot.blocks[0].blocks[0].id")
                        .value("column-b"))
                .andExpect(jsonPath("$.data.session.snapshot.blocks[0].blocks[1].id")
                        .value("column-a"))
                .andExpect(jsonPath("$.data.previousValue.beforeBlockId").value("column-c"))
                .andExpect(jsonPath("$.data.savedValue.beforeBlockId").value("column-a"));

        applyTestMetaContext();
        String operation = jdbcTemplate.queryForObject("""
                SELECT operation FROM ab_authoring_change_item
                WHERE tenant_id = ? AND env_id = ? AND change_set_id = (
                    SELECT id FROM ab_authoring_change_set
                    WHERE tenant_id = ? AND env_id = ? AND pid = ?)
                """, String.class,
                testTenant.getId(), MetaContext.getCurrentEnvironmentId(),
                testTenant.getId(), MetaContext.getCurrentEnvironmentId(), opened.changeSetPid());
        assertThat(operation).isEqualTo("MOVE");

        PageSchema unchanged = pageSchemaMapper.selectByPid(page.getPid());
        assertThat(unchanged.getBlocks()).containsSubsequence("column-a", "column-b", "column-c");
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
    void reviewerGetsABoundedReadOnlyWorkspaceWithoutDesignerAdminPermission() throws Exception {
        PageSchema page = insertPage("normal");
        SessionView normalAuthoringSession = workspaceService.open(
                new OpenSessionRequest(page.getPid(), null));
        long environmentId = MetaContext.getCurrentEnvironmentId();
        SessionView opened;
        long authorUserId = testUser.getId() + 100_000;
        try {
            MetaContext.setContext(
                    testTenant.getId(), authorUserId, "author", "author");
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
        String body = mockMvc.perform(post(
                        "/api/authoring/change-sets/{changeSetPid}/review-workspaces",
                        opened.changeSetPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session.state").value("READ_ONLY"))
                .andExpect(jsonPath("$.data.session.workspaceMode").value("REVIEW"))
                .andExpect(jsonPath("$.data.session.ownerUserId").value(authorUserId))
                .andExpect(jsonPath("$.data.session.changeSetStatus").value("IN_REVIEW"))
                .andExpect(jsonPath("$.data.session.revision").value(2))
                .andExpect(jsonPath("$.data.capabilities.checksum").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String reviewSessionPid = objectMapper.readTree(body).at("/data/session/sessionPid").asText();

        mockMvc.perform(get(
                        "/api/authoring/review-workspaces/{sessionPid}", reviewSessionPid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session.sessionPid").value(reviewSessionPid));
        mockMvc.perform(get(
                        "/api/authoring/review-workspaces/{sessionPid}",
                        normalAuthoringSession.sessionPid()))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_audit_event
                WHERE tenant_id = ? AND env_id = ? AND change_set_pid = ?
                  AND event_type = 'REVIEW_WORKSPACE_OPENED'
                  AND metadata ->> 'workspaceMode' = 'REVIEW'
                """, Integer.class,
                testTenant.getId(), environmentId, opened.changeSetPid())).isEqualTo(1);

        grantDesignerManage();
        grantDesignerAdmin();
        mockMvc.perform(post(
                        "/api/authoring/sessions/{sessionPid}/writer-lease/takeover",
                        reviewSessionPid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"reason\":\"reviewer must stay read-only\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch(
                        "/api/authoring/sessions/{sessionPid}/studio-patches", reviewSessionPid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":2,"blockId":"table-1",
                                 "propertyPath":"/props/density","operation":"REPLACE",
                                 "value":"compact","manifestChecksum":"ignored"}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(
                        "/api/authoring/sessions/{sessionPid}/handoffs", reviewSessionPid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"intent\":\"PAGE_STRUCTURE\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(
                        "/api/authoring/sessions/{sessionPid}/submit", reviewSessionPid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(
                        "/api/authoring/change-sets/{changeSetPid}/approve",
                        opened.changeSetPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"reason\":\"reviewed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
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
        int outboxBefore = tableCount("ab_outbox");
        int behaviorOutboxBefore = tableCount("ab_behavior_outcome_outbox");
        int messageBefore = tableCount("ab_im_message");
        int webhookBefore = tableCount("ab_webhook_delivery_log");
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
        assertThat(tableCount("ab_outbox")).isEqualTo(outboxBefore);
        assertThat(tableCount("ab_behavior_outcome_outbox")).isEqualTo(behaviorOutboxBefore);
        assertThat(tableCount("ab_im_message")).isEqualTo(messageBefore);
        assertThat(tableCount("ab_webhook_delivery_log")).isEqualTo(webhookBefore);
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
    void ownerWithdrawalCreatesANewEditableRevisionAndStalesThePendingReview() {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        workspaceService.apply(opened.sessionPid(), new ApplyPatchRequest(
                1, "table-1", "/props/pageSize", PatchOperation.ADD,
                objectMapper.getNodeFactory().numberNode(20),
                capabilityRegistry.find("table").orElseThrow().checksum()));
        governanceService.submit(opened.sessionPid(), new RevisionRequest(2));

        ChangeSetView withdrawn = governanceService.withdrawReview(
                opened.sessionPid(), new ResumeEditingRequest(2, "补充筛选条件"));

        assertThat(withdrawn.status()).isEqualTo("DRAFT");
        assertThat(withdrawn.revision()).isEqualTo(3);
        assertThat(withdrawn.validationState()).isEqualTo("UNVALIDATED");
        assertThat(withdrawn.approvalState()).isEqualTo("STALE");
        assertThat(approvalStatus(opened.changeSetPid(), 2)).isEqualTo("STALE");
        assertRevisionTransitionAudit(
                opened.changeSetPid(), "CHANGE_SET_REVIEW_WITHDRAWN",
                "补充筛选条件", 2, 3);
        SessionView resumed = workspaceService.get(opened.sessionPid());
        assertThat(resumed.state()).isEqualTo("ACTIVE");
        assertThat(resumed.revision()).isEqualTo(3);

        assertThatThrownBy(() -> workspaceService.apply(
                opened.sessionPid(), new ApplyPatchRequest(
                        2, "table-1", "/props/density", PatchOperation.REPLACE,
                        objectMapper.getNodeFactory().textNode("compact"),
                        capabilityRegistry.find("table").orElseThrow().checksum())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.revision.conflict");
        assertThat(patchDensity(resumed, "compact").session().revision()).isEqualTo(4);
    }

    @Test
    void reopeningAnApprovedRevisionInvalidatesItsApprovalBeforeFurtherEditing() {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        workspaceService.apply(opened.sessionPid(), new ApplyPatchRequest(
                1, "table-1", "/props/pageSize", PatchOperation.ADD,
                objectMapper.getNodeFactory().numberNode(20),
                capabilityRegistry.find("table").orElseThrow().checksum()));
        governanceService.submit(opened.sessionPid(), new RevisionRequest(2));

        long environmentId = MetaContext.getCurrentEnvironmentId();
        try {
            MetaContext.setContext(
                    testTenant.getId(), testUser.getId() + 100_000,
                    "reviewer", "reviewer");
            MetaContext.setEnvironmentId(environmentId);
            governanceService.approve(
                    opened.changeSetPid(), new ReviewRequest(2, "reviewed"));
        } finally {
            applyTestMetaContext();
        }

        ChangeSetView reopened = governanceService.reopenApproved(
                opened.sessionPid(), new ResumeEditingRequest(2, "批准后发现需补充说明"));

        assertThat(reopened.status()).isEqualTo("DRAFT");
        assertThat(reopened.revision()).isEqualTo(3);
        assertThat(reopened.validationState()).isEqualTo("UNVALIDATED");
        assertThat(reopened.approvalState()).isEqualTo("STALE");
        assertThat(approvalStatus(opened.changeSetPid(), 2)).isEqualTo("STALE");
        assertThat(approvalReason(opened.changeSetPid(), 2)).isEqualTo("reviewed");
        assertRevisionTransitionAudit(
                opened.changeSetPid(), "CHANGE_SET_APPROVAL_INVALIDATED",
                "批准后发现需补充说明", 2, 3);
        assertThatThrownBy(() -> governanceService.publish(
                opened.changeSetPid(), new RevisionRequest(3)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.workflow.invalid-state");
        SessionView resumed = workspaceService.get(opened.sessionPid());
        assertThat(resumed.state()).isEqualTo("ACTIVE");
        assertThat(resumed.revision()).isEqualTo(3);
        assertThat(patchDensity(resumed, "compact").session().revision()).isEqualTo(4);
    }

    @Test
    void rejectionRecordsTheOldDecisionAndReturnsTheOwnerToANewRevision() {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        workspaceService.apply(opened.sessionPid(), new ApplyPatchRequest(
                1, "table-1", "/props/pageSize", PatchOperation.ADD,
                objectMapper.getNodeFactory().numberNode(20),
                capabilityRegistry.find("table").orElseThrow().checksum()));
        governanceService.submit(opened.sessionPid(), new RevisionRequest(2));

        long environmentId = MetaContext.getCurrentEnvironmentId();
        try {
            MetaContext.setContext(
                    testTenant.getId(), testUser.getId() + 100_000,
                    "reviewer", "reviewer");
            MetaContext.setEnvironmentId(environmentId);
            assertThatThrownBy(() -> governanceService.reject(
                    opened.changeSetPid(), new ReviewRequest(2, " ")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("authoring.review.reason-required");
            ChangeSetView rejected = governanceService.reject(
                    opened.changeSetPid(), new ReviewRequest(2, "默认筛选会隐藏异常订单"));
            assertThat(rejected.status()).isEqualTo("REJECTED");
            assertThat(rejected.revision()).isEqualTo(3);
            assertThat(rejected.validationState()).isEqualTo("UNVALIDATED");
            assertThat(rejected.approvalState()).isEqualTo("REJECTED");
        } finally {
            applyTestMetaContext();
        }

        assertThat(approvalStatus(opened.changeSetPid(), 2)).isEqualTo("REJECTED");
        assertThat(approvalReason(opened.changeSetPid(), 2))
                .isEqualTo("默认筛选会隐藏异常订单");
        assertRevisionTransitionAudit(
                opened.changeSetPid(), "CHANGE_SET_REJECTED",
                "默认筛选会隐藏异常订单", 2, 3);
        SessionView resumed = workspaceService.get(opened.sessionPid());
        assertThat(resumed.state()).isEqualTo("ACTIVE");
        assertThat(resumed.revision()).isEqualTo(3);
        assertThat(patchDensity(resumed, "compact").session().revision()).isEqualTo(4);
    }

    @Test
    void splitCreatesIndependentSourceAndChildWithLineageDiffAuthorAndAudit() throws Exception {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        PatchResult lowRisk = patchDensity(opened, "compact");
        ObjectNode dataSource = objectMapper.createObjectNode().put("model", "payments");
        PatchResult highRisk = workspaceService.applyStudio(
                opened.sessionPid(),
                new ApplyPatchRequest(
                        lowRisk.session().revision(), "table-1", "/dataSource",
                        PatchOperation.ADD, dataSource,
                        capabilityRegistry.find("table").orElseThrow().checksum()));

        SplitChangeSetView split = governanceService.split(
                opened.sessionPid(),
                new SplitChangeSetRequest(
                        highRisk.session().revision(),
                        List.of(highRisk.changeItemPid()),
                        "支付数据源变更",
                        "将 L3 数据源变更与 L0 密度调整分开评审"));

        assertThat(split.sourceSession().changeSetPid()).isEqualTo(opened.changeSetPid());
        assertThat(split.sourceSession().revision()).isEqualTo(4);
        assertThat(split.sourceSession().riskLevel()).isEqualTo("L0");
        assertThat(split.sourceSession().publishPolicy()).isEqualTo("DIRECT_ALLOWED");
        assertThat(split.sourceSession().snapshot().at("/blocks/0/props/density").asText())
                .isEqualTo("compact");
        assertThat(split.sourceSession().snapshot().at("/blocks/0/dataSource").isMissingNode())
                .isTrue();
        assertThat(split.targetSession().revision()).isEqualTo(2);
        assertThat(split.targetSession().riskLevel()).isEqualTo("L3");
        assertThat(split.targetSession().publishPolicy()).isEqualTo("STUDIO_APPROVAL");
        assertThat(split.targetSession().snapshot().at("/blocks/0/props/density").asText())
                .isEqualTo("normal");
        assertThat(split.targetSession().snapshot().at("/blocks/0/dataSource/model").asText())
                .isEqualTo("payments");
        assertThat(split.sourceItems()).extracting(item -> item.changeItemPid())
                .containsExactly(lowRisk.changeItemPid());
        assertThat(split.targetItems()).hasSize(1);
        assertThat(split.targetItems().get(0).sourceChangeItemPid())
                .isEqualTo(highRisk.changeItemPid());
        assertThat(split.targetItems().get(0).actorUserId()).isEqualTo(testUser.getId());
        assertThat(split.lineage().get(0).path("changeSetPid").asText())
                .isEqualTo(opened.changeSetPid());
        assertThat(split.lineage().get(0).path("revision").asLong()).isEqualTo(3);

        assertThat(governanceService.submit(
                split.sourceSession().sessionPid(), new RevisionRequest(4)).status())
                .isEqualTo("APPROVED");
        assertThat(governanceService.submit(
                split.targetSession().sessionPid(), new RevisionRequest(2)).status())
                .isEqualTo("IN_REVIEW");

        Integer mappingCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_change_item_split item_split
                JOIN ab_authoring_change_item source_item
                  ON source_item.id = item_split.source_change_item_id
                JOIN ab_authoring_change_item target_item
                  ON target_item.id = item_split.target_change_item_id
                WHERE source_item.pid = ? AND target_item.source_change_item_id = source_item.id
                """, Integer.class, highRisk.changeItemPid());
        assertThat(mappingCount).isEqualTo(1);
        String dependencyJson = jdbcTemplate.queryForObject("""
                SELECT dependency_snapshot::text
                FROM ab_authoring_change_set_split
                WHERE target_change_set_id = (
                    SELECT id FROM ab_authoring_change_set WHERE pid = ?)
                """, String.class, split.targetSession().changeSetPid());
        ObjectNode dependencySnapshot = (ObjectNode) objectMapper.readTree(dependencyJson);
        assertThat(dependencySnapshot.path("crossPartitionDependencies").asBoolean()).isFalse();
        assertThat(dependencySnapshot.path("sourceItemPids").get(0).asText())
                .isEqualTo(lowRisk.changeItemPid());
        assertThat(dependencySnapshot.path("targetItemPids").get(0).asText())
                .isEqualTo(highRisk.changeItemPid());
        Integer auditCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_audit_event
                WHERE tenant_id = ? AND env_id = ?
                  AND event_type IN ('CHANGE_SET_SPLIT_SOURCE', 'CHANGE_SET_SPLIT_TARGET')
                  AND metadata ->> 'reason' = ?
                """, Integer.class,
                testTenant.getId(), MetaContext.getCurrentEnvironmentId(),
                "将 L3 数据源变更与 L0 密度调整分开评审");
        assertThat(auditCount).isEqualTo(2);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ab_authoring_change_set_split SET reason = 'rewrite' "
                        + "WHERE target_change_set_id = "
                        + "(SELECT id FROM ab_authoring_change_set WHERE pid = ?)",
                split.targetSession().changeSetPid()))
                .hasMessageContaining("authoring history is append-only");
    }

    @Test
    void splitRejectsCrossPartitionPropertyDependenciesWithoutChangingTheSource() {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        PatchResult first = patchDensity(opened, "compact");
        PatchResult second = patchDensity(first.session(), "comfortable");

        assertThatThrownBy(() -> governanceService.split(
                opened.sessionPid(),
                new SplitChangeSetRequest(
                        second.session().revision(),
                        List.of(second.changeItemPid()),
                        "后续密度调整",
                        "尝试切断同一路径依赖")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.split.dependency-crosses-partition");

        SessionView unchanged = workspaceService.get(opened.sessionPid());
        assertThat(unchanged.revision()).isEqualTo(3);
        assertThat(unchanged.snapshot().at("/blocks/0/props/density").asText())
                .isEqualTo("comfortable");
        assertThat(governanceService.listChangeItems(opened.sessionPid())).hasSize(2);
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

    private PageSchema insertReorderPage() {
        PageSchema page = insertPage("normal");
        page.setSchemaVersion(3);
        page.setBlocks("""
                [{"id":"list-1","blockType":"list","blocks":[
                  {"id":"column-a","blockType":"column"},
                  {"id":"column-b","blockType":"column"},
                  {"id":"column-c","blockType":"column"}
                ]}]
                """);
        pageSchemaMapper.updateById(page);
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

    private int tableCount(String table) {
        String sql = switch (table) {
            case "ab_outbox" -> "SELECT COUNT(*) FROM ab_outbox";
            case "ab_behavior_outcome_outbox" ->
                    "SELECT COUNT(*) FROM ab_behavior_outcome_outbox";
            case "ab_im_message" -> "SELECT COUNT(*) FROM ab_im_message";
            case "ab_webhook_delivery_log" ->
                    "SELECT COUNT(*) FROM ab_webhook_delivery_log";
            default -> throw new IllegalArgumentException("Unexpected table");
        };
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
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

    private String studioPatchBody(SessionView opened) {
        return """
                {
                  "expectedRevision":%d,
                  "blockId":"table-1",
                  "propertyPath":"/dataSource",
                  "operation":"ADD",
                  "value":{"model":"payments"},
                  "manifestChecksum":"%s"
                }
                """.formatted(
                opened.revision(),
                capabilityRegistry.find("table").orElseThrow().checksum());
    }

    private String studioMoveBody(SessionView opened, String blockId, String beforeBlockId) {
        return """
                {
                  "expectedRevision":%d,
                  "blockId":"%s",
                  "beforeBlockId":"%s",
                  "manifestChecksum":"%s"
                }
                """.formatted(
                opened.revision(),
                blockId,
                beforeBlockId,
                capabilityRegistry.find("column").orElseThrow().checksum());
    }

    private PatchResult patchDensity(SessionView opened, String density) {
        return workspaceService.apply(opened.sessionPid(), new ApplyPatchRequest(
                opened.revision(), "table-1", "/props/density", PatchOperation.REPLACE,
                objectMapper.getNodeFactory().textNode(density),
                capabilityRegistry.find("table").orElseThrow().checksum()));
    }

    private String approvalStatus(String changeSetPid, long revision) {
        return jdbcTemplate.queryForObject("""
                SELECT status FROM ab_authoring_approval
                WHERE change_set_id = (
                    SELECT id FROM ab_authoring_change_set WHERE pid = ?)
                  AND change_set_revision = ?
                """, String.class, changeSetPid, revision);
    }

    private String approvalReason(String changeSetPid, long revision) {
        return jdbcTemplate.queryForObject("""
                SELECT reason FROM ab_authoring_approval
                WHERE change_set_id = (
                    SELECT id FROM ab_authoring_change_set WHERE pid = ?)
                  AND change_set_revision = ?
                """, String.class, changeSetPid, revision);
    }

    private void assertRevisionTransitionAudit(
            String changeSetPid,
            String eventType,
            String reason,
            long decisionRevision,
            long resultRevision) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_audit_event
                WHERE tenant_id = ? AND env_id = ? AND change_set_pid = ?
                  AND event_type = ? AND result = 'ALLOW'
                  AND metadata ->> 'reason' = ?
                  AND (metadata ->> 'decisionRevision')::bigint = ?
                  AND (metadata ->> 'resultRevision')::bigint = ?
                """, Integer.class,
                testTenant.getId(), MetaContext.getCurrentEnvironmentId(), changeSetPid,
                eventType, reason, decisionRevision, resultRevision);
        assertThat(count).isEqualTo(1);
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

    private void grantDesignerAdmin() {
        grantCommittedPermissionToTestRole(
                MetaPermission.PAGE_DESIGNER_ADMIN,
                "meta",
                "designer",
                "admin",
                "Page Designer Admin");
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
