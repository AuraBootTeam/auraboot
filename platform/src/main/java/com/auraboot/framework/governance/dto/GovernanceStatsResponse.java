package com.auraboot.framework.governance.dto;

import lombok.Data;

/**
 * Response DTO for governance dashboard statistics.
 */
@Data
public class GovernanceStatsResponse {

    private long totalChangeRequests;
    private long pendingRequests;
    private long approvedRequests;
    private long rejectedRequests;
    private long totalVersionedEntities;
    private long totalVersionSnapshots;
}
