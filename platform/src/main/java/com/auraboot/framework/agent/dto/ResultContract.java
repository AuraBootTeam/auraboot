package com.auraboot.framework.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * External output protocol — the ONLY format exposed to frontend / API / HITL / AuraBot SSE.
 * Converted from SkillResult via ResultContractMapper. Hides engine internals (actionPids, cost, etc).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResultContract {

    // output type and rendering
    private String outputType;          // text | structured_result | action_proposal | artifact
    private String renderHint;          // chart_table | table | summary | form | card | timeline
    private String actionability;       // read_only | propose | execute

    // structured data
    private Map<String, Object> data;
    private String textSummary;
    private List<Map<String, Object>> table;
    private Map<String, Object> chart;

    // HITL interaction
    private List<SuggestedAction> suggestedActions;
    private boolean canContinueFrom;

    // metadata (no engine internals)
    private String skillCode;
    private long durationMs;
    private String status;              // success | partial_success | failed

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestedAction {
        private String label;
        private String skillCode;
        private Map<String, Object> prefillInput;
    }

    /**
     * Convert SkillResult → ResultContract, hiding engine internals.
     */
    public static ResultContract fromSkillResult(SkillResult result, String actionability) {
        return ResultContract.builder()
                .outputType(result.getOutputType())
                .renderHint(result.getRenderHint())
                .actionability(actionability)
                .data(result.getData())
                .textSummary(result.getTextSummary())
                .canContinueFrom(result.isCanContinueFrom())
                .skillCode(result.getSkillCode())
                .durationMs(result.getDurationMs())
                .status(result.getStatus() != null ? result.getStatus().name().toLowerCase() : "unknown")
                .build();
    }
}
