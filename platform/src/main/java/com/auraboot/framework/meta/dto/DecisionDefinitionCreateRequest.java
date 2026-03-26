package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Request DTO for creating/updating a Decision Definition.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
public class DecisionDefinitionCreateRequest {

    @NotBlank
    private String code;

    private String displayName;

    private String description;

    @NotBlank
    private String subjectType;

    @NotBlank
    private String stage;

    @NotNull
    private List<RequiredEvidenceDTO> requiredEvidence;

    private List<InvariantRuleDTO> invariants;

    @NotNull
    private List<DecisionOutcomeDTO> outcomeOptions;

    private boolean autoAdjudicate;
}
