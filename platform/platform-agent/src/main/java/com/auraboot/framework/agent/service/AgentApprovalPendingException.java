package com.auraboot.framework.agent.service;

import java.util.Map;

/**
 * Thrown by {@code StepLoopService} when a plan step requires approval and the
 * approval gate has created a pending {@code ab_agent_approval} row. Carries
 * the {@code ab_agent_approval.pid} so the catching layer
 * ({@code AgentRunService.executeTaskSync}) can surface it through
 * {@link RunOutcome.PendingApproval} — the chokepoint then uses it as the
 * resumption token in the {@code confirm_required} SSE event (Phase C.3d,
 * Q-C3.3=α "approval-gate convergence to ACP").
 *
 * <p>The {@code approvalPid} is intentionally exposed as a typed field rather
 * than embedded in the message text so the chokepoint mapping stays robust if
 * the human-readable wording ever changes.
 */
public class AgentApprovalPendingException extends RuntimeException {

    private final String approvalPid;
    private final String toolName;
    private final Map<String, Object> input;

    public AgentApprovalPendingException(String message) {
        this(null, message);
    }

    public AgentApprovalPendingException(String approvalPid, String message) {
        this(approvalPid, message, null, null);
    }

    public AgentApprovalPendingException(String approvalPid, String message,
                                         String toolName, Map<String, Object> input) {
        super(message);
        this.approvalPid = approvalPid;
        this.toolName = toolName;
        this.input = input;
    }

    /** {@code ab_agent_approval.pid} of the pending approval row, or null when
     *  the throw site does not yet know it (legacy callers). */
    public String getApprovalPid() {
        return approvalPid;
    }

    public String getToolName() {
        return toolName;
    }

    public Map<String, Object> getInput() {
        return input;
    }
}
