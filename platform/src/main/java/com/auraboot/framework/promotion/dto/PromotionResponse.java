package com.auraboot.framework.promotion.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class PromotionResponse {

    private String pid;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceEnvId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long targetEnvId;
    private String status;

    private List<PromotionUnitView> units;

    /** Last DryRunResult JSON, parsed for convenience; null if never validated. */
    private DryRunResult dryRunResult;
    private Date dryRunAt;

    private Date createdAt;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;
    private Date updatedAt;

    // Terminal-state audit
    private Date appliedAt;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long appliedBy;
    private String appliedReason;
    private Date rejectedAt;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long rejectedBy;
    private String rejectedReason;
    private String failureReason;
    private String parentPromotionPid;
    private String originDriftDecisionPid;

    @Data
    public static class PromotionUnitView {
        private String pid;
        private String resourceType;
        private String resourcePid;
        private Integer sourceVersion;
        private Integer targetVersion;
        private String targetResourcePid;
        private String driftStatus;
        private String driftFingerprint;
        private String driftDecision;
        private String driftExecutionStatus;
        private String driftExecutionPid;
        private Integer sortOrder;
    }
}
