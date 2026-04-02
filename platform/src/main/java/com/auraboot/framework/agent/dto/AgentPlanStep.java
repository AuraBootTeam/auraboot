package com.auraboot.framework.agent.dto;

import lombok.Data;
import java.util.Map;

@Data
public class AgentPlanStep {

    public enum StepStatus {
        PENDING, RUNNING, COMPLETED, FAILED, SKIPPED, AWAITING_APPROVAL
    }

    private int stepIndex;
    private String description;
    private String toolCode;
    private Map<String, Object> toolInput;
    private StepStatus status;
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

    public boolean isTerminal() {
        return status == StepStatus.COMPLETED || status == StepStatus.FAILED || status == StepStatus.SKIPPED;
    }
}
