package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Result of one post-publish replay or manual revalidation step.
 */
@Data
@Builder
public class ModelPublishReplayResultDTO {

    private ModelPublishReplayStepDTO step;

    private String status;

    private Boolean automated;

    private Boolean executed;

    private String message;

    private String traceId;

    private Boolean matched;

    private Map<String, Object> outputs;

    private List<String> errors;
}
