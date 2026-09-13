package com.auraboot.framework.behavior.dto;

import lombok.Data;

@Data
public class AnalyticsExecutionCounts {
    private long started;
    private long succeeded;
    private long failed;
    private long cancelled;
    private long unresolved;
    private long excludedSandbox;
    private long unknownPrincipal;
}
