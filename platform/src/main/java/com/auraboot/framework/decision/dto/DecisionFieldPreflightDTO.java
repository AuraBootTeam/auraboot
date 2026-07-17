package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.List;

/**
 * Field/schema change guard result backed by the DecisionOps usage index.
 */
@Data
public class DecisionFieldPreflightDTO {

    private String fieldRef;

    private String action;

    private String currentDataType;

    private String nextDataType;

    private String dictCode;

    private String dictValue;

    private String nextPermission;

    private String nextSourceRef;

    private Boolean allowed;

    private Boolean blocked;

    private Boolean requiresAcknowledgement;

    private DecisionImpactRiskDTO risk;

    private List<DecisionImpactRefDTO> references = List.of();

    private String message;
}
