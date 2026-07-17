package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Post-publish replay report generated from model publish governance.
 */
@Data
@Builder
public class ModelPublishReplayReportDTO {

    private String modelCode;

    private Integer draftVersion;

    private Integer latestPublishedVersion;

    private Instant generatedAt;

    private ModelPublishGovernanceDTO governance;

    private Integer totalCount;

    private Integer automatedCount;

    private Integer executedCount;

    private Integer manualCount;

    private Integer failedCount;

    private Integer needsInputCount;

    private List<ModelPublishReplayResultDTO> results;
}
