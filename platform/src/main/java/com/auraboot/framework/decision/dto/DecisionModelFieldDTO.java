package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.List;

/**
 * Read-model row for fields referenced by Decision Runtime versions.
 */
@Data
public class DecisionModelFieldDTO {
    private String entityCode;
    private String path;
    private String label;
    private String dataType;
    private Long refs;
    private Boolean masked;
    private String permission;
    private List<String> decisionCodes;
}
