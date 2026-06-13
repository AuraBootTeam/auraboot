package com.auraboot.framework.decision.service;

import com.auraboot.framework.decision.dto.DecisionTableAnalysisDTO;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Static analysis for platform decision tables.
 */
public interface DecisionTableAnalysisService {

    DecisionTableAnalysisDTO analyze(JsonNode model);
}
