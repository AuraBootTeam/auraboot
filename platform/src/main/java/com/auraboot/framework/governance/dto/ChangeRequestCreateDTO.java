package com.auraboot.framework.governance.dto;

import lombok.Data;

import java.util.Map;

/**
 * DTO for submitting a new change request.
 */
@Data
public class ChangeRequestCreateDTO {

    /** The entity type (model code) */
    private String entityType;

    /** The row PID of the record (null for CREATE) */
    private String entityPid;

    /** Type of change: CREATE, UPDATE, DELETE */
    private String changeType;

    /** The proposed data payload */
    private Map<String, Object> proposedData;

    /** Optional comment from the submitter */
    private String comment;
}
