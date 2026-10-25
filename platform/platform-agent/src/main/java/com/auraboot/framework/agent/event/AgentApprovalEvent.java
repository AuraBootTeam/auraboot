package com.auraboot.framework.agent.event;

import com.auraboot.framework.event.AuraEvent;
import lombok.Getter;

import java.util.Map;

/**
 * Domain event published when an agent approval request changes state.
 * Enables the notification system to send alerts to users.
 */
public class AgentApprovalEvent extends AuraEvent {

    @Getter
    private final String approvalPid;
    @Getter
    private final String runPid;
    @Getter
    private final String agentCode;
    @Getter
    private final String approvalStatus; // APPROVED, REJECTED, EXPIRED
    @Getter
    private final Long approvedBy;

    public AgentApprovalEvent(Long tenantId, String approvalPid, String runPid,
                               String agentCode, String approvalStatus, Long approvedBy) {
        super(tenantId, "agent_approval_" + approvalStatus,
              "agent_approval", approvalPid,
              Map.of(
                  "approvalPid", approvalPid,
                  "runPid", runPid != null ? runPid : "",
                  "agentCode", agentCode != null ? agentCode : "",
                  "approvalStatus", approvalStatus,
                  "approvedBy", approvedBy != null ? approvedBy : 0L
              ));
        this.approvalPid = approvalPid;
        this.runPid = runPid;
        this.agentCode = agentCode;
        this.approvalStatus = approvalStatus;
        this.approvedBy = approvedBy;
    }
}
