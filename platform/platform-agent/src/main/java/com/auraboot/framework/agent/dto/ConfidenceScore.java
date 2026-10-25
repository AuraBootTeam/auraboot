package com.auraboot.framework.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Multi-dimensional confidence score from D1 Grounding.
 * HITL can see which dimension is uncertain.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfidenceScore {
    private double intent;      // intent recognition confidence
    private double object;      // object resolution confidence
    @Builder.Default
    private double scope = 1.0; // scope completeness (P0 default 1.0)
    private double overall;     // weighted: intent * 0.6 + object * 0.4

    public static ConfidenceScore of(double intent, double object) {
        return ConfidenceScore.builder()
                .intent(intent)
                .object(object)
                .scope(1.0)
                .overall(intent * 0.6 + object * 0.4)
                .build();
    }
}
