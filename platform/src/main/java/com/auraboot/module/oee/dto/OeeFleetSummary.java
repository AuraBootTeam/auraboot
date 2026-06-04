package com.auraboot.module.oee.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Fleet-level OEE roll-up over the requested window: average rates (percent, scale 1) across the
 * equipment that produced data, total losses across the fleet, and equipment counts. Drives the KPI
 * cards of the OEE 大屏. Averages exclude equipment with zero loading hours (no-data) so idle
 * equipment does not drag the fleet rates to zero.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OeeFleetSummary {
    private BigDecimal availabilityPct;   // avg over equipment with data, 0-100
    private BigDecimal performancePct;
    private BigDecimal qualityPct;
    private BigDecimal oeePct;
    private BigDecimal teepPct;

    private BigDecimal breakdownHours;    // fleet total
    private BigDecimal setupHours;
    private BigDecimal minorStopHours;
    private BigDecimal speedLossUnits;
    private BigDecimal startupDefectUnits;
    private BigDecimal processDefectUnits;

    private int equipmentCount;           // all equipment of the tenant
    private int equipmentWithDataCount;   // equipment with non-zero OEE inputs in the window
}
