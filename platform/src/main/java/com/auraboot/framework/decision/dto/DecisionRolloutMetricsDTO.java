package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class DecisionRolloutMetricsDTO {
    private String policyPid;
    private ArmMetrics baseline = new ArmMetrics();
    private ArmMetrics candidate = new ArmMetrics();

    @Data
    public static class ArmMetrics {
        private Integer version;
        private long evaluations;
        private long matched;
        private long errors;
        private double matchedRate;
        private double errorRate;
        private Long p95LatencyMs;
        private Map<String, Long> resultDistribution = new LinkedHashMap<>();
    }
}
