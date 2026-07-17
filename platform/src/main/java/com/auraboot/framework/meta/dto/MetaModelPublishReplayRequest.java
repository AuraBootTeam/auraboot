package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.Map;

/**
 * Request body for running post-publish replay checks from a model governance plan.
 */
@Data
public class MetaModelPublishReplayRequest {

    private Boolean executeAutomated;

    private String correlationId;

    private Map<String, Map<String, Object>> sampleContext;
}
