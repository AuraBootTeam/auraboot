package com.auraboot.module.oee.engine;

import com.auraboot.module.oee.dto.OeeInputs;
import com.auraboot.module.oee.dto.OeeResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure OEE algorithm engine: OeeInputs -> OeeResult. No Spring dependency, so it is unit-testable. */
@Service
public class OeeCalculationEngine {

    private static final int SCALE = 6;
    private static final BigDecimal ONE = BigDecimal.ONE;

    /** Safe division: a divisor <= 0 returns 0, avoiding NaN / divide-by-zero (OEE boundary: no data = 0, not an error). */
    private BigDecimal safeDiv(BigDecimal a, BigDecimal b) {
        if (b == null || b.signum() <= 0 || a == null) {
            return BigDecimal.ZERO;
        }
        return a.divide(b, SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public OeeResult calculate(OeeInputs in) {
        BigDecimal calendar = nz(in.getCalendarHours());
        BigDecimal planned = BigDecimal.ZERO;
        BigDecimal unplanned = BigDecimal.ZERO;
        BigDecimal breakdown = BigDecimal.ZERO;
        if (in.getDowntimes() != null) {
            for (OeeInputs.Downtime d : in.getDowntimes()) {
                String type = d.getType() == null ? "" : d.getType();
                switch (type) {
                    case "planned" -> planned = planned.add(nz(d.getHours()));
                    case "unplanned" -> unplanned = unplanned.add(nz(d.getHours()));
                    case "breakdown" -> breakdown = breakdown.add(nz(d.getHours()));
                    default -> { }
                }
            }
        }
        BigDecimal loading = calendar.subtract(planned).max(BigDecimal.ZERO);     // loading time = calendar - planned downtime

        // Option A / GreptimeDB convergence (DDR-2026-06-21 D5): when telemetry signals are present,
        // source availability/performance/quality from the real device run-time/output/good; downtimes
        // are then used ONLY for the six-big-losses reason breakdown below. Absent telemetry -> legacy
        // downtime-derived path (backward compatible).
        boolean telemetry = in.getTelemetryOperatingHours() != null;
        BigDecimal operating;
        if (telemetry) {
            operating = nz(in.getTelemetryOperatingHours()).min(loading);          // measured run-time, capped at loading (<=100%)
        } else {
            BigDecimal availLoss = unplanned.add(breakdown);
            operating = loading.subtract(availLoss).max(BigDecimal.ZERO);          // operating time
        }
        BigDecimal availability = safeDiv(operating, loading);

        BigDecimal theoretical = operating.multiply(nz(in.getCapacityPerHour()));   // theoretical output
        BigDecimal actual = telemetry ? nz(in.getTelemetryOutputQty()) : nz(in.getActualQty());
        BigDecimal performance = safeDiv(actual, theoretical).min(ONE);             // cap 1.0
        BigDecimal speedLoss = theoretical.subtract(actual).max(BigDecimal.ZERO);   // speed loss (pieces)

        // setupHours this period = planned downtime (dictionary has no changeover split, see grounding note 5);
        // when pe_downtime_type adds "changeover", split it out here.

        BigDecimal good = telemetry
            ? nz(in.getTelemetryGoodQty())
            : actual.subtract(nz(in.getDefectQty())).max(BigDecimal.ZERO);
        BigDecimal quality = safeDiv(good, actual);
        BigDecimal processDefect = telemetry ? actual.subtract(good).max(BigDecimal.ZERO) : nz(in.getDefectQty());
        BigDecimal oee = availability.multiply(performance).multiply(quality).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal teep = oee.multiply(safeDiv(loading, calendar)).setScale(SCALE, RoundingMode.HALF_UP);

        return OeeResult.builder()
            .availability(availability)
            .performance(performance).quality(quality).oee(oee).teep(teep)
            .losses(OeeResult.SixBigLosses.builder()
                .breakdownHours(breakdown).setupHours(planned).minorStopHours(BigDecimal.ZERO)
                .speedLossUnits(speedLoss).startupDefectUnits(BigDecimal.ZERO).processDefectUnits(processDefect).build())
            .build();
    }
}
