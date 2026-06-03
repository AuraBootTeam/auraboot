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
        BigDecimal availLoss = unplanned.add(breakdown);
        BigDecimal operating = loading.subtract(availLoss).max(BigDecimal.ZERO);  // operating time
        BigDecimal availability = safeDiv(operating, loading);

        // performance / quality / OEE -> filled in Task 4 / 5
        return OeeResult.builder()
            .availability(availability)
            .performance(BigDecimal.ZERO).quality(BigDecimal.ZERO).oee(BigDecimal.ZERO).teep(BigDecimal.ZERO)
            .losses(OeeResult.SixBigLosses.builder()
                .breakdownHours(breakdown).setupHours(planned).minorStopHours(BigDecimal.ZERO)
                .speedLossUnits(BigDecimal.ZERO).startupDefectUnits(BigDecimal.ZERO).processDefectUnits(BigDecimal.ZERO).build())
            .build();
    }
}
