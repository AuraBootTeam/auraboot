package com.auraboot.framework.behavior.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record AnalyticsExecution(String definitionVersion, String countingUnit, String cohortRule,
                                 Instant from, Instant to, Instant dataCutoff, String timezone,
                                 AnalyticsExecutionCounts counts, BigDecimal successRate,
                                 BigDecimal completedSuccessRate, String sampleStatus,
                                 String observationStatus) {}
