package com.auraboot.framework.agent.dto.replay;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Read-only runtime evidence bundle for incident triage.
 */
@Data
@Builder
public class AgentRuntimeAuditTrail {

    private String runId;
    private Long conversationId;
    private String toolName;
    private List<AgentActionItem> actions;
    private List<AgentAuthorizationDecisionItem> authorizationDecisions;
    private List<AgentApprovalAuditItem> approvals;
    private List<AgentResultContractItem> resultContracts;
}
