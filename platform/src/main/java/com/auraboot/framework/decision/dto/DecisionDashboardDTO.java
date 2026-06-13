package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.List;

/**
 * DecisionOps dashboard read model.
 */
@Data
public class DecisionDashboardDTO {
    private DecisionDashboardSummaryDTO summary;
    private List<DecisionDashboardExceptionDTO> exceptions;
}
