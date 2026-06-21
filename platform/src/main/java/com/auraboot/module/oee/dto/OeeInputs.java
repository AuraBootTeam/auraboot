package com.auraboot.module.oee.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Raw inputs supplied by the data-source port; the engine derives the OEE rates from these.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OeeInputs {
    private BigDecimal calendarHours;        // calendar time (sum of resource_calendar available hours)
    private List<Downtime> downtimes;        // downtime records (already filtered by equipment + window)
    private BigDecimal actualQty;            // actual output (SUM pe_woo_actual_qty)
    private BigDecimal defectQty;            // defect count (SUM pe_woo_defect_qty)
    private BigDecimal capacityPerHour;      // theoretical capacity per hour (pe_res_capacity_per_hour)

    // Optional telemetry-derived signals (Option A / GreptimeDB convergence, DDR-2026-06-21 D5).
    // When telemetryOperatingHours != null, the engine sources availability/performance/quality from
    // these real device signals instead of deriving them from calendar/downtime/actualQty; downtimes
    // are then used ONLY for the six-big-losses reason breakdown.
    private BigDecimal telemetryOperatingHours;  // measured run-time (running-signal TWA), hours
    private BigDecimal telemetryOutputQty;       // measured produced count (counter delta), pieces
    private BigDecimal telemetryGoodQty;         // measured good count, pieces

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Downtime {
        private String type;                 // planned / unplanned / breakdown
        private BigDecimal hours;            // pe_dt_duration_hours
    }
}
