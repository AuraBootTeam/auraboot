package com.auraboot.framework.promotion.dto;

import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class PromotionResponse {

    private String pid;
    private Long sourceEnvId;
    private Long targetEnvId;
    private String status;

    private List<PromotionUnitView> units;

    /** Last DryRunResult JSON, parsed for convenience; null if never validated. */
    private DryRunResult dryRunResult;
    private Date dryRunAt;

    private Date createdAt;
    private Long createdBy;
    private Date updatedAt;

    // Terminal-state audit
    private Date appliedAt;
    private Long appliedBy;
    private String appliedReason;
    private Date rejectedAt;
    private Long rejectedBy;
    private String rejectedReason;
    private String failureReason;

    @Data
    public static class PromotionUnitView {
        private String pid;
        private String resourceType;
        private String resourcePid;
        private Integer sourceVersion;
        private Integer targetVersion;
        private Integer sortOrder;
    }
}
