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
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ObserveChangeSetRequest;
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
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.TakeoverWriterLeaseRequest;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
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
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthoringWorkspaceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AuthoringWorkspaceService workspaceService;

    @MockitoSpyBean
    private AuthoringWorkspaceRepository workspaceRepository;

    @MockitoSpyBean
    private CommandExecutor commandExecutor;

    @Autowired
    private AuthoringHandoffService handoffService;

    @Autowired
    private AuthoringGovernanceService governanceService;

    @Autowired
    private AuthoringImpactAnalyzer impactAnalyzer;

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
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

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
        mockMvc.perform(get("/api/authoring/new-page-workspace-options"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/authoring/sessions/missing/new-page-workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
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
        mockMvc.perform(get("/api/authoring/sessions/missing/role-preview-targets"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/authoring/sessions/missing/role-structure-preview")
                        .param("rolePid", "role"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/authoring/sessions/missing/synthetic-preview"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/authoring/sessions/missing/identity-simulations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rolePid\":\"role\",\"durationMinutes\":5,\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/authoring/identity-simulations/missing"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/authoring/identity-simulations/missing/end"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/authoring/sessions/missing/ai-patch-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"items\":[]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(
                        "/api/authoring/sessions/missing/ai-patch-proposals/missing"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(
                        "/api/authoring/sessions/missing/ai-patch-proposals/missing/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(
                        "/api/authoring/sessions/missing/ai-patch-proposals/missing/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/authoring/sessions/missing/writer-lease/takeover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"expectedLeaseRevision\":1,\"reason\":\"test\"}"))
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
        mockMvc.perform(post("/api/authoring/sessions/missing/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1}"))
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
        mockMvc.perform(get("/api/authoring/change-sets/missing/releases"))
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
    void ownedAuthoringSessionDeniesDirectCommandBeforeBusinessSideEffects() throws Exception {
        grantDesignerManage();
        grantCommandExecute();
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        Map<String, Long> before = businessWriteCounts();

        doThrow(new IllegalStateException("command executor must not run in authoring preview"))
                .when(commandExecutor)
                .execute(eq("authoring:no-op"), any(CommandExecuteRequest.class));

        mockMvc.perform(post("/api/meta/commands/execute/authoring:no-op")
                        .header(AuthoringBusinessWriteInterceptor.SESSION_HEADER,
                                opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "payload", Map.of()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.context")
                        .value("authoring.preview.business-write-denied"));

        mockMvc.perform(put("/api/dynamic/authoring_missing/record-missing")
                        .header(AuthoringBusinessWriteInterceptor.SESSION_HEADER,
                                opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"published\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.context")
                        .value("authoring.preview.business-write-denied"));

        verify(commandExecutor, never())
                .execute(eq("authoring:no-op"), any(CommandExecuteRequest.class));
        assertThat(businessWriteCounts()).isEqualTo(before);
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
                        .content("{\"expectedRevision\":1,\"expectedLeaseRevision\":1,\"reason\":\"继续处理紧急变更\"}"))
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
    void staleObservedLeaseRevisionAllowsOnlyOneTakeoverWinner() {
        grantDesignerAdmin();
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        SessionView firstObserver = workspaceService.observe(
                opened.changeSetPid(), new ObserveChangeSetRequest(null));
        SessionView secondObserver = workspaceService.observe(
                opened.changeSetPid(), new ObserveChangeSetRequest(null));

        assertThat(firstObserver.writerLease().revision()).isEqualTo(1);
        assertThat(secondObserver.writerLease().revision()).isEqualTo(1);
        SessionView winner = workspaceService.takeoverWriterLease(
                firstObserver.sessionPid(),
                new TakeoverWriterLeaseRequest(
                        firstObserver.revision(), firstObserver.writerLease().revision(),
                        "第一个节点基于 lease r1 接管"));
        assertThat(winner.state()).isEqualTo("ACTIVE");
        assertThat(winner.writerLease().status()).isEqualTo("OWNED");
        assertThat(winner.writerLease().revision()).isEqualTo(2);

        assertThatThrownBy(() -> workspaceService.takeoverWriterLease(
                secondObserver.sessionPid(),
                new TakeoverWriterLeaseRequest(
                        secondObserver.revision(), secondObserver.writerLease().revision(),
                        "第二个节点仍基于 lease r1 接管")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.writer-lease.conflict");

        SessionView loser = workspaceService.get(secondObserver.sessionPid());
        assertThat(loser.state()).isEqualTo("READ_ONLY");
        assertThat(loser.writerLease().status()).isEqualTo("HELD_BY_OTHER_SESSION");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_config_session session
                JOIN ab_authoring_change_set change_set ON change_set.id = session.change_set_id
                WHERE change_set.pid = ? AND session.state = 'ACTIVE'
                """, Integer.class, opened.changeSetPid())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_audit_event
                WHERE tenant_id = ? AND env_id = ? AND change_set_pid = ?
                  AND event_type = 'WRITER_LEASE_TAKEN_OVER'
                """, Integer.class, testTenant.getId(), MetaContext.getCurrentEnvironmentId(),
                opened.changeSetPid())).isEqualTo(1);
    }

    @Test
    void activeWriterHeartbeatRenewsOnlyTheLeaseWithoutChangingTheDraftRevision() throws Exception {
        grantDesignerManage();
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));

        String renewedBody = mockMvc.perform(post(
                        "/api/authoring/sessions/{sessionPid}/writer-lease/renew",
                        opened.sessionPid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionPid").value(opened.sessionPid()))
                .andExpect(jsonPath("$.data.revision").value(opened.revision()))
                .andExpect(jsonPath("$.data.writerLease.status").value("OWNED"))
                .andExpect(jsonPath("$.data.writerLease.revision").value(2))
                .andReturn().getResponse().getContentAsString();

        Instant renewedUntil = Instant.parse(objectMapper.readTree(renewedBody)
                .at("/data/writerLease/leasedUntil").asText());
        assertThat(renewedUntil).isAfter(opened.writerLease().leasedUntil());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT revision FROM ab_authoring_change_set WHERE pid = ?
                """, Long.class, opened.changeSetPid())).isEqualTo(opened.revision());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_change_item ci
                JOIN ab_authoring_change_set cs ON cs.id = ci.change_set_id
                WHERE cs.pid = ?
                """, Integer.class, opened.changeSetPid())).isZero();
    }

    @Test
    void expiredWriterCannotHeartbeatOrWriteAndAnotherTabMustTakeOverWithAudit() throws Exception {
        grantDesignerAdmin();
        grantDesignerManage();
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        jdbcTemplate.update("""
                UPDATE ab_authoring_writer_lease wl
                SET leased_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                FROM ab_authoring_change_set cs
                WHERE wl.change_set_id = cs.id AND cs.pid = ?
                """, opened.changeSetPid());

        assertThat(workspaceService.get(opened.sessionPid()).writerLease().status())
                .isEqualTo("EXPIRED");
        mockMvc.perform(post(
                        "/api/authoring/sessions/{sessionPid}/writer-lease/renew",
                        opened.sessionPid()))
                .andExpect(status().isConflict());
        applyTestMetaContext();
        assertThatThrownBy(() -> workspaceService.apply(
                opened.sessionPid(),
                new ApplyPatchRequest(
                        opened.revision(), "table-1", "/props/density", PatchOperation.REPLACE,
                        objectMapper.getNodeFactory().textNode("compact"),
                        capabilityRegistry.find("table").orElseThrow().checksum())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.writer-lease.expired");

        String observerBody = mockMvc.perform(post(
                        "/api/authoring/change-sets/{changeSetPid}/sessions",
                        opened.changeSetPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interactionContext\":{\"tabId\":\"second-tab\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("READ_ONLY"))
                .andExpect(jsonPath("$.data.writerLease.status").value("EXPIRED"))
                .andReturn().getResponse().getContentAsString();
        String observerSessionPid = objectMapper.readTree(observerBody)
                .at("/data/sessionPid").asText();

        applyTestMetaContext();
        SessionView taken = workspaceService.takeoverWriterLease(
                observerSessionPid,
                new TakeoverWriterLeaseRequest(
                        opened.revision(), opened.writerLease().revision(),
                        "原标签页已离线，接管过期租约"));
        assertThat(taken.state()).isEqualTo("ACTIVE");
        assertThat(taken.writerLease().status()).isEqualTo("OWNED");
        assertThat(taken.writerLease().revision()).isEqualTo(2);
        SessionView originalAfter = workspaceService.get(opened.sessionPid());
        assertThat(originalAfter.state()).isEqualTo("READ_ONLY");
        assertThat(originalAfter.writerLease().status()).isEqualTo("HELD_BY_OTHER_SESSION");

        Integer takeoverEvents = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_audit_event
                WHERE tenant_id = ? AND env_id = ? AND change_set_pid = ?
                  AND event_type = 'WRITER_LEASE_TAKEN_OVER'
                  AND metadata ->> 'reason' = '原标签页已离线，接管过期租约'
                """, Integer.class, testTenant.getId(), MetaContext.getCurrentEnvironmentId(),
                opened.changeSetPid());
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
                .andExpect(jsonPath("$.data.ownership.ownershipScope").value("TENANT"))
                .andExpect(jsonPath("$.data.ownership.sourceOwnershipScope").value("TENANT"))
                .andExpect(jsonPath("$.data.ownership.sourcePagePid").value(page.getPid()))
                .andExpect(jsonPath("$.data.ownership.tenantOverride").value(false))
                .andExpect(jsonPath("$.data.ownership.sourceMutable").value(true))
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
    void studioStructureCreateRequiresAdminPermission() throws Exception {
        grantDesignerManage();
        PageSchema page = insertStructurePage();
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));

        mockMvc.perform(post("/api/authoring/sessions/{sessionPid}/studio-blocks", opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":1,"blockId":"middle","blockType":"form-section",
                                 "parentBlockId":"form-root","manifestChecksum":"%s"}
                                """.formatted(capabilityRegistry.find("form-section")
                                        .orElseThrow().checksum())))
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
                .andExpect(jsonPath("$.data.session.snapshot.blocks[0].blocks[0].dataSource.model")
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
    void studioStructureAdaptersPersistCreateRelocateAndRemoveIntoOneIsolatedChangeSet() throws Exception {
        grantDesignerAdmin();
        PageSchema page = insertStructurePage();
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        String fieldChecksum = capabilityRegistry.find("field").orElseThrow().checksum();
        String sectionChecksum = capabilityRegistry.find("form-section").orElseThrow().checksum();

        mockMvc.perform(post("/api/authoring/sessions/{sessionPid}/studio-blocks", opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":1,"blockId":"middle","blockType":"form-section",
                                 "parentBlockId":"form-root","manifestChecksum":"%s"}
                                """.formatted(sectionChecksum)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session.revision").value(2))
                .andExpect(jsonPath("$.data.session.riskLevel").value("L3"))
                .andExpect(jsonPath("$.data.session.route").value("HANDOFF_STUDIO"))
                .andExpect(jsonPath("$.data.session.publishPolicy").value("STUDIO_APPROVAL"))
                .andExpect(jsonPath("$.data.session.snapshot.blocks[0].blocks[2].id")
                        .value("middle"));

        mockMvc.perform(patch("/api/authoring/sessions/{sessionPid}/studio-relocations",
                        opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":2,"blockId":"field-a",
                                 "targetParentBlockId":"middle",
                                 "manifestChecksum":"%s"}
                                """.formatted(fieldChecksum)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session.revision").value(3))
                .andExpect(jsonPath("$.data.previousValue.parentBlockId").value("left"))
                .andExpect(jsonPath("$.data.savedValue.parentBlockId").value("middle"));

        mockMvc.perform(post("/api/authoring/sessions/{sessionPid}/studio-block-removals",
                        opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":3,"blockId":"field-b","manifestChecksum":"%s"}
                                """.formatted(fieldChecksum)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session.revision").value(4))
                .andExpect(jsonPath("$.data.session.snapshot.blocks[0].blocks[0].blocks.length()")
                        .value(0))
                .andExpect(jsonPath("$.data.session.snapshot.blocks[0].blocks[1].blocks.length()")
                        .value(0))
                .andExpect(jsonPath("$.data.session.snapshot.blocks[0].blocks[2].blocks[0].id")
                        .value("field-a"));

        applyTestMetaContext();
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                SELECT property_path, operation FROM ab_authoring_change_item
                WHERE tenant_id = ? AND env_id = ? AND change_set_id = (
                    SELECT id FROM ab_authoring_change_set
                    WHERE tenant_id = ? AND env_id = ? AND pid = ?)
                ORDER BY result_revision, id
                """, testTenant.getId(), MetaContext.getCurrentEnvironmentId(),
                testTenant.getId(), MetaContext.getCurrentEnvironmentId(), opened.changeSetPid());
        assertThat(items).extracting(item -> item.get("property_path"))
                .containsExactly("/$structure/create", "/$structure/parent", "/$structure/remove");
        assertThat(items).extracting(item -> item.get("operation"))
                .containsExactly("ADD", "MOVE", "REMOVE");
        assertThat(pageSchemaMapper.selectByPid(page.getPid()).getBlocks())
                .contains("field-a", "field-b").doesNotContain("middle");
    }

    @Test
    void studioBatchPersistsTheWholeDocumentPlanWithOneAtomicBoundary() throws Exception {
        grantDesignerAdmin();
        PageSchema page = insertStructurePage();
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        String fieldChecksum = capabilityRegistry.find("field").orElseThrow().checksum();
        String sectionChecksum = capabilityRegistry.find("form-section").orElseThrow().checksum();

        mockMvc.perform(post("/api/authoring/sessions/{sessionPid}/studio-batches",
                        opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision":1,
                                  "creates":[{"blockId":"middle","blockType":"form-section",
                                    "parentBlockId":"form-root","beforeBlockId":null,
                                    "manifestChecksum":"%s"}],
                                  "relocations":[{"blockId":"field-a",
                                    "targetParentBlockId":"middle","beforeBlockId":null,
                                    "manifestChecksum":"%s"}],
                                  "removes":[{"blockId":"field-b","manifestChecksum":"%s"}],
                                  "moves":[],
                                  "patches":[]
                                }
                                """.formatted(sectionChecksum, fieldChecksum, fieldChecksum)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session.revision").value(4))
                .andExpect(jsonPath("$.data.changeItemPids.length()").value(3))
                .andExpect(jsonPath("$.data.session.snapshot.blocks[0].blocks[2].id")
                        .value("middle"))
                .andExpect(jsonPath("$.data.session.snapshot.blocks[0].blocks[2].blocks[0].id")
                        .value("field-a"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void studioBatchRollsBackEarlierStructureWhenALaterPatchIsDenied() throws Exception {
        grantDesignerAdmin();
        PageSchema page = insertStructurePage();
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        String sectionChecksum = capabilityRegistry.find("form-section").orElseThrow().checksum();

        mockMvc.perform(post("/api/authoring/sessions/{sessionPid}/studio-batches",
                        opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision":1,
                                  "creates":[{"blockId":"middle","blockType":"form-section",
                                    "parentBlockId":"form-root","beforeBlockId":null,
                                    "manifestChecksum":"%s"}],
                                  "relocations":[],
                                  "removes":[],
                                  "moves":[],
                                  "patches":[{"blockId":"middle",
                                    "propertyPath":"/props/undeclared","operation":"ADD",
                                    "value":"must-not-persist","manifestChecksum":"%s"}]
                                }
                                """.formatted(sectionChecksum, sectionChecksum)))
                .andExpect(status().isUnprocessableEntity());

        applyTestMetaContext();
        SessionView unchanged = workspaceService.get(opened.sessionPid());
        assertThat(unchanged.revision()).isEqualTo(1);
        assertThat(unchanged.snapshot().toString()).doesNotContain("middle");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_change_item
                WHERE change_set_id = (
                    SELECT id FROM ab_authoring_change_set WHERE pid = ?)
                """, Integer.class, opened.changeSetPid())).isZero();
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
            prepareAndSubmit(opened.sessionPid(), new RevisionRequest(2));
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
            prepareAndSubmit(opened.sessionPid(), new RevisionRequest(2));
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
                .andExpect(jsonPath("$.data.session.ownerUserId").value(String.valueOf(authorUserId)))
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
                        .content("{\"expectedRevision\":2,\"expectedLeaseRevision\":1,\"reason\":\"reviewer must stay read-only\"}"))
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
        prepareAndSubmit(opened.sessionPid(), new RevisionRequest(2));

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
        assertThat(reloaded.snapshot().at("/blocks/0/blocks/0/id").asText())
                .isEqualTo("table-1");
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
        assertThat(result.session().snapshot().at("/blocks/0/blocks/0/props/density").asText())
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
        assertThat(reloaded.snapshot().at("/blocks/0/blocks/0/props/density").asText())
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

        ChangeSetView submitted = prepareAndSubmit(
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
        assertThat(active.snapshot().at("/blocks/0/blocks/0/props/density").asText())
                .isEqualTo("compact");
        assertThat(pageSchemaMapper.selectByPid(page.getPid()).getBlocks())
                .contains("normal").doesNotContain("compact");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ab_authoring_release SET manifest = '{}'::jsonb WHERE pid = ?",
                release.releasePid()))
                .hasMessageContaining("authoring release content is immutable");
    }

    @Test
    void firstChannelFailureRollsBackPreparedReleaseAndItemAtomically() {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        patchDensity(opened, "compact");
        prepareAndSubmit(opened.sessionPid(), new RevisionRequest(2));
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION pg_temp.fail_authoring_channel_insert()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'injected authoring channel insert failure';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_test_fail_authoring_channel_insert
                BEFORE INSERT ON ab_authoring_release_channel
                FOR EACH ROW EXECUTE FUNCTION pg_temp.fail_authoring_channel_insert()
                """);

        assertThatThrownBy(() -> publishInNestedTransaction(opened.changeSetPid(), 2))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("injected authoring channel insert failure");

        assertThat(countReleases(opened.changeSetPid())).isZero();
        assertThat(countReleaseItems(opened.changeSetPid())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_release_channel
                WHERE tenant_id = ? AND env_id = ?
                  AND resource_type = 'PAGE_SCHEMA' AND resource_pid = ?
                """, Integer.class, testTenant.getId(),
                MetaContext.getCurrentEnvironmentId(), page.getPid())).isZero();
        SessionView unchanged = workspaceService.get(opened.sessionPid());
        assertThat(unchanged.changeSetStatus()).isEqualTo("APPROVED");
        assertThat(unchanged.publishState()).isEqualTo("READY");
        assertThat(unchanged.state()).isEqualTo("READ_ONLY");
        assertThat(countPublishAudits(opened.changeSetPid())).isZero();
        assertThat(countFailedPublishAudits(opened.changeSetPid())).isEqualTo(1);
        Map<String, Object> failureAudit = jdbcTemplate.queryForMap("""
                SELECT result, reason_code, resource_pid, metadata::text AS metadata
                FROM ab_authoring_audit_event
                WHERE change_set_pid = ? AND event_type = 'RELEASE_PUBLISH_FAILED'
                """, opened.changeSetPid());
        assertThat(failureAudit.get("result")).isEqualTo("FAIL");
        assertThat(failureAudit.get("reason_code"))
                .isEqualTo("PUBLISH_PERSISTENCE_FAILED");
        assertThat(failureAudit.get("resource_pid")).isEqualTo(page.getPid());
        assertThat((String) failureAudit.get("metadata"))
                .contains("\"expectedRevision\": 2")
                .contains("\"failureCategory\": \"PERSISTENCE\"")
                .doesNotContain("injected")
                .doesNotContain("channel");
    }

    @Test
    void latePublishFailureRestoresPreviousChannelAndReleaseStatesAtomically() {
        PageSchema page = insertPage("normal");
        SessionView first = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        patchDensity(first, "compact");
        prepareAndSubmit(first.sessionPid(), new RevisionRequest(2));
        ReleaseView prior = governanceService.publish(
                first.changeSetPid(), new RevisionRequest(2));

        SessionView second = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        patchDensity(second, "comfortable");
        prepareAndSubmit(second.sessionPid(), new RevisionRequest(2));
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION pg_temp.fail_authoring_change_set_publish()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF OLD.status = 'APPROVED' AND NEW.status = 'PUBLISHED' THEN
                        RAISE EXCEPTION 'injected authoring change set publish failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_test_fail_authoring_change_set_publish
                BEFORE UPDATE OF status ON ab_authoring_change_set
                FOR EACH ROW EXECUTE FUNCTION pg_temp.fail_authoring_change_set_publish()
                """);

        assertThatThrownBy(() -> publishInNestedTransaction(second.changeSetPid(), 2))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("injected authoring change set publish failure");

        AuthoringActiveReleaseResolver.ActiveRelease active =
                activeReleaseResolver.findByResource(
                        testTenant.getId(), MetaContext.getCurrentEnvironmentId(),
                        "PAGE_SCHEMA", page.getPid());
        assertThat(active.releasePid()).isEqualTo(prior.releasePid());
        assertThat(active.channelVersion()).isEqualTo(1);
        assertThat(active.snapshot().at("/blocks/0/blocks/0/props/density").asText())
                .isEqualTo("compact");
        assertThat(countReleases(second.changeSetPid())).isZero();
        assertThat(countReleaseItems(second.changeSetPid())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM ab_authoring_release WHERE pid = ?
                """, String.class, prior.releasePid())).isEqualTo("ACTIVE");
        SessionView unchanged = workspaceService.get(second.sessionPid());
        assertThat(unchanged.changeSetStatus()).isEqualTo("APPROVED");
        assertThat(unchanged.publishState()).isEqualTo("READY");
        assertThat(unchanged.state()).isEqualTo("READ_ONLY");
        assertThat(countPublishAudits(second.changeSetPid())).isZero();
        assertThat(countFailedPublishAudits(second.changeSetPid())).isEqualTo(1);
    }

    @Test
    void publishAuditFailureRollsBackTheCompletedPointerSwitchAtomically() {
        PageSchema page = insertPage("normal");
        SessionView first = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        patchDensity(first, "compact");
        prepareAndSubmit(first.sessionPid(), new RevisionRequest(2));
        ReleaseView prior = governanceService.publish(
                first.changeSetPid(), new RevisionRequest(2));

        SessionView second = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        patchDensity(second, "comfortable");
        prepareAndSubmit(second.sessionPid(), new RevisionRequest(2));
        doThrow(new DataIntegrityViolationException(
                "injected authoring publish audit failure"))
                .when(workspaceRepository)
                .audit(argThat(entry -> "RELEASE_PUBLISHED".equals(entry.eventType())));

        assertThatThrownBy(() -> publishInNestedTransaction(second.changeSetPid(), 2))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("injected authoring publish audit failure");

        AuthoringActiveReleaseResolver.ActiveRelease active =
                activeReleaseResolver.findByResource(
                        testTenant.getId(), MetaContext.getCurrentEnvironmentId(),
                        "PAGE_SCHEMA", page.getPid());
        assertThat(active.releasePid()).isEqualTo(prior.releasePid());
        assertThat(active.channelVersion()).isEqualTo(1);
        assertThat(active.snapshot().at("/blocks/0/blocks/0/props/density").asText())
                .isEqualTo("compact");
        assertThat(countReleases(second.changeSetPid())).isZero();
        assertThat(countReleaseItems(second.changeSetPid())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM ab_authoring_release WHERE pid = ?
                """, String.class, prior.releasePid())).isEqualTo("ACTIVE");
        SessionView unchanged = workspaceService.get(second.sessionPid());
        assertThat(unchanged.changeSetStatus()).isEqualTo("APPROVED");
        assertThat(unchanged.publishState()).isEqualTo("READY");
        assertThat(unchanged.state()).isEqualTo("READ_ONLY");
        assertThat(countPublishAudits(second.changeSetPid())).isZero();
        assertThat(countFailedPublishAudits(second.changeSetPid())).isEqualTo(1);
    }

    @Test
    void runtimeEndpointsExposeOnlyPublishedActiveReleaseSnapshot() throws Exception {
        grantPageSchemaRead();
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        patchDensity(opened, "compact");
        prepareAndSubmit(opened.sessionPid(), new RevisionRequest(2));
        ReleaseView release = governanceService.publish(
                opened.changeSetPid(), new RevisionRequest(2));

        mockMvc.perform(get("/api/pages/key/{pageKey}", page.getPageKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blocks[0].blocks[0].props.density").value("compact"))
                .andExpect(jsonPath("$.data.runtime.source").value("AUTHORING_RELEASE"))
                .andExpect(jsonPath("$.data.runtime.releasePid").value(release.releasePid()))
                .andExpect(jsonPath("$.data.runtime.channelVersion").value(1))
                .andExpect(jsonPath("$.data.runtime.snapshotChecksum").isNotEmpty())
                .andExpect(jsonPath("$.data.runtime.cacheKey")
                        .value(org.hamcrest.Matchers.startsWith("authoring-release:")));
        mockMvc.perform(get("/api/pages/runtime/{pid}", page.getPid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runtime.releasePid").value(release.releasePid()))
                .andExpect(jsonPath("$.data.blocks[0].blocks[0].props.density").value("compact"));
        mockMvc.perform(post("/api/pages/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageKeys\":[\"" + page.getPageKey() + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].runtime.releasePid").value(release.releasePid()))
                .andExpect(jsonPath("$.data[0].blocks[0].blocks[0].props.density")
                        .value("compact"));

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

        ChangeSetView submitted = prepareAndSubmit(
                opened.sessionPid(), new RevisionRequest(2));
        assertThat(submitted.status()).isEqualTo("IN_REVIEW");
        assertThat(submitted.approvalState()).isEqualTo("PENDING");
        assertThatThrownBy(() -> governanceService.approve(
                opened.changeSetPid(), new ReviewRequest(2, "self approval")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("four-eyes-required");
        Map<String, Object> denied = jdbcTemplate.queryForMap("""
                SELECT actor_user_id, result, reason_code, metadata::text AS metadata
                FROM ab_authoring_audit_event
                WHERE change_set_pid = ?
                  AND event_type = 'CHANGE_SET_APPROVAL_DENIED'
                ORDER BY created_at DESC, id DESC LIMIT 1
                """, opened.changeSetPid());
        assertThat(((Number) denied.get("actor_user_id")).longValue())
                .isEqualTo(testUser.getId());
        assertThat(denied.get("result")).isEqualTo("DENY");
        assertThat(denied.get("reason_code")).isEqualTo("FOUR_EYES_REQUIRED");
        assertThat((String) denied.get("metadata"))
                .contains("\"expectedRevision\": 2")
                .contains("\"failureCategory\": \"REJECTED\"")
                .doesNotContain("self approval")
                .doesNotContain("four-eyes-required");
        assertThat(approvalStatus(opened.changeSetPid(), 2)).isEqualTo("PENDING");

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
    void pageLocalL2ChangeCannotBypassReviewOrBeDowngradedByLaterL0Items() {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        PatchResult lowRisk = patchDensity(opened, "compact");
        ObjectNode defaultFilter = objectMapper.createObjectNode().put("status", "OPEN");
        PatchResult pageLocal = workspaceService.apply(
                opened.sessionPid(),
                new ApplyPatchRequest(
                        lowRisk.session().revision(), "table-1", "/props/defaultFilter",
                        PatchOperation.ADD, defaultFilter,
                        capabilityRegistry.find("table").orElseThrow().checksum()));

        assertThat(pageLocal.session().riskLevel()).isEqualTo("L2");
        assertThat(pageLocal.session().route()).isEqualTo("GUIDED_INLINE");
        assertThat(pageLocal.session().publishPolicy()).isEqualTo("REQUIRED_REVIEW");
        PatchResult laterLowRisk = workspaceService.apply(
                opened.sessionPid(),
                new ApplyPatchRequest(
                        pageLocal.session().revision(), "table-1", "/layout/span",
                        PatchOperation.ADD, objectMapper.getNodeFactory().numberNode(12),
                        capabilityRegistry.find("table").orElseThrow().checksum()));
        assertThat(laterLowRisk.session().riskLevel()).isEqualTo("L2");
        assertThat(laterLowRisk.session().publishPolicy()).isEqualTo("REQUIRED_REVIEW");

        ChangeSetView submitted = prepareAndSubmit(
                opened.sessionPid(), new RevisionRequest(laterLowRisk.session().revision()));
        assertThat(submitted.status()).isEqualTo("IN_REVIEW");
        assertThat(submitted.approvalState()).isEqualTo("PENDING");
        assertThat(submitted.publishState()).isEqualTo("DRAFT");
        assertThatThrownBy(() -> governanceService.approve(
                opened.changeSetPid(),
                new ReviewRequest(laterLowRisk.session().revision(), "owner bypass")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.approval.four-eyes-required");
        assertThatThrownBy(() -> governanceService.publish(
                opened.changeSetPid(), new RevisionRequest(laterLowRisk.session().revision())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.workflow.invalid-state");
    }

    @Test
    void unknownImpactCannotSubmitUntilTheExactRevisionIsPrepared() {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        PatchResult patched = patchDensity(opened, "compact");

        assertThatThrownBy(() -> governanceService.submit(
                opened.sessionPid(), new RevisionRequest(patched.session().revision())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.submit.not-ready");

        SessionView prepared = governanceService.prepare(
                opened.sessionPid(), new RevisionRequest(patched.session().revision()));
        assertThat(prepared.changeSetStatus()).isEqualTo("DRAFT");
        assertThat(prepared.validationState()).isEqualTo("VALID");
        assertThat(prepared.impactState()).isEqualTo("KNOWN");
        assertThat(prepared.impact()).isNotNull();
        assertThat(prepared.impact().revision()).isEqualTo(2);
        assertThat(prepared.impact().dependencies()).extracting(dependency ->
                dependency.resourceCode()).containsExactly("test_model");

        ChangeSetView submitted = governanceService.submit(
                opened.sessionPid(), new RevisionRequest(prepared.revision()));
        assertThat(submitted.status()).isEqualTo("APPROVED");
        assertThat(submitted.impactState()).isEqualTo("KNOWN");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE ab_authoring_impact_run SET dependencies = '[]'::jsonb
                WHERE change_set_id = (
                    SELECT id FROM ab_authoring_change_set WHERE pid = ?)
                """, opened.changeSetPid()))
                .hasMessageContaining("authoring history is append-only");
    }

    @Test
    void missingDependencyFailsClosedAndKeepsTheDraftEditable() {
        PageSchema page = insertPage("normal");
        page.setModelCode("missing_model");
        pageSchemaMapper.updateById(page);
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        PatchResult patched = patchDensity(opened, "compact");

        SessionView prepared = governanceService.prepare(
                opened.sessionPid(), new RevisionRequest(patched.session().revision()));

        assertThat(prepared.state()).isEqualTo("ACTIVE");
        assertThat(prepared.changeSetStatus()).isEqualTo("DRAFT");
        assertThat(prepared.validationState()).isEqualTo("VALID");
        assertThat(prepared.impactState()).isEqualTo("FAILED");
        assertThat(prepared.impact().failureCode()).isEqualTo("DEPENDENCY_MISSING");
        assertThat(prepared.impact().dependencies()).isEmpty();
        assertThatThrownBy(() -> governanceService.submit(
                opened.sessionPid(), new RevisionRequest(prepared.revision())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.submit.not-ready");
    }

    @Test
    void dependencyDriftStalesValidationImpactAndPublishEligibility() {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        PatchResult patched = patchDensity(opened, "compact");
        SessionView prepared = governanceService.prepare(
                opened.sessionPid(), new RevisionRequest(patched.session().revision()));
        assertThat(prepared.impactState()).isEqualTo("KNOWN");

        jdbcTemplate.update("""
                UPDATE ab_meta_model
                SET row_version = row_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND code = 'test_model'
                  AND is_current = TRUE AND deleted_flag = FALSE
                """, testTenant.getId());

        assertThatThrownBy(() -> governanceService.submit(
                opened.sessionPid(), new RevisionRequest(prepared.revision())))
                .isInstanceOf(AuthoringStaleStateException.class)
                .hasMessageContaining("authoring.validation.dependency-stale");
        SessionView stale = workspaceService.get(opened.sessionPid());
        assertThat(stale.validationState()).isEqualTo("STALE");
        assertThat(stale.impactState()).isEqualTo("STALE");
        assertThat(stale.publishState()).isEqualTo("DRAFT");
    }

    @Test
    void dictionaryItemDriftInvalidatesAnExactRevisionImpactResult() {
        PageSchema page = insertPage("normal");
        ensureDictionary("authoring_test_status");
        page.setBlocks("""
                [{"id":"table-1","blockType":"table",
                  "props":{"density":"normal"},
                  "columns":[{"field":"status","dictCode":"authoring_test_status"}]}]
                """);
        pageSchemaMapper.updateById(page);
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        PatchResult patched = patchDensity(opened, "compact");
        SessionView prepared = governanceService.prepare(
                opened.sessionPid(), new RevisionRequest(patched.session().revision()));

        assertThat(prepared.impact().dependencies()).extracting(dependency ->
                dependency.resourceType() + ":" + dependency.resourceCode())
                .containsExactly("DICTIONARY:authoring_test_status", "MODEL:test_model");
        jdbcTemplate.update("""
                UPDATE ab_dict_item
                SET label = 'Closed updated', updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND dict_id = (
                    SELECT id FROM ab_dict
                    WHERE tenant_id = ? AND code = 'authoring_test_status'
                      AND is_current = TRUE AND deleted_flag = FALSE)
                  AND value = 'closed'
                """, testTenant.getId(), testTenant.getId());

        assertThatThrownBy(() -> governanceService.submit(
                opened.sessionPid(), new RevisionRequest(prepared.revision())))
                .isInstanceOf(AuthoringStaleStateException.class)
                .hasMessageContaining("authoring.validation.dependency-stale");
        SessionView stale = workspaceService.get(opened.sessionPid());
        assertThat(stale.validationState()).isEqualTo("STALE");
        assertThat(stale.impactState()).isEqualTo("STALE");
    }

    @Test
    void commandDefinitionDriftInvalidatesAnExactRevisionImpactResult() {
        PageSchema page = insertPage("normal");
        ensureCommand("authoring:test_approve", "test_model");
        page.setBlocks("""
                [{"id":"table-1","blockType":"table",
                  "props":{"density":"normal"},
                  "buttons":[{"code":"approve","command":"authoring:test_approve"}]}]
                """);
        pageSchemaMapper.updateById(page);
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        PatchResult patched = patchDensity(opened, "compact");
        SessionView prepared = governanceService.prepare(
                opened.sessionPid(), new RevisionRequest(patched.session().revision()));

        assertThat(prepared.impact().dependencies()).extracting(dependency ->
                dependency.resourceType() + ":" + dependency.resourceCode())
                .containsExactly("COMMAND:authoring:test_approve", "MODEL:test_model");
        jdbcTemplate.update("""
                UPDATE ab_command_definition
                SET row_version = row_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND code = 'authoring:test_approve'
                  AND is_current = TRUE AND deleted_flag = FALSE
                """, testTenant.getId());

        assertThatThrownBy(() -> governanceService.submit(
                opened.sessionPid(), new RevisionRequest(prepared.revision())))
                .isInstanceOf(AuthoringStaleStateException.class)
                .hasMessageContaining("authoring.validation.dependency-stale");
        assertThat(workspaceService.get(opened.sessionPid()).impactState()).isEqualTo("STALE");
    }

    @Test
    void namedQueryFieldDriftInvalidatesAnExactRevisionImpactResult() {
        PageSchema page = insertPage("normal");
        ensureNamedQuery("authoring_order_metrics");
        page.setBlocks("""
                [{"id":"table-1","blockType":"table",
                  "props":{"density":"normal"},
                  "dataSource":{"type":"namedQuery",
                    "queryCode":"authoring_order_metrics"}}]
                """);
        pageSchemaMapper.updateById(page);
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        PatchResult patched = patchDensity(opened, "compact");
        SessionView prepared = governanceService.prepare(
                opened.sessionPid(), new RevisionRequest(patched.session().revision()));

        assertThat(prepared.impact().dependencies()).extracting(dependency ->
                dependency.resourceType() + ":" + dependency.resourceCode())
                .containsExactly("MODEL:test_model", "NAMED_QUERY:authoring_order_metrics");
        jdbcTemplate.update("""
                UPDATE ab_named_query_field
                SET sortable = NOT sortable, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND query_code = 'authoring_order_metrics'
                  AND field_code = 'total'
                """, testTenant.getId());

        assertThatThrownBy(() -> governanceService.submit(
                opened.sessionPid(), new RevisionRequest(prepared.revision())))
                .isInstanceOf(AuthoringStaleStateException.class)
                .hasMessageContaining("authoring.validation.dependency-stale");
        assertThat(workspaceService.get(opened.sessionPid()).impactState()).isEqualTo("STALE");
    }

    @Test
    void archivedNamedQueryFailsClosedAndKeepsTheDraftEditable() {
        PageSchema page = insertPage("normal");
        ensureNamedQuery("authoring_archived_metrics");
        jdbcTemplate.update("""
                UPDATE ab_named_query
                SET status = 'archived', updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND code = 'authoring_archived_metrics'
                """, testTenant.getId());
        page.setBlocks("""
                [{"id":"table-1","blockType":"table",
                  "props":{"density":"normal"},
                  "dataSource":{"type":"namedQuery",
                    "queryCode":"authoring_archived_metrics"}}]
                """);
        pageSchemaMapper.updateById(page);
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        PatchResult patched = patchDensity(opened, "compact");

        SessionView prepared = governanceService.prepare(
                opened.sessionPid(), new RevisionRequest(patched.session().revision()));

        assertThat(prepared.state()).isEqualTo("ACTIVE");
        assertThat(prepared.changeSetStatus()).isEqualTo("DRAFT");
        assertThat(prepared.validationState()).isEqualTo("VALID");
        assertThat(prepared.impactState()).isEqualTo("FAILED");
        assertThat(prepared.impact().failureCode()).isEqualTo("DEPENDENCY_MISSING");
        assertThatThrownBy(() -> governanceService.submit(
                opened.sessionPid(), new RevisionRequest(prepared.revision())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.submit.not-ready");
    }

    @Test
    void navigatedPageDriftInvalidatesAnExactEnvironmentRevisionImpactResult() {
        PageSchema target = insertPage("normal");
        PageSchema page = insertPage("normal");
        page.setBlocks("""
                [{"id":"table-1","blockType":"table",
                  "props":{"density":"normal"},
                  "buttons":[{"code":"open","action":{
                    "type":"navigate","to":"%s"}}]}]
                """.formatted(target.getPageKey()));
        pageSchemaMapper.updateById(page);
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        PatchResult patched = patchDensity(opened, "compact");
        SessionView prepared = governanceService.prepare(
                opened.sessionPid(), new RevisionRequest(patched.session().revision()));

        assertThat(prepared.impact().dependencies()).extracting(dependency ->
                dependency.resourceType() + ":" + dependency.resourceCode())
                .containsExactly("MODEL:test_model", "PAGE:" + target.getPageKey());
        jdbcTemplate.update("""
                UPDATE ab_page_schema
                SET row_version = row_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND env_id = ? AND pid = ?
                  AND is_current = TRUE AND deleted_flag = FALSE
                """, testTenant.getId(), MetaContext.getCurrentEnvironmentId(), target.getPid());

        assertThatThrownBy(() -> governanceService.submit(
                opened.sessionPid(), new RevisionRequest(prepared.revision())))
                .isInstanceOf(AuthoringStaleStateException.class)
                .hasMessageContaining("authoring.validation.dependency-stale");
        assertThat(workspaceService.get(opened.sessionPid()).impactState()).isEqualTo("STALE");
    }

    @Test
    void navigatedPageActiveReleaseSwitchInvalidatesTheDependentRevision() {
        PageSchema target = insertPage("normal");
        SessionView targetFirst = workspaceService.open(
                new OpenSessionRequest(target.getPid(), null));
        patchDensity(targetFirst, "compact");
        prepareAndSubmit(targetFirst.sessionPid(), new RevisionRequest(2));
        governanceService.publish(targetFirst.changeSetPid(), new RevisionRequest(2));

        PageSchema page = insertPage("normal");
        page.setBlocks("""
                [{"id":"table-1","blockType":"table",
                  "props":{"density":"normal"},
                  "buttons":[{"code":"open","action":{
                    "type":"navigate","to":"%s"}}]}]
                """.formatted(target.getPageKey()));
        pageSchemaMapper.updateById(page);
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        PatchResult patched = patchDensity(opened, "compact");
        SessionView prepared = governanceService.prepare(
                opened.sessionPid(), new RevisionRequest(patched.session().revision()));

        SessionView targetSecond = workspaceService.open(
                new OpenSessionRequest(target.getPid(), null));
        patchDensity(targetSecond, "comfortable");
        prepareAndSubmit(targetSecond.sessionPid(), new RevisionRequest(2));
        governanceService.publish(targetSecond.changeSetPid(), new RevisionRequest(2));

        assertThatThrownBy(() -> governanceService.submit(
                opened.sessionPid(), new RevisionRequest(prepared.revision())))
                .isInstanceOf(AuthoringStaleStateException.class)
                .hasMessageContaining("authoring.validation.dependency-stale");
        assertThat(workspaceService.get(opened.sessionPid()).impactState()).isEqualTo("STALE");
    }

    @Test
    void realDependencyQueryTimeoutFailsClosedWithoutPoisoningTheOuterTransaction()
            throws Exception {
        try (Connection blocker = dataSource.getConnection();
                Statement statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute("LOCK TABLE ab_meta_model IN ACCESS EXCLUSIVE MODE");

            AuthoringImpactAnalyzer.ImpactResult result = impactAnalyzer.analyze(
                    testTenant.getId(), MetaContext.getCurrentEnvironmentId(),
                    objectMapper.readTree("""
                            {"pid":"timeout-page","modelCode":"blocked_model","blocks":[]}
                            """));

            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.failureCode()).isEqualTo("ANALYSIS_TIMEOUT");
            assertThat(result.dependencies()).isEmpty();
            assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            blocker.rollback();
        }
    }

    @Test
    void invalidRevisionStaysEditableAndAValidNewRevisionGetsItsOwnValidationFact() {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        PatchResult invalidPatch = workspaceService.apply(
                opened.sessionPid(),
                new ApplyPatchRequest(
                        opened.revision(), "table-1", "/props/defaultFilter",
                        PatchOperation.ADD,
                        objectMapper.getNodeFactory().textNode("status = OPEN secret-value"),
                        capabilityRegistry.find("table").orElseThrow().checksum()));

        SessionView invalid = governanceService.prepare(
                opened.sessionPid(), new RevisionRequest(invalidPatch.session().revision()));

        assertThat(invalid.changeSetStatus()).isEqualTo("DRAFT");
        assertThat(invalid.validationState()).isEqualTo("INVALID");
        assertThat(invalid.publishState()).isEqualTo("DRAFT");
        SessionView invalidSession = workspaceService.get(opened.sessionPid());
        assertThat(invalidSession.state()).isEqualTo("ACTIVE");
        assertThat(invalidSession.validation()).isNotNull();
        assertThat(invalidSession.validation().revision()).isEqualTo(2);
        assertThat(invalidSession.validation().errorCount()).isEqualTo(1);
        assertThat(invalidSession.validation().issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("DEFAULT_FILTER_INVALID");
            assertThat(issue.blockId()).isEqualTo("table-1");
            assertThat(issue.propertyPath()).isEqualTo("/props/defaultFilter");
        });
        String invalidIssues = jdbcTemplate.queryForObject("""
                SELECT issues::text FROM ab_authoring_validation_run
                WHERE change_set_id = (
                    SELECT id FROM ab_authoring_change_set WHERE pid = ?)
                  AND change_set_revision = 2 AND status = 'INVALID'
                """, String.class, opened.changeSetPid());
        assertThat(invalidIssues).doesNotContain("secret-value");

        ObjectNode structuredFilter = objectMapper.createObjectNode().put("status", "OPEN");
        PatchResult fixed = workspaceService.apply(
                opened.sessionPid(),
                new ApplyPatchRequest(
                        invalidSession.revision(), "table-1", "/props/defaultFilter",
                        PatchOperation.REPLACE, structuredFilter,
                        capabilityRegistry.find("table").orElseThrow().checksum()));
        assertThat(fixed.session().validationState()).isEqualTo("UNVALIDATED");
        assertThat(fixed.session().validation()).isNull();

        ChangeSetView submitted = prepareAndSubmit(
                opened.sessionPid(), new RevisionRequest(fixed.session().revision()));
        assertThat(submitted.status()).isEqualTo("IN_REVIEW");
        assertThat(submitted.validationState()).isEqualTo("VALID");
        SessionView reviewed = workspaceService.get(opened.sessionPid());
        assertThat(reviewed.state()).isEqualTo("READ_ONLY");
        assertThat(reviewed.validation().revision()).isEqualTo(3);
        assertThat(reviewed.validation().status()).isEqualTo("VALID");
        assertThat(reviewed.validation().errorCount()).isZero();
        Integer runCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_validation_run
                WHERE change_set_id = (
                    SELECT id FROM ab_authoring_change_set WHERE pid = ?)
                """, Integer.class, opened.changeSetPid());
        assertThat(runCount).isEqualTo(2);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE ab_authoring_validation_run SET issues = '[]'::jsonb
                WHERE change_set_id = (
                    SELECT id FROM ab_authoring_change_set WHERE pid = ?)
                """, opened.changeSetPid()))
                .hasMessageContaining("authoring history is append-only");
    }

    @Test
    void ownerWithdrawalCreatesANewEditableRevisionAndStalesThePendingReview() {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        workspaceService.apply(opened.sessionPid(), new ApplyPatchRequest(
                1, "table-1", "/props/pageSize", PatchOperation.ADD,
                objectMapper.getNodeFactory().numberNode(20),
                capabilityRegistry.find("table").orElseThrow().checksum()));
        prepareAndSubmit(opened.sessionPid(), new RevisionRequest(2));

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
        prepareAndSubmit(opened.sessionPid(), new RevisionRequest(2));

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
        Map<String, Object> rejectedAudit = jdbcTemplate.queryForMap("""
                SELECT result, reason_code, metadata::text AS metadata
                FROM ab_authoring_audit_event
                WHERE change_set_pid = ? AND event_type = 'RELEASE_PUBLISH_FAILED'
                ORDER BY created_at DESC, id DESC LIMIT 1
                """, opened.changeSetPid());
        assertThat(rejectedAudit.get("result")).isEqualTo("FAIL");
        assertThat(rejectedAudit.get("reason_code"))
                .isEqualTo("AUTHORING_WORKFLOW_INVALID_STATE");
        assertThat((String) rejectedAudit.get("metadata"))
                .contains("\"failureCategory\": \"REJECTED\"")
                .doesNotContain("authoring.workflow.invalid-state");
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
        prepareAndSubmit(opened.sessionPid(), new RevisionRequest(2));

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

        assertThat(highRisk.session().riskLevel()).isEqualTo("L3");
        assertThat(highRisk.session().publishPolicy()).isEqualTo("STUDIO_APPROVAL");

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
        assertThat(split.sourceSession().snapshot()
                .at("/blocks/0/blocks/0/props/density").asText())
                .isEqualTo("compact");
        assertThat(split.sourceSession().snapshot()
                .at("/blocks/0/blocks/0/dataSource").isMissingNode())
                .isTrue();
        assertThat(split.targetSession().revision()).isEqualTo(2);
        assertThat(split.targetSession().riskLevel()).isEqualTo("L3");
        assertThat(split.targetSession().publishPolicy()).isEqualTo("STUDIO_APPROVAL");
        assertThat(split.targetSession().snapshot()
                .at("/blocks/0/blocks/0/props/density").asText())
                .isEqualTo("normal");
        assertThat(split.targetSession().snapshot()
                .at("/blocks/0/blocks/0/dataSource/model").asText())
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

        assertThat(prepareAndSubmit(
                split.sourceSession().sessionPid(), new RevisionRequest(4)).status())
                .isEqualTo("APPROVED");
        assertThat(prepareAndSubmit(
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
        assertThat(unchanged.snapshot().at("/blocks/0/blocks/0/props/density").asText())
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

        prepareAndSubmit(first.sessionPid(), new RevisionRequest(2));
        governanceService.publish(first.changeSetPid(), new RevisionRequest(2));

        assertThatThrownBy(() -> prepareAndSubmit(
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
        prepareAndSubmit(first.sessionPid(), new RevisionRequest(2));
        ReleaseView releaseOne = governanceService.publish(
                first.changeSetPid(), new RevisionRequest(2));

        SessionView second = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        assertThat(second.snapshot().at("/blocks/0/blocks/0/props/density").asText())
                .isEqualTo("compact");
        patchDensity(second, "comfortable");
        prepareAndSubmit(second.sessionPid(), new RevisionRequest(2));
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
        assertThat(active.snapshot().at("/blocks/0/blocks/0/props/density").asText())
                .isEqualTo("compact");
    }

    private PageSchema insertPage(String density) {
        ensureModel("test_model");
        ensureModel("payments");
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

    private void ensureModel(String modelCode) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_meta_model
                WHERE tenant_id = ? AND code = ? AND is_current = TRUE
                  AND deleted_flag = FALSE
                """, Integer.class, testTenant.getId(), modelCode);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO ab_meta_model (
                    pid, tenant_id, code, table_name, version, is_current,
                    row_version, status, deleted_flag)
                VALUES (?, ?, ?, ?, 1, TRUE, 1, 'published', FALSE)
                """, UniqueIdGenerator.generate(), testTenant.getId(), modelCode,
                "mt_" + modelCode);
    }

    private void ensureDictionary(String dictCode) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_dict
                WHERE tenant_id = ? AND code = ? AND is_current = TRUE
                  AND deleted_flag = FALSE
                """, Integer.class, testTenant.getId(), dictCode);
        if (count != null && count > 0) {
            return;
        }
        String dictPid = UniqueIdGenerator.generate();
        jdbcTemplate.update("""
                INSERT INTO ab_dict (
                    pid, tenant_id, code, name, dict_type, status,
                    version, is_current, extension, deleted_flag)
                VALUES (?, ?, ?, 'Authoring status', 'dynamic', 'published',
                        1, TRUE, '{}'::jsonb, FALSE)
                """, dictPid, testTenant.getId(), dictCode);
        jdbcTemplate.update("""
                INSERT INTO ab_dict_item (
                    pid, tenant_id, dict_id, value, label, sort_no, status, source)
                VALUES (?, ?, (
                    SELECT id FROM ab_dict WHERE pid = ?),
                    'closed', 'Closed', 10, 'enabled', 'test')
                """, UniqueIdGenerator.generate(), testTenant.getId(), dictPid);
    }

    private void ensureCommand(String commandCode, String modelCode) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_command_definition
                WHERE tenant_id = ? AND code = ? AND is_current = TRUE
                  AND deleted_flag = FALSE
                """, Integer.class, testTenant.getId(), commandCode);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO ab_command_definition (
                    pid, tenant_id, code, display_name, model_code,
                    input_schema, target_models, execution_config, extension,
                    version, is_current, row_version, status, deleted_flag)
                VALUES (?, ?, ?, 'Authoring approve', ?,
                        '{}'::jsonb, '[]'::jsonb, '{"type":"state_transition"}'::jsonb,
                        '{}'::jsonb, 1, TRUE, 1, 'published', FALSE)
                """, UniqueIdGenerator.generate(), testTenant.getId(), commandCode, modelCode);
    }

    private void ensureNamedQuery(String queryCode) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_named_query
                WHERE tenant_id = ? AND code = ?
                """, Integer.class, testTenant.getId(), queryCode);
        if (count != null && count > 0) {
            return;
        }
        String queryPid = UniqueIdGenerator.generate();
        jdbcTemplate.update("""
                INSERT INTO ab_named_query (
                    pid, tenant_id, code, title, from_sql, base_where,
                    status, current_version, policy)
                VALUES (?, ?, ?, 'Authoring order metrics',
                        'SELECT 1 AS total', '[]'::jsonb,
                        'published', 1, '{}'::jsonb)
                """, queryPid, testTenant.getId(), queryCode);
        jdbcTemplate.update("""
                INSERT INTO ab_named_query_field (
                    tenant_id, query_code, field_code, column_expr,
                    data_type, operators, sortable, searchable, sort_order, source)
                VALUES (?, ?, 'total', 'total', 'integer',
                        '["EQ"]'::jsonb, TRUE, TRUE, 10, 'test')
                """, testTenant.getId(), queryCode);
        jdbcTemplate.update("""
                INSERT INTO ab_named_query_version (
                    pid, tenant_id, query_code, version_no, from_sql,
                    fields_snapshot, status)
                VALUES (?, ?, ?, 1, 'SELECT 1 AS total',
                        '[{"fieldCode":"total"}]'::jsonb, 'published')
                """, UniqueIdGenerator.generate(), testTenant.getId(), queryCode);
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

    private PageSchema insertStructurePage() {
        PageSchema page = insertPage("normal");
        page.setKind("form");
        page.setSchemaVersion(3);
        page.setBlocks("""
                [{"id":"form-root","blockType":"form","blocks":[
                  {"id":"left","blockType":"form-section","blocks":[
                    {"id":"field-a","blockType":"field"}
                  ]},
                  {"id":"right","blockType":"form-section","blocks":[
                    {"id":"field-b","blockType":"field"}
                  ]}
                ]}]
                """);
        pageSchemaMapper.updateById(page);
        return page;
    }

    private void publishInNestedTransaction(String changeSetPid, long expectedRevision) {
        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        nested.executeWithoutResult(ignored -> governanceService.publish(
                changeSetPid, new RevisionRequest(expectedRevision)));
    }

    private int countReleases(String changeSetPid) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_release release
                JOIN ab_authoring_change_set change_set
                  ON change_set.id = release.change_set_id
                WHERE change_set.pid = ?
                """, Integer.class, changeSetPid);
        return value == null ? 0 : value;
    }

    private int countReleaseItems(String changeSetPid) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_release_item item
                JOIN ab_authoring_release release ON release.id = item.release_id
                JOIN ab_authoring_change_set change_set
                  ON change_set.id = release.change_set_id
                WHERE change_set.pid = ?
                """, Integer.class, changeSetPid);
        return value == null ? 0 : value;
    }

    private int countPublishAudits(String changeSetPid) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_audit_event
                WHERE change_set_pid = ? AND event_type = 'RELEASE_PUBLISHED'
                """, Integer.class, changeSetPid);
        return value == null ? 0 : value;
    }

    private int countFailedPublishAudits(String changeSetPid) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_audit_event
                WHERE change_set_pid = ? AND event_type = 'RELEASE_PUBLISH_FAILED'
                """, Integer.class, changeSetPid);
        return value == null ? 0 : value;
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

    private ChangeSetView prepareAndSubmit(String sessionPid, RevisionRequest request) {
        governanceService.prepare(sessionPid, request);
        return governanceService.submit(sessionPid, request);
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

    private void grantCommandExecute() {
        grantCommittedPermissionToTestRole(
                MetaPermission.COMMAND_EXECUTE,
                "meta",
                "command",
                "execute",
                "Command Execute");
        userPermissionService.evictUserPermissions(getTestUser().getId());
    }

    private Map<String, Long> businessWriteCounts() {
        return Map.of(
                "announcement", jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ab_announcement", Long.class),
                "outbox", jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ab_outbox", Long.class),
                "behaviorOutcomeOutbox", jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ab_behavior_outcome_outbox", Long.class),
                "imMessage", jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ab_im_message", Long.class),
                "webhookDelivery", jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ab_webhook_delivery_log", Long.class));
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
