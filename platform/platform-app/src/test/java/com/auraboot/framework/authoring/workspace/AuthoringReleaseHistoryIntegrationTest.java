package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.authoring.policy.AuthoringCapabilityRegistry;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyPatchRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.OpenSessionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReleaseHistoryView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReleaseView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RevisionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RollbackRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthoringReleaseHistoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AuthoringWorkspaceService workspaceService;

    @Autowired
    private AuthoringGovernanceService governanceService;

    @Autowired
    private AuthoringCapabilityRegistry capabilityRegistry;

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserPermissionService userPermissionService;

    @Autowired
    private ObjectMapper objectMapper;

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
    void controllerSeparatesReleaseHistoryReadFromPublishAndRollbackAdmin() throws Exception {
        PageSchema page = insertPage("normal");
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        patchDensity(opened, "compact");
        prepareAndSubmit(opened.sessionPid(), new RevisionRequest(2));
        ReleaseView release = governanceService.publish(
                opened.changeSetPid(), new RevisionRequest(2));

        grantPublisherRead();
        assertThat(userPermissionService.getUserPermissionCodes(getTestUser().getId()))
                .contains(MetaPermission.PAGE_PUBLISH_READ);
        mockMvc.perform(get("/api/authoring/change-sets/{changeSetPid}/releases",
                        opened.changeSetPid())
                        .queryParam("page", "1")
                        .queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeReleasePid").value(release.releasePid()))
                .andExpect(jsonPath("$.data.rollbackEligibility.eligible").value(false))
                .andExpect(jsonPath("$.data.rollbackEligibility.reasonCode")
                        .value("NO_PREVIOUS_RELEASE"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/authoring/change-sets/{changeSetPid}/releases",
                        opened.changeSetPid()).queryParam("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/authoring/releases/{releasePid}/rollback",
                        release.releasePid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedChannelVersion\":1,\"reason\":\"read only\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void releaseHistoryIsPaginatedAndOwnsImmediatePreviousRollbackEligibility() {
        PageSchema page = insertPage("normal");
        long environmentId = MetaContext.getCurrentEnvironmentId();
        SessionView first = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        ReleaseHistoryView empty = governanceService.releaseHistory(
                first.changeSetPid(), 1, 20);
        assertThat(empty.activeReleasePid()).isNull();
        assertThat(empty.total()).isZero();
        assertThat(empty.rollbackEligibility().reasonCode()).isEqualTo("NO_ACTIVE_RELEASE");
        patchDensity(first, "compact");
        prepareAndSubmit(first.sessionPid(), new RevisionRequest(2));
        ReleaseView releaseOne = governanceService.publish(
                first.changeSetPid(), new RevisionRequest(2));

        SessionView second = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        patchDensity(second, "comfortable");
        prepareAndSubmit(second.sessionPid(), new RevisionRequest(2));
        ReleaseView releaseTwo = governanceService.publish(
                second.changeSetPid(), new RevisionRequest(2));

        ReleaseHistoryView firstPage = governanceService.releaseHistory(
                second.changeSetPid(), 1, 1);
        assertThat(firstPage.resourcePid()).isEqualTo(page.getPid());
        assertThat(firstPage.activeReleasePid()).isEqualTo(releaseTwo.releasePid());
        assertThat(firstPage.previousReleasePid()).isEqualTo(releaseOne.releasePid());
        assertThat(firstPage.channelVersion()).isEqualTo(2);
        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(firstPage.items()).singleElement().satisfies(item -> {
            assertThat(item.releasePid()).isEqualTo(releaseTwo.releasePid());
            assertThat(item.status()).isEqualTo("ACTIVE");
            assertThat(item.reversibility()).isEqualTo("REVERSIBLE");
        });
        assertThat(firstPage.rollbackEligibility().eligible()).isTrue();
        assertThat(firstPage.rollbackEligibility().reasonCode()).isEqualTo("ELIGIBLE");
        assertThat(firstPage.rollbackEligibility().targetReleasePid())
                .isEqualTo(releaseOne.releasePid());
        assertThat(firstPage.rollbackEligibility().reversibleItemCount()).isEqualTo(1);
        assertThat(firstPage.rollbackEligibility().compensatableItemCount()).isZero();
        assertThat(firstPage.rollbackEligibility().forwardOnlyItemCount()).isZero();

        ReleaseHistoryView secondPage = governanceService.releaseHistory(
                second.changeSetPid(), 2, 1);
        assertThat(secondPage.items()).singleElement().satisfies(item -> {
            assertThat(item.releasePid()).isEqualTo(releaseOne.releasePid());
            assertThat(item.status()).isEqualTo("SUPERSEDED");
        });

        governanceService.rollback(
                releaseTwo.releasePid(), new RollbackRequest(2, "regression"));
        ReleaseHistoryView afterRollback = governanceService.releaseHistory(
                second.changeSetPid(), 1, 20);
        assertThat(afterRollback.activeReleasePid()).isEqualTo(releaseOne.releasePid());
        assertThat(afterRollback.previousReleasePid()).isEqualTo(releaseTwo.releasePid());
        assertThat(afterRollback.channelVersion()).isEqualTo(3);
        assertThat(afterRollback.rollbackEligibility().eligible()).isFalse();
        assertThat(afterRollback.rollbackEligibility().reasonCode())
                .isEqualTo("PREVIOUS_RELEASE_UNAVAILABLE");
        assertThat(afterRollback.items())
                .extracting(item -> item.releasePid() + ":" + item.status())
                .containsExactly(
                        releaseTwo.releasePid() + ":ROLLED_BACK",
                        releaseOne.releasePid() + ":ACTIVE");

        try {
            MetaContext.setContext(
                    getTestTenant().getId() + 100_000,
                    getTestUser().getId(),
                    "other-tenant",
                    "other-tenant");
            MetaContext.setEnvironmentId(environmentId);
            assertThatThrownBy(() -> governanceService.releaseHistory(
                    second.changeSetPid(), 1, 20))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("authoring.change-set.not-found");
        } finally {
            applyTestMetaContext();
        }
    }

    @Test
    void releaseHistoryExplainsForwardOnlyChangesWithoutOfferingFakeRollback() {
        PageSchema page = insertPage("normal");
        SessionView first = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        patchDensity(first, "compact");
        prepareAndSubmit(first.sessionPid(), new RevisionRequest(2));
        governanceService.publish(first.changeSetPid(), new RevisionRequest(2));

        SessionView second = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        patchDensity(second, "comfortable");
        prepareAndSubmit(second.sessionPid(), new RevisionRequest(2));
        jdbcTemplate.update("""
                INSERT INTO ab_authoring_change_item (
                    pid, tenant_id, env_id, change_set_id, resource_draft_id,
                    block_id, property_path, operation, new_value, effect_tags,
                    risk_level, route, publish_policy, reversibility,
                    manifest_checksum, base_revision, result_revision, actor_user_id)
                SELECT ?, cs.tenant_id, cs.env_id, cs.id, draft.id,
                       'table-1', '/props/externalMigration', 'ADD', 'true'::jsonb,
                       '["EXTERNAL_SIDE_EFFECT"]'::jsonb,
                       'L3', 'HANDOFF_STUDIO', 'STUDIO_APPROVAL', 'FORWARD_ONLY',
                       ?, 1, 2, ?
                FROM ab_authoring_change_set cs
                JOIN ab_authoring_resource_draft draft
                  ON draft.change_set_id = cs.id
                 AND draft.tenant_id = cs.tenant_id
                 AND draft.env_id = cs.env_id
                WHERE cs.pid = ? AND cs.tenant_id = ? AND cs.env_id = ?
                """, UniqueIdGenerator.generate(),
                capabilityRegistry.find("table").orElseThrow().checksum(), getTestUser().getId(),
                second.changeSetPid(), getTestTenant().getId(),
                MetaContext.getCurrentEnvironmentId());
        ReleaseView release = governanceService.publish(
                second.changeSetPid(), new RevisionRequest(2));

        ReleaseHistoryView history = governanceService.releaseHistory(
                second.changeSetPid(), 1, 20);
        assertThat(history.rollbackEligibility().eligible()).isFalse();
        assertThat(history.rollbackEligibility().reasonCode())
                .isEqualTo("CONTAINS_FORWARD_ONLY_CHANGES");
        assertThat(history.rollbackEligibility().targetReleasePid()).isNotNull();
        assertThat(history.rollbackEligibility().forwardOnlyItemCount()).isEqualTo(1);
        assertThat(history.items()).first().satisfies(item ->
                assertThat(item.reversibility()).isEqualTo("FORWARD_ONLY"));
        assertThatThrownBy(() -> governanceService.rollback(
                release.releasePid(), new RollbackRequest(2, "must not be offered")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.rollback.not-reversible");
    }

    private PageSchema insertPage(String density) {
        ensureModel("test_model");
        String pid = UniqueIdGenerator.generate();
        PageSchema page = new PageSchema();
        page.setPid(pid);
        page.setTenantId(getTestTenant().getId());
        page.setEnvId(MetaContext.getCurrentEnvironmentId());
        page.setPageKey("authoring_release_" + pid.toLowerCase());
        page.setModelCode("test_model");
        page.setName("Authoring Release " + pid);
        page.setKind("list");
        page.setSchemaVersion(2);
        page.setProfile("admin");
        page.setTitle("{\"en-US\":\"Orders\"}");
        page.setLayout("{}");
        page.setBlocks("[{\"id\":\"table-1\",\"blockType\":\"table\"," +
                "\"props\":{\"density\":\"" + density + "\"}}]");
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
                """, Integer.class, getTestTenant().getId(), modelCode);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO ab_meta_model (
                    pid, tenant_id, code, table_name, version, is_current,
                    row_version, status, deleted_flag)
                VALUES (?, ?, ?, ?, 1, TRUE, 1, 'published', FALSE)
                """, UniqueIdGenerator.generate(), getTestTenant().getId(), modelCode,
                "mt_" + modelCode);
    }

    private void patchDensity(SessionView opened, String density) {
        workspaceService.apply(opened.sessionPid(), new ApplyPatchRequest(
                opened.revision(), "table-1", "/props/density", PatchOperation.REPLACE,
                objectMapper.getNodeFactory().textNode(density),
                capabilityRegistry.find("table").orElseThrow().checksum()));
    }

    private void prepareAndSubmit(String sessionPid, RevisionRequest request) {
        governanceService.prepare(sessionPid, request);
        governanceService.submit(sessionPid, request);
    }

    private void grantPublisherRead() {
        grantCommittedPermissionToTestRole(
                MetaPermission.PAGE_PUBLISH_READ,
                "meta",
                "publish",
                "read",
                "Page Release History Read");
        userPermissionService.evictUserPermissions(getTestUser().getId());
    }
}
