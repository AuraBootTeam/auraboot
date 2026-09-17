package com.auraboot.framework.behavior.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Fixed task-cohort funnel with explicit observation boundaries and quality signals. */
public record AnalyticsFunnel(String definitionVersion, String unit, String cohortRule,
        Instant from, Instant to, Instant dataCutoff, String timezone,
        List<Stage> records, Quality quality) {
    public record Stage(String code, long tasks, long firstStageDenominator, long previousStageDenominator,
                        BigDecimal overallRate, BigDecimal previousStageRate, String status) {}
    public record Quality(long missingCorrelationEvents, long withoutWindowEntryTasks,
                          long unmatchedStageEvents, long sampledEvents, String completeness) {}
}
