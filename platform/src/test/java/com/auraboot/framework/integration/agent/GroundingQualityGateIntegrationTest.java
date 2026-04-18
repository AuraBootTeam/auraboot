package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.agent.service.GroundingService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §5.1 quality gate: pins the 4 degradation conditions
 * (low overall confidence, no object resolved, empty candidate skills,
 * intent/object confidence divergence > 0.5).
 */
@DisplayName("GroundingService — quality gate (spec §5.1)")
class GroundingQualityGateIntegrationTest extends BaseIntegrationTest {

    @Autowired private GroundingService groundingService;

    private BusinessIntentFrame baselineOk() {
        return BusinessIntentFrame.builder()
                .intent("query")
                .object("crm_account")
                .riskLevel("L0")
                .actionability("read_only")
                .confidence(ConfidenceScore.of(0.85, 0.85))
                .candidateSkills(List.of("dsl.query"))
                .candidateSkillsMode("hint")
                .build();
    }

    @Test
    @DisplayName("healthy BIF passes the gate → returns null")
    void healthy_passes() {
        assertThat(groundingService.checkQualityGate(baselineOk())).isNull();
    }

    @Test
    @DisplayName("overall confidence < 0.50 → 'low_confidence'")
    void low_confidence_detected() {
        BusinessIntentFrame bif = baselineOk();
        bif.setConfidence(ConfidenceScore.of(0.30, 0.30));
        assertThat(groundingService.checkQualityGate(bif)).startsWith("low_confidence");
    }

    @Test
    @DisplayName("no object resolved → 'no_object_resolved'")
    void missing_object_detected() {
        BusinessIntentFrame bif = baselineOk();
        bif.setObject(null);
        assertThat(groundingService.checkQualityGate(bif)).isEqualTo("no_object_resolved");
    }

    @Test
    @DisplayName("empty candidate skills → 'no_candidate_skills'")
    void empty_candidates_detected() {
        BusinessIntentFrame bif = baselineOk();
        bif.setCandidateSkills(List.of());
        assertThat(groundingService.checkQualityGate(bif)).isEqualTo("no_candidate_skills");
    }

    @Test
    @DisplayName("|confidence.intent - confidence.object| > 0.5 → 'confidence_divergence'")
    void confidence_divergence_detected() {
        BusinessIntentFrame bif = baselineOk();
        // intent high, object very low — grounder is conflicted
        bif.setConfidence(ConfidenceScore.of(0.95, 0.20));
        assertThat(groundingService.checkQualityGate(bif)).isEqualTo("confidence_divergence");
    }
}
