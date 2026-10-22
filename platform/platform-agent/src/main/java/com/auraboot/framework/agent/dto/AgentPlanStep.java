package com.auraboot.framework.agent.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Step Contract as persisted in ab_agent_run.execution_plan JSONB.
 *
 * Minimum field set per specs/02-SkillRuntimeSpec §5.5.1 / ACP-Ideal §5.5:
 * step_index, skill_code, status, input, output, started_at, finished_at.
 *
 * Additional fields (description, toolCode, toolInput, result, error,
 * durationMs, requiresApproval) extend the contract for concrete planner
 * output and HITL UI; all are optional per-spec.
 */
@Data
public class AgentPlanStep {

    public enum StepStatus {
        PENDING, RUNNING, COMPLETED, FAILED, SKIPPED, AWAITING_APPROVAL
    }

    // --- Spec §5.5.1 minimum field set ---
    private int stepIndex;
    /** Skill dispatched for this step (spec: skill_code). Populated when StepLoopService
     *  routes to a real Skill; null for free-form LLM steps without an explicit Skill. */
    private String skillCode;
    private StepStatus status;
    /** SkillInput snapshot — the parameter map passed into the Skill / tool. */
    private Map<String, Object> input;
    /** SkillResult summary — truncated data + status for HITL display. */
    private Map<String, Object> output;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime startedAt;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime finishedAt;

    // --- Planner/HITL extensions (non-minimum) ---
    private String description;
    private String toolCode;
    private Map<String, Object> toolInput;
    private String result;
    private String error;
    private long durationMs;
    private boolean requiresApproval;

    public AgentPlanStep() {
        this.status = StepStatus.PENDING;
    }

    public AgentPlanStep(int stepIndex, String description) {
        this.stepIndex = stepIndex;
        this.description = description;
        this.status = StepStatus.PENDING;
    }

    @JsonIgnore
    public boolean isTerminal() {
        return status == StepStatus.COMPLETED || status == StepStatus.FAILED || status == StepStatus.SKIPPED;
    }
}
