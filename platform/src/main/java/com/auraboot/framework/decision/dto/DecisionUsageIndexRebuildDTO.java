package com.auraboot.framework.decision.dto;

import lombok.Data;

/**
 * Summary returned after rebuilding the Decision Runtime usage index.
 */
@Data
public class DecisionUsageIndexRebuildDTO {

    private Long tenantId;
    private Integer totalRefs;
    private Integer consumerRefs;
    private Integer decisionRefs;
    private Integer fieldRefs;
    private Integer functionRefs;
    private Integer integrationRefs;
}
