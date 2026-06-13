package com.auraboot.framework.decision.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * Stateless DMN XML import/export request for the visual decision-table editor.
 */
@Data
public class DecisionTableDmnXmlRequest {

    private String decisionId;

    private String decisionName;

    private String namespace;

    private JsonNode model;

    private String dmnXml;
}
