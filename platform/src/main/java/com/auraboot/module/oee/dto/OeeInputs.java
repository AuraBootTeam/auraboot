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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Downtime {
        private String type;                 // planned / unplanned / breakdown
        private BigDecimal hours;            // pe_dt_duration_hours
    }
}
