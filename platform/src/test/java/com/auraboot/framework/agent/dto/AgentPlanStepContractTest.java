package com.auraboot.framework.agent.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Step Contract minimum field set (spec §5.5.1) and verifies the DTO
 * round-trips cleanly through the Jackson mapper used for execution_plan JSONB.
 */
@DisplayName("AgentPlanStep — Step Contract (spec §5.5.1)")
class AgentPlanStepContractTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("all 7 minimum fields are present on the DTO")
    void minimum_fields_present() throws Exception {
        AgentPlanStep step = new AgentPlanStep(2, "list leads");
        step.setSkillCode("dsl.query");
        step.setStatus(AgentPlanStep.StepStatus.COMPLETED);
        step.setInput(Map.of("keyword", "acme"));
        step.setOutput(Map.of("status", "success", "total", 5));
        step.setStartedAt(LocalDateTime.of(2026, 4, 18, 12, 0, 0));
        step.setFinishedAt(LocalDateTime.of(2026, 4, 18, 12, 0, 1));

        String json = mapper.writeValueAsString(step);

        assertThat(json)
                .contains("\"stepIndex\":2")
                .contains("\"skillCode\":\"dsl.query\"")
                .contains("\"status\":\"COMPLETED\"")
                .contains("\"input\"")
                .contains("\"output\"")
                .contains("\"startedAt\":\"2026-04-18T12:00:00\"")
                .contains("\"finishedAt\":\"2026-04-18T12:00:01\"");
    }

    @Test
    @DisplayName("round-trip through Jackson preserves all contract fields")
    void round_trip_preserves_fields() throws Exception {
        AgentPlanStep original = new AgentPlanStep(0, "describe");
        original.setSkillCode("dsl.command");
        original.setStatus(AgentPlanStep.StepStatus.FAILED);
        original.setInput(Map.of("modelCode", "crm_lead"));
        original.setOutput(Map.of("status", "failed", "error", "boom"));
        original.setStartedAt(LocalDateTime.of(2026, 4, 18, 10, 0));
        original.setFinishedAt(LocalDateTime.of(2026, 4, 18, 10, 1));

        String json = mapper.writeValueAsString(original);
        AgentPlanStep parsed = mapper.readValue(json, AgentPlanStep.class);

        assertThat(parsed.getStepIndex()).isEqualTo(0);
        assertThat(parsed.getSkillCode()).isEqualTo("dsl.command");
        assertThat(parsed.getStatus()).isEqualTo(AgentPlanStep.StepStatus.FAILED);
        assertThat(parsed.getInput()).containsEntry("modelCode", "crm_lead");
        assertThat(parsed.getOutput()).containsEntry("error", "boom");
        assertThat(parsed.getStartedAt()).isEqualTo(LocalDateTime.of(2026, 4, 18, 10, 0));
        assertThat(parsed.getFinishedAt()).isEqualTo(LocalDateTime.of(2026, 4, 18, 10, 1));
    }

    @Test
    @DisplayName("default constructor initializes status to PENDING; timestamps stay null")
    void default_ctor_defaults() {
        AgentPlanStep step = new AgentPlanStep();
        assertThat(step.getStatus()).isEqualTo(AgentPlanStep.StepStatus.PENDING);
        assertThat(step.getStartedAt()).isNull();
        assertThat(step.getFinishedAt()).isNull();
        assertThat(step.getSkillCode()).isNull();
        assertThat(step.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("isTerminal covers COMPLETED / FAILED / SKIPPED")
    void terminal_states() {
        AgentPlanStep s = new AgentPlanStep();
        for (AgentPlanStep.StepStatus st : AgentPlanStep.StepStatus.values()) {
            s.setStatus(st);
            boolean expected = st == AgentPlanStep.StepStatus.COMPLETED
                    || st == AgentPlanStep.StepStatus.FAILED
                    || st == AgentPlanStep.StepStatus.SKIPPED;
            assertThat(s.isTerminal()).as("status %s", st).isEqualTo(expected);
        }
    }
}
