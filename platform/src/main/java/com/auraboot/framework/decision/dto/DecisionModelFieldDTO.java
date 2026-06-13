package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.List;

/**
 * DecisionOps data-model field catalogue row, derived from validated decision field references.
 */
@Data
public class DecisionModelFieldDTO {
    private String entityCode;
    private String path;
    private String label;
    private String dataType;
    private int refs;
    private Boolean masked;
    private String permission;
    private List<String> decisionCodes;
}
