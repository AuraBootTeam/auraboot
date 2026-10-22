package com.auraboot.framework.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Runtime result of a Skill execution — internal to SkillEngine and StepLoopService.
 * For external consumption (frontend / API / HITL), use ResultContract via ResultContractMapper.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillResult {

    public enum Status { SUCCESS, PARTIAL_SUCCESS, FAILED }

    private String skillCode;
    private String outputType;          // text | structured_result | action_proposal | artifact
    private String renderHint;          // chart_table | table | summary | form
    private Map<String, Object> data;
    private String textSummary;

    // execution stats
    private int toolCallCount;
    private int actionCount;
    private List<String> actionPids;
    private long durationMs;
    private double cost;

    // status
    private Status status;
    private String errorMessage;

    // continuation (P1)
    private boolean canContinueFrom;
    private Map<String, Object> continuationData;
}
