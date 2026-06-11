package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class DecisionRolloutMetricsDTO {
    private String policyPid;
    private int windowHours;
    private int bucketSeconds;
    private int retentionDays;
    private String source;
    private String latencyAggregation;
    private Instant refreshedAt;
    private ArmMetrics baseline = new ArmMetrics();
    private ArmMetrics candidate = new ArmMetrics();
    private List<WindowMetrics> windows = new ArrayList<>();

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

    @Data
    public static class WindowMetrics {
        private Instant windowStart;
        private ArmMetrics baseline = new ArmMetrics();
        private ArmMetrics candidate = new ArmMetrics();
    }
}
