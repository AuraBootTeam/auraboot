package com.auraboot.framework.agent.dto;

import lombok.Data;
import java.util.List;

@Data
public class PlatformAiScoreRequest {

    /** DSL model code, e.g. "crm_lead" */
    private String modelCode;

    /** DSL field code to write the score back to (0-100); must already exist on the model */
    private String scoreField;

    /**
     * Context fields (DB column names, e.g. "crm_lead_status", "crm_lead_company")
     * whose values will be serialized and sent to the LLM.
     */
    private List<String> contextFields;

    /**
     * Scoring dimension descriptors passed to the LLM system prompt.
     * No mathematical weighting is enforced — purely prompt-level guidance.
     */
    private List<ScoringDimension> scoringDimensions;

    /** Optional: score only these specific record pids. Empty means score all (up to limit). */
    private List<String> recordPids;

    /** Max records to fetch when scoring all (default 200). */
    private int limit = 200;

    /** Records per LLM batch (default 10, max 20). */
    private int batchSize = 10;

    @Data
    public static class ScoringDimension {
        /** The contextField column this dimension corresponds to. */
        private String fieldCode;
        /** Scoring description for the LLM system prompt (English). */
        private String description;
        /** Weight in points (0-100 total; informational only for the LLM). */
        private int weight;
    }
}
