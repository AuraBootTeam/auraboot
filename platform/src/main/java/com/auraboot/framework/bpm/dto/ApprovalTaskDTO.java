package com.auraboot.framework.bpm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response DTO for approval task listing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalTaskDTO {

    private String pid;
    private String taskTitle;
    private String taskDescription;
    private String priority;
    private String status;
    private String assigneeStrategy;
    private List<Long> assigneeUserIds;
    private Long actualApproverId;
    private String processKey;
    private String businessKey;
    private String chainExecutionId;
    private String formRef;
    private String approvalComment;
    private String signature;
    private List<Map<String, Object>> attachments;
    private String approverName;
    private Instant deadlineAt;
    private Instant completedAt;
    private Instant createdAt;

    // Extended fields for detail view
    private Map<String, Object> formSnapshot;
    private Map<String, Object> approvalData;
}
