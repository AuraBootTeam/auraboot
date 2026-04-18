package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.agent.dto.ResultContract;
import com.auraboot.framework.agent.dto.SkillResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ResultContractMapper — the SkillResult → ResultContract
 * conversion that hides engine internals from external consumers.
 */
@DisplayName("ResultContractMapper — SkillResult → ResultContract")
class ResultContractMapperTest {

    private final ResultContractMapper mapper = new ResultContractMapper();

    @AfterEach
    void clearBif() {
        BifContext.clear();
    }

    private SkillResult baseResult() {
        return SkillResult.builder()
                .skillCode("dsl.query")
                .outputType("structured_result")
                .renderHint("table")
                .data(Map.of("totalRows", 5))
                .textSummary("Found 5 leads")
                .toolCallCount(1)
                .actionCount(1)
                .durationMs(142)
                .cost(0.003)
                .status(SkillResult.Status.SUCCESS)
                .canContinueFrom(false)
                .build();
    }

    @Test
    @DisplayName("toContract copies external fields (skillCode, outputType, renderHint, data, status)")
    void copies_external_fields() {
        ResultContract c = mapper.toContract(baseResult(), "read_only");
        assertThat(c.getSkillCode()).isEqualTo("dsl.query");
        assertThat(c.getOutputType()).isEqualTo("structured_result");
        assertThat(c.getRenderHint()).isEqualTo("table");
        assertThat(c.getData()).containsEntry("totalRows", 5);
        assertThat(c.getTextSummary()).isEqualTo("Found 5 leads");
        assertThat(c.getStatus()).isEqualTo("success");
        assertThat(c.getActionability()).isEqualTo("read_only");
        assertThat(c.getDurationMs()).isEqualTo(142);
    }

    @Test
    @DisplayName("status enum lowercased (spec external contract)")
    void status_lowercased() {
        SkillResult r = baseResult();
        r.setStatus(SkillResult.Status.PARTIAL_SUCCESS);
        assertThat(mapper.toContract(r, "read_only").getStatus()).isEqualTo("partial_success");

        r.setStatus(SkillResult.Status.FAILED);
        assertThat(mapper.toContract(r, "read_only").getStatus()).isEqualTo("failed");
    }

    @Test
    @DisplayName("actionability derived from BifContext when not passed explicitly")
    void actionability_from_bif_context() {
        BusinessIntentFrame bif = BusinessIntentFrame.builder()
                .intent("delete").object("crm_lead").riskLevel("L3")
                .actionability("execute")
                .confidence(ConfidenceScore.of(0.9, 0.9))
                .build();
        BifContext.setCurrentBif(bif);

        ResultContract c = mapper.toContract(baseResult());
        assertThat(c.getActionability()).isEqualTo("execute");
    }

    @Test
    @DisplayName("actionability falls back to read_only when no BIF in context")
    void actionability_defaults_when_no_bif() {
        BifContext.clear();
        ResultContract c = mapper.toContract(baseResult());
        assertThat(c.getActionability()).isEqualTo("read_only");
    }

    @Test
    @DisplayName("engine internals (actionPids, cost, toolCallCount) are NOT exposed")
    void engine_internals_hidden() {
        ResultContract c = mapper.toContract(baseResult(), "read_only");
        // ResultContract has no cost / toolCallCount / actionPids fields at all —
        // this test pins the external surface. If someone adds them, fix the
        // test AND justify why engine internals became external.
        java.util.Set<String> externalFields = new java.util.HashSet<>();
        for (java.lang.reflect.Field f : ResultContract.class.getDeclaredFields()) {
            externalFields.add(f.getName());
        }
        assertThat(externalFields).doesNotContain("cost", "toolCallCount", "actionPids", "actionCount");
    }

    @Test
    @DisplayName("null SkillResult fields map to null contract fields (not exception)")
    void null_fields_graceful() {
        SkillResult r = SkillResult.builder()
                .skillCode("dsl.query")
                .status(SkillResult.Status.SUCCESS)
                .build();
        ResultContract c = mapper.toContract(r, "read_only");
        assertThat(c.getSkillCode()).isEqualTo("dsl.query");
        assertThat(c.getOutputType()).isNull();
        assertThat(c.getRenderHint()).isNull();
        assertThat(c.getData()).isNull();
    }
}
