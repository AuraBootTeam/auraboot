package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * Request DTO for submitting evidence.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
public class EvidenceSubmitRequest {

    @NotBlank
    private String subjectType;

    @NotBlank
    private String subjectId;

    @NotBlank
    private String stage;

    @NotBlank
    private String evidenceCode;

    private Map<String, Object> evidenceData;

    private String source;
}
