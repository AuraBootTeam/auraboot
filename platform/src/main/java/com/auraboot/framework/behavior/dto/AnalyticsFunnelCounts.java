package com.auraboot.framework.behavior.dto;

import lombok.Data;

/** Database rollup; counts never infer a successful stage from an unrelated event. */
@Data
public class AnalyticsFunnelCounts {
    private long requested;
    private long succeeded;
    private long viewed;
    private long saved;
    private long used;
    private long missingCorrelationEvents;
    private long withoutWindowEntryTasks;
    private long unmatchedStageEvents;
    private long sampledEvents;
}
