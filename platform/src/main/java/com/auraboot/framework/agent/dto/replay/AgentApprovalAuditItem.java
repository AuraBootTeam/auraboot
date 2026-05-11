package com.auraboot.framework.agent.dto.replay;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AgentApprovalAuditItem {

    private String pid;
    private String runId;
    private String approvalType;
    private String approvalTitle;
    private String approvalDescription;
    private String requestData;
    private String approvalStatus;
    private String policyId;
    private Long approverId;
    private Instant createdAt;
    private Instant approvedAt;
}
