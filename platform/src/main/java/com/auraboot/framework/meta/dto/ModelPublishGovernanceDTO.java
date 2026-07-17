package com.auraboot.framework.meta.dto;

import com.auraboot.framework.decision.dto.DecisionFieldImpactDTO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Rule Center governance summary for a model publish preflight.
 */
@Data
@Builder
public class ModelPublishGovernanceDTO {

    private String modelCode;

    private Integer draftVersion;

    private Integer latestPublishedVersion;

    private Boolean allowed;

    private Boolean blocked;

    private Boolean requiresAcknowledgement;

    private Boolean schemaChangeDetected;

    private List<String> schemaChangeKinds;

    private List<DecisionFieldImpactDTO> fieldImpacts;

    private List<ModelPublishReplayStepDTO> replayPlan;

    private String migrationPlan;

    private String historicalVersionPolicy;

    private List<String> warnings;
}
