package com.auraboot.framework.decision.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Stateless decision-table analysis request used by the DMN V2 workbench.
 */
@Data
public class DecisionTableAnalyzeRequest {

    private String decisionCode;

    private String versionPid;

    @NotNull
    private JsonNode model;
}
