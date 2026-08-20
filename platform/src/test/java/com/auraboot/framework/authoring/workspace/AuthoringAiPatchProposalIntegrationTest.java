package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.policy.AuthoringCapabilityRegistry;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.AiPatchProposalItemRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.AiPatchProposalView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyAiPatchProposalRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyAiPatchProposalResult;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyPatchRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.CreateAiPatchProposalRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.OpenSessionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real-PostgreSQL proof that AI proposals cannot bypass the governed authoring aggregate. */
class AuthoringAiPatchProposalIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AuthoringAiPatchProposalService proposalService;
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

    @Test
    void proposalDoesNotMutateDraftAndHumanApplyCreatesOrdinaryChangeItems() {
        PageSchema page = insertPage();
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));

        AiPatchProposalView proposal = proposalService.create(
                opened.sessionPid(), proposalRequest(opened));

        assertThat(proposal.status()).isEqualTo("PROPOSED");
        assertThat(proposal.typedPatchOnly()).isTrue();
        assertThat(proposal.requiresHumanApproval()).isTrue();
        assertThat(proposal.items()).hasSize(2);
        assertThat(proposal.items().getFirst().previousValue().asText()).isEqualTo("normal");
        assertThat(proposal.items().getFirst().value().asText()).isEqualTo("compact");
        assertThat(proposal.aggregateRisk()).isEqualTo("L3");
        assertThat(proposal.aggregateRoute()).isEqualTo("HANDOFF_STUDIO");
        assertThat(proposal.publishPolicy()).isEqualTo("STUDIO_APPROVAL");
        assertThat(workspaceService.get(opened.sessionPid()).revision()).isEqualTo(1);
        assertThat(changeItemCount(opened.changeSetPid())).isZero();
        assertThat(proposalCount(opened.sessionPid(), "PROPOSED")).isEqualTo(1);

        ApplyAiPatchProposalResult applied = proposalService.apply(
                opened.sessionPid(),
                proposal.proposalPid(),
                new ApplyAiPatchProposalRequest(opened.revision()));

        assertThat(applied.proposal().status()).isEqualTo("APPLIED");
        assertThat(applied.proposal().resultRevision()).isEqualTo(3);
        assertThat(applied.session().revision()).isEqualTo(3);
        assertThat(applied.session().snapshot().at("/blocks/0/blocks/0/props/density").asText())
                .isEqualTo("compact");
        assertThat(applied.session().snapshot().at("/blocks/0/blocks/0/dataSource/model").asText())
                .isEqualTo("payments");
        assertThat(applied.session().validationState()).isEqualTo("UNVALIDATED");
        assertThat(applied.session().impactState()).isEqualTo("UNKNOWN");
        assertThat(changeItemCount(opened.changeSetPid())).isEqualTo(2);
        assertThat(auditCount(opened.changeSetPid(), "AI_PATCH_PROPOSAL_CREATED")).isEqualTo(1);
        assertThat(auditCount(opened.changeSetPid(), "AI_PATCH_PROPOSAL_ITEM_APPLIED"))
                .isEqualTo(2);
        assertThat(auditCount(opened.changeSetPid(), "AI_PATCH_PROPOSAL_APPLIED")).isEqualTo(1);
        assertThat(auditMetadata(opened.changeSetPid(), "AI_PATCH_PROPOSAL_CREATED"))
                .doesNotContain("payments")
                .doesNotContain("compact");
    }

    @Test
    void staleProposalFailsClosedWithoutPartiallyApplyingItsItems() {
        PageSchema page = insertPage();
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        AiPatchProposalView proposal = proposalService.create(
                opened.sessionPid(), proposalRequest(opened));

        SessionView advanced = workspaceService.applyStudio(
                opened.sessionPid(),
                new ApplyPatchRequest(
                        opened.revision(),
                        "table-1",
                        "/title",
                        PatchOperation.ADD,
                        objectMapper.getNodeFactory().textNode("Current title"),
                        tableManifest())).session();

        assertThatThrownBy(() -> proposalService.apply(
                opened.sessionPid(),
                proposal.proposalPid(),
                new ApplyAiPatchProposalRequest(opened.revision())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.revision.conflict");

        SessionView unchanged = workspaceService.get(opened.sessionPid());
        assertThat(unchanged.revision()).isEqualTo(advanced.revision());
        assertThat(unchanged.snapshot().at("/blocks/0/blocks/0/title").asText())
                .isEqualTo("Current title");
        assertThat(unchanged.snapshot().at("/blocks/0/blocks/0/dataSource").isMissingNode()).isTrue();
        assertThat(changeItemCount(opened.changeSetPid())).isEqualTo(1);
        assertThat(proposalService.get(opened.sessionPid(), proposal.proposalPid()).status())
                .isEqualTo("PROPOSED");
    }

    @Test
    void unknownAndStructuralTargetsAreRejectedBeforeProposalPersistence() {
        PageSchema page = insertPage();
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        CreateAiPatchProposalRequest unknown = new CreateAiPatchProposalRequest(
                opened.revision(),
                List.of(new AiPatchProposalItemRequest(
                        "table-1",
                        "/props/notDeclared",
                        PatchOperation.REPLACE,
                        objectMapper.getNodeFactory().textNode("unsafe"),
                        tableManifest())));
        CreateAiPatchProposalRequest structural = new CreateAiPatchProposalRequest(
                opened.revision(),
                List.of(new AiPatchProposalItemRequest(
                        "table-1",
                        "/props/density",
                        PatchOperation.MOVE,
                        null,
                        tableManifest())));

        assertThatThrownBy(() -> proposalService.create(opened.sessionPid(), unknown))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.policy.capability_unknown");
        assertThatThrownBy(() -> proposalService.create(opened.sessionPid(), structural))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.ai-proposal.property-patch-required");
        assertThat(workspaceService.get(opened.sessionPid()).revision()).isEqualTo(1);
        assertThat(changeItemCount(opened.changeSetPid())).isZero();
        assertThat(proposalCount(opened.sessionPid(), "PROPOSED")).isZero();
    }

    private CreateAiPatchProposalRequest proposalRequest(SessionView opened) {
        return new CreateAiPatchProposalRequest(
                opened.revision(),
                List.of(
                        new AiPatchProposalItemRequest(
                                "table-1",
                                "/props/density",
                                PatchOperation.REPLACE,
                                objectMapper.getNodeFactory().textNode("compact"),
                                tableManifest()),
                        new AiPatchProposalItemRequest(
                                "table-1",
                                "/dataSource",
                                PatchOperation.ADD,
                                objectMapper.valueToTree(java.util.Map.of("model", "payments")),
                                tableManifest())));
    }

    private PageSchema insertPage() {
        ensureModel("test_model");
        ensureModel("payments");
        String pid = UniqueIdGenerator.generate();
        PageSchema page = new PageSchema();
        page.setPid(pid);
        page.setTenantId(testTenant.getId());
        page.setEnvId(com.auraboot.framework.application.tenant.MetaContext
                .getCurrentEnvironmentId());
        page.setPageKey("ai_authoring_" + pid.toLowerCase());
        page.setModelCode("test_model");
        page.setName("AI Authoring " + pid);
        page.setKind("list");
        page.setSchemaVersion(4);
        page.setProfile("admin");
        page.setTitle("{\"en-US\":\"Orders\"}");
        page.setLayout("{\"type\":\"stack\"}");
        page.setBlocks("[{\"id\":\"table-1\",\"blockType\":\"table\","
                + "\"props\":{\"density\":\"normal\"}}]");
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

    private String tableManifest() {
        return capabilityRegistry.find("table").orElseThrow().checksum();
    }

    private int proposalCount(String sessionPid, String status) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_ai_patch_proposal
                WHERE tenant_id = ? AND env_id = ? AND source_session_pid = ? AND status = ?
                """, Integer.class,
                testTenant.getId(),
                com.auraboot.framework.application.tenant.MetaContext.getCurrentEnvironmentId(),
                sessionPid,
                status);
        return count == null ? 0 : count;
    }

    private int changeItemCount(String changeSetPid) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_change_item item
                JOIN ab_authoring_change_set change_set ON change_set.id = item.change_set_id
                WHERE change_set.pid = ?
                """, Integer.class, changeSetPid);
        return count == null ? 0 : count;
    }

    private int auditCount(String changeSetPid, String eventType) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_audit_event
                WHERE change_set_pid = ? AND event_type = ?
                """, Integer.class, changeSetPid, eventType);
        return count == null ? 0 : count;
    }

    private String auditMetadata(String changeSetPid, String eventType) {
        return jdbcTemplate.queryForObject("""
                SELECT metadata::text FROM ab_authoring_audit_event
                WHERE change_set_pid = ? AND event_type = ?
                ORDER BY id DESC LIMIT 1
                """, String.class, changeSetPid, eventType);
    }
}
