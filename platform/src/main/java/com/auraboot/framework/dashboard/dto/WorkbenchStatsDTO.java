package com.auraboot.framework.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Aggregated statistics for the workbench dashboard.
 * Each entry in the stats map represents a single metric card.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkbenchStatsDTO {

    /**
     * Map of stat key (e.g. "inbox_pending") to its StatItem.
     */
    private Map<String, StatItem> stats;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StatItem {
        /** The metric value (Number, String, etc.) */
        private Object value;

        /** i18n label key, e.g. "workbench.stats.inbox_pending" */
        private String label;

        /** Display format: "number", "currency", "percent" */
        private String format;

        /** Optional trend indicator */
        private Trend trend;

        /** Optional time series (e.g. 7 daily snapshots) for sparkline rendering. Null when no history is available. */
        private Series series;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Trend {
        /** "up", "down", or "flat" */
        private String direction;

        /** Trend value (e.g. 12.5 for 12.5%) */
        private Object value;

        /** Comparison period, e.g. "last_week", "last_month" */
        private String period;

        /** Unit of the trend value, e.g. "percent", "absolute" */
        private String unit;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Series {
        /** Granularity: "day" | "week" | "month". This round only emits "day". */
        private String period;

        /** Oldest → newest. For "day"/7 this is 7 entries; numeric only. */
        private List<Number> points;
    }
}
