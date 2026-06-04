package com.auraboot.module.oee.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One row of the fleet OEE view: the computed rates (as 0-100 percentages, scale 1) plus the
 * six-big-losses for a single equipment over the requested window. Rates are pre-multiplied to
 * percent so dashboard widgets can bind a field directly without renderer-side formatting.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OeeFleetRow {
    private String equipmentId;
    private String code;
    private String name;

    private BigDecimal availabilityPct;   // 0-100
    private BigDecimal performancePct;    // 0-100
    private BigDecimal qualityPct;        // 0-100
    private BigDecimal oeePct;            // 0-100
    private BigDecimal teepPct;           // 0-100

    private BigDecimal breakdownHours;
    private BigDecimal setupHours;
    private BigDecimal minorStopHours;
    private BigDecimal speedLossUnits;
    private BigDecimal startupDefectUnits;
    private BigDecimal processDefectUnits;
}
