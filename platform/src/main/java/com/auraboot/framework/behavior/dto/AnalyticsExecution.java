package com.auraboot.framework.behavior.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record AnalyticsExecution(String definitionVersion, String countingUnit, String cohortRule,
                                 Instant from, Instant to, Instant dataCutoff, String timezone,
                                 AnalyticsExecutionCounts counts, BigDecimal successRate,
                                 BigDecimal completedSuccessRate, String sampleStatus,
                                 String observationStatus) {
    /** Flat projection for the shared DSL table renderer; counts retain their API grouping. */
    @com.fasterxml.jackson.annotation.JsonProperty("records")
    public java.util.List<Summary> records() {
        return java.util.List.of(new Summary(counts.getStarted(), counts.getSucceeded(), counts.getFailed(),
                counts.getCancelled(), counts.getUnresolved(), counts.getExcludedSandbox(),
                counts.getUnknownPrincipal(), successRate, completedSuccessRate));
    }

    public record Summary(long started, long succeeded, long failed, long cancelled, long unresolved,
                          long excludedSandbox, long unknownPrincipal, BigDecimal successRate,
                          BigDecimal completedSuccessRate) {}
}
