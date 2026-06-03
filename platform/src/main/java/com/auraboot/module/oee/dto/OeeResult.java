package com.auraboot.module.oee.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OeeResult {
    private BigDecimal availability;   // availability rate 0-1
    private BigDecimal performance;    // performance rate 0-1
    private BigDecimal quality;        // quality rate 0-1
    private BigDecimal oee;            // = A x P x Q
    private BigDecimal teep;           // = oee x (loadingHours / calendarHours)
    private SixBigLosses losses;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SixBigLosses {  // unit: hours / pieces
        private BigDecimal breakdownHours;     // breakdown downtime
        private BigDecimal setupHours;         // setup/adjustment (= planned downtime this period, see engine note)
        private BigDecimal minorStopHours;     // minor stops (performance loss, placeholder 0 - NOT_AVAILABLE without high-frequency data)
        private BigDecimal speedLossUnits;     // speed loss (theoretical output - actual output)
        private BigDecimal startupDefectUnits; // startup scrap (folded into process scrap this period)
        private BigDecimal processDefectUnits; // process scrap (= defectQty)
    }
}
