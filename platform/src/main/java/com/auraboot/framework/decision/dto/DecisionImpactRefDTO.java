package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.Map;

/**
 * One incoming consumer or outgoing dependency edge in the decision impact graph.
 */
@Data
public class DecisionImpactRefDTO {

    private String sourceType;
    private String sourceCode;
    private String sourceName;
    private String sourceVersion;
    private String sourcePid;
    private String targetType;
    private String targetCode;
    private String targetPath;
    private String binding;
    private Map<String, Object> metadata;
}
