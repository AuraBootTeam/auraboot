package com.auraboot.framework.bpm.event;

import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * Approval-specific events, extending BpmEvent.
 * Event types: APPROVAL_TASK_CREATED, APPROVAL_COMPLETED, APPROVAL_REJECTED, APPROVAL_TASK_REASSIGNED
 */
@Getter
public class ApprovalEvent extends BpmEvent {

    private final String taskPid;
    private final String chainExecutionPid;
    private final List<Long> assigneeUserIds;
    private final String outcome;
    private final Long approverId;

    private ApprovalEvent(Long tenantId, String eventType, String processKey,
                           String chainExecutionPid, String nodeId, String taskPid,
                           List<Long> assigneeUserIds, String outcome, Long approverId,
                           Map<String, Object> payload) {
        super(tenantId, eventType, "approval", processKey, chainExecutionPid, nodeId, payload);
        this.taskPid = taskPid;
        this.chainExecutionPid = chainExecutionPid;
        this.assigneeUserIds = assigneeUserIds;
        this.outcome = outcome;
        this.approverId = approverId;
    }

    public static ApprovalEvent taskCreated(Long tenantId, String processKey,
                                             String chainExecutionPid, String nodeId,
                                             String taskPid, List<Long> assigneeUserIds,
                                             String taskTitle) {
        return new ApprovalEvent(tenantId, "approval_task_created", processKey,
                chainExecutionPid, nodeId, taskPid, assigneeUserIds, null, null,
                Map.of("taskTitle", taskTitle, "assigneeCount", assigneeUserIds.size()));
    }

    public static ApprovalEvent completed(Long tenantId, String processKey,
                                           String chainExecutionPid, String nodeId,
                                           String taskPid, String outcome, Long approverId,
                                           String comment) {
        return new ApprovalEvent(tenantId, "approval_completed", processKey,
                chainExecutionPid, nodeId, taskPid, List.of(), outcome, approverId,
                Map.of("outcome", outcome, "comment", comment != null ? comment : ""));
    }

    public static ApprovalEvent reassigned(Long tenantId, String processKey,
                                            String chainExecutionPid, String nodeId,
                                            String taskPid, List<Long> oldAssignees,
                                            List<Long> newAssignees) {
        return new ApprovalEvent(tenantId, "approval_task_reassigned", processKey,
                chainExecutionPid, nodeId, taskPid, newAssignees, null, null,
                Map.of("oldAssignees", oldAssignees, "newAssignees", newAssignees));
    }
}
