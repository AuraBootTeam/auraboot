package com.auraboot.framework.decision;

import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.service.DecisionVersionService;
import com.auraboot.framework.decision.service.DrtDefinitionService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7 governance — the optional 4-eyes approval gate over the real stack: VALIDATED →
 * submitForApproval → PENDING_APPROVAL → approve → PUBLISHED (approval_by recorded), plus the reject
 * path, plus proof the default VALIDATED → publish path is unchanged.
 */
class DecisionApprovalIntegrationTest extends BaseIntegrationTest {

    @Autowired private DrtDefinitionService definitionService;
    @Autowired private DecisionVersionService versionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode ast() throws Exception {
        return mapper.readTree("""
            { "type": "compare",
              "left": { "type": "path", "scope": "record", "path": "data.amount", "dataType": "decimal" },
              "operator": "GT", "right": { "type": "literal", "value": 10000, "dataType": "decimal" } }""");
    }

    private DrtVersionDTO validatedDraft(String code) throws Exception {
        DrtDefinitionCreateRequest def = new DrtDefinitionCreateRequest();
        def.setDecisionCode(code);
        def.setDecisionName("Approval IT " + code);
        def.setScopeType("AUTOMATION");
        def.setOwnerModule("decision");
        definitionService.create(def);

        DrtVersionCreateRequest ver = new DrtVersionCreateRequest();
        ver.setKind("SIMPLE_CONDITION");
        ver.setRuntimeAdapter("AST_EVALUATOR");
        ver.setContentJson(ast());
        DrtVersionDTO draft = versionService.createDraft(code, ver);
        versionService.validate(draft.getPid());
        return draft;
    }

    @Test
    void approvalPath_submitApprovePublishes_andRecordsApprover() throws Exception {
        DrtVersionDTO draft = validatedDraft("appr_ok_" + System.nanoTime());

        DrtVersionDTO submitted = versionService.submitForApproval(draft.getPid());
        assertThat(submitted.getStatus()).isEqualTo("PENDING_APPROVAL");

        DrtVersionDTO approved = versionService.approve(draft.getPid(), "looks good");
        assertThat(approved.getStatus()).isEqualTo("PUBLISHED");

        // approver + note recorded on the real row
        var row = jdbcTemplate.queryForMap(
                "select status, approval_by, approval_note, published_by from ab_drt_version where pid=?", draft.getPid());
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(row.get("approval_by")).isNotNull();
        assertThat(row.get("approval_note")).isEqualTo("looks good");
        assertThat(row.get("published_by")).isNotNull();
    }

    @Test
    void rejectPath_submitReject_movesToRejected() throws Exception {
        DrtVersionDTO draft = validatedDraft("appr_rej_" + System.nanoTime());
        versionService.submitForApproval(draft.getPid());
        DrtVersionDTO rejected = versionService.reject(draft.getPid(), "missing edge case");
        assertThat(rejected.getStatus()).isEqualTo("REJECTED");
        assertThat(jdbcTemplate.queryForObject(
                "select approval_note from ab_drt_version where pid=?", String.class, draft.getPid()))
                .isEqualTo("missing edge case");
    }

    @Test
    void cannotApproveAValidatedVersionThatWasNotSubmitted() throws Exception {
        DrtVersionDTO draft = validatedDraft("appr_guard_" + System.nanoTime());
        assertThatThrownBy(() -> versionService.approve(draft.getPid(), "x"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void defaultPublishPath_unchanged() throws Exception {
        DrtVersionDTO draft = validatedDraft("appr_default_" + System.nanoTime());
        DrtVersionDTO published = versionService.publish(draft.getPid());
        assertThat(published.getStatus()).isEqualTo("PUBLISHED");
    }
}
