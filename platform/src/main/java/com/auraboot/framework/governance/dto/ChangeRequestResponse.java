package com.auraboot.framework.governance.dto;

import lombok.Data;

import java.util.Date;
import java.util.Map;

/**
 * Response DTO for change request details.
 */
@Data
public class ChangeRequestResponse {

    private String pid;
    private String requestNumber;
    private String entityType;
    private String entityPid;
    private String changeType;
    private Map<String, Object> proposedData;
    private Map<String, Object> originalData;
    private String status;
    private String submittedByPid;
    private String reviewedByPid;
    private String reviewComment;
    private String appliedByPid;
    private Date createdAt;
    private Date updatedAt;
    private Date reviewedAt;
    private Date appliedAt;
}
