package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * One post-publish replay or revalidation step for an affected rule consumer.
 */
@Data
@Builder
public class ModelPublishReplayStepDTO {

    private String consumerType;

    private String consumerLabel;

    private String sourceCode;

    private String sourceName;

    private String sourceVersion;

    private String sourcePid;

    private String fieldRef;

    private String targetPath;

    private String binding;

    private String recommendedAction;

    private Boolean required;

    private Map<String, Object> metadata;
}
