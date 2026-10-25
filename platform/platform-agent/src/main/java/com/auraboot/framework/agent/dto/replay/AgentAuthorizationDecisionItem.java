package com.auraboot.framework.agent.dto.replay;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AgentAuthorizationDecisionItem {

    private String pid;
    private String runId;
    private String decisionKind;
    private String toolRef;
    private String skillCode;
    private String blastRadius;
    private String requestedEffects;
    private String grantedEffects;
    private String rejectedEffects;
    private boolean requireApproval;
    private String approvalId;
    private String policyId;
    private Integer policyVersion;
    private String decisionReason;
    private Instant decisionAt;
}
