package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for SkillEngine.resolveRefPath — the 4 $ref namespaces
 * defined by spec §4.2 step_input_mappings:
 *   $ref:input.X     → SkillInput.parameters
 *   $ref:steps[N].X  → Nth previous output
 *   $ref:prev.X      → shorthand for last previous output
 *   $ref:bif.X       → current-turn BusinessIntentFrame (via BifContext)
 */
@DisplayName("SkillEngine — $ref path resolver")
class SkillEngineRefResolverIntegrationTest extends BaseIntegrationTest {

    @Autowired private SkillEngine skillEngine;

    @AfterEach
    void clearBif() {
        BifContext.clear();
    }

    @Test
    @DisplayName("$ref:input.X resolves from original input map")
    void ref_input() {
        Map<String, Object> input = Map.of("recordPid", "01ABC", "count", 5);
        Object v = skillEngine.resolveRefPath("input.recordPid", input, List.of());
        assertThat(v).isEqualTo("01ABC");

        Object n = skillEngine.resolveRefPath("input.count", input, List.of());
        assertThat(n).isEqualTo(5);
    }

    @Test
    @DisplayName("$ref:steps[N].X resolves from previousOutputs by index")
    void ref_steps_by_index() {
        List<Map<String, Object>> outputs = List.of(
                Map.of("data", Map.of("pid", "01REC0")),
                Map.of("data", Map.of("pid", "01REC1"), "total", 7));
        assertThat(skillEngine.resolveRefPath("steps[0].data.pid", Map.of(), outputs)).isEqualTo("01REC0");
        assertThat(skillEngine.resolveRefPath("steps[1].data.pid", Map.of(), outputs)).isEqualTo("01REC1");
        assertThat(skillEngine.resolveRefPath("steps[1].total", Map.of(), outputs)).isEqualTo(7);
    }

    @Test
    @DisplayName("$ref:steps[N].X returns null when N is out of range")
    void ref_steps_out_of_range() {
        List<Map<String, Object>> outputs = List.of(Map.of("x", 1));
        assertThat(skillEngine.resolveRefPath("steps[5].x", Map.of(), outputs)).isNull();
    }

    @Test
    @DisplayName("$ref:prev.X resolves from last previousOutputs entry")
    void ref_prev() {
        List<Map<String, Object>> outputs = List.of(
                Map.of("data", Map.of("pid", "OLD")),
                Map.of("data", Map.of("pid", "LATEST"), "n", 42));
        assertThat(skillEngine.resolveRefPath("prev.data.pid", Map.of(), outputs)).isEqualTo("LATEST");
        assertThat(skillEngine.resolveRefPath("prev.n", Map.of(), outputs)).isEqualTo(42);
    }

    @Test
    @DisplayName("$ref:prev without field returns the whole last output map")
    void ref_prev_whole() {
        List<Map<String, Object>> outputs = List.of(Map.of("a", 1), Map.of("b", 2));
        Object v = skillEngine.resolveRefPath("prev", Map.of(), outputs);
        assertThat(v).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) v).get("b")).isEqualTo(2);
    }

    @Test
    @DisplayName("$ref:prev returns null when previousOutputs is empty")
    void ref_prev_empty() {
        assertThat(skillEngine.resolveRefPath("prev.x", Map.of(), List.of())).isNull();
    }

    @Test
    @DisplayName("$ref:bif.X resolves from current BifContext")
    void ref_bif() {
        BusinessIntentFrame bif = BusinessIntentFrame.builder()
                .intent("query")
                .object("crm_account")
                .riskLevel("L1")
                .actionability("read_only")
                .candidateSkillsMode("hint")
                .confidence(ConfidenceScore.of(0.9, 0.8))
                .build();
        BifContext.setCurrentBif(bif);

        assertThat(skillEngine.resolveRefPath("bif.intent", Map.of(), List.of())).isEqualTo("query");
        assertThat(skillEngine.resolveRefPath("bif.object", Map.of(), List.of())).isEqualTo("crm_account");
        assertThat(skillEngine.resolveRefPath("bif.riskLevel", Map.of(), List.of())).isEqualTo("L1");
        assertThat(skillEngine.resolveRefPath("bif.candidateSkillsMode", Map.of(), List.of())).isEqualTo("hint");
    }

    @Test
    @DisplayName("$ref:bif returns null when no BIF is in context")
    void ref_bif_no_context() {
        BifContext.clear();
        assertThat(skillEngine.resolveRefPath("bif.intent", Map.of(), List.of())).isNull();
    }

    @Test
    @DisplayName("unknown namespace returns null, not an exception")
    void ref_unknown_namespace() {
        assertThat(skillEngine.resolveRefPath("garbage.xyz", Map.of("a", 1), List.of())).isNull();
    }
}
