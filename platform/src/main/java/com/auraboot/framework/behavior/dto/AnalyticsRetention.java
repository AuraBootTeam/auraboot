package com.auraboot.framework.behavior.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AnalyticsRetention(String definitionVersion, String unit, String cohortRule,
        String timezone, Instant from, Instant to, Instant dataCutoff, String completeness, List<Point> records) {
    public record Point(String cohortDay, int dayOffset, long cohortSize, Long retained,
                        BigDecimal retentionRate, String status) {}
}
