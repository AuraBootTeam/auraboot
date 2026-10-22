package com.auraboot.framework.agent.dto;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class PlatformAiScoreResult {
    /** Model code that was scored. */
    private String modelCode;
    /** Score field that was written back. */
    private String scoreField;
    /** Number of records successfully scored and written back. */
    private int scoredCount;
    /** Number of records that failed during scoring. */
    private int failedCount;
    /** Per-record score summary: pid → score (0-100). */
    private Map<String, Integer> scores;
    /** Total LLM input tokens consumed. */
    private int totalInputTokens;
    /** Total LLM output tokens consumed. */
    private int totalOutputTokens;
}
