package com.auraboot.module.oee.engine;

import com.auraboot.module.oee.dto.OeeInputs;
import com.auraboot.module.oee.dto.OeeResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OeeCalculationEngineTest {

    private final OeeCalculationEngine engine = new OeeCalculationEngine();

    private OeeInputs.Downtime dt(String type, String h) {
        return OeeInputs.Downtime.builder().type(type).hours(new BigDecimal(h)).build();
    }

    @Test
    void availability_excludesPlannedDowntimeFromLoading_countsUnplannedAsLoss() {
        // calendar 8h; planned downtime 1h (lunch/planned maintenance) -> loading = 7h;
        // unplanned + breakdown = 1h loss -> operating = 6h
        OeeInputs in = OeeInputs.builder()
            .calendarHours(new BigDecimal("8"))
            .downtimes(List.of(dt("planned", "1"), dt("unplanned", "0.5"), dt("breakdown", "0.5")))
            .actualQty(new BigDecimal("600")).defectQty(BigDecimal.ZERO)
            .capacityPerHour(new BigDecimal("100"))
            .build();
        OeeResult r = engine.calculate(in);
        // availability = operating 6 / loading 7 = 0.8571
        assertEquals(0, new BigDecimal("0.8571").compareTo(r.getAvailability().setScale(4, RoundingMode.HALF_UP)));
    }

    @Test
    void availability_zeroLoading_returnsZeroNotNaN() {
        OeeInputs in = OeeInputs.builder()
            .calendarHours(BigDecimal.ZERO).downtimes(List.of())
            .actualQty(BigDecimal.ZERO).defectQty(BigDecimal.ZERO).capacityPerHour(new BigDecimal("100"))
            .build();
        OeeResult r = engine.calculate(in);
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getAvailability()));  // not NaN / no throw
    }

    @Test
    void performance_actualOverTheoretical_cappedAtOne() {
        // operating 6h x capacity 100 = theoretical 600; actual 600 -> performance = 1.0
        OeeInputs in = OeeInputs.builder()
            .calendarHours(new BigDecimal("8"))
            .downtimes(List.of(OeeInputs.Downtime.builder().type("breakdown").hours(new BigDecimal("2")).build()))
            .actualQty(new BigDecimal("600")).defectQty(BigDecimal.ZERO).capacityPerHour(new BigDecimal("100"))
            .build();
        // loading 8, loss 2, operating 6, theoretical = 6x100 = 600, actual 600
        OeeResult r = engine.calculate(in);
        assertEquals(0, new BigDecimal("1.000000").compareTo(r.getPerformance()));
    }

    @Test
    void performance_overproduction_capsAtOne_notAboveOne() {
        // actual 700 > theoretical 600 -> performance capped at 1.0 (OEE convention: never exceeds 100%)
        OeeInputs in = OeeInputs.builder()
            .calendarHours(new BigDecimal("8"))
            .downtimes(List.of(OeeInputs.Downtime.builder().type("breakdown").hours(new BigDecimal("2")).build()))
            .actualQty(new BigDecimal("700")).defectQty(BigDecimal.ZERO).capacityPerHour(new BigDecimal("100"))
            .build();
        OeeResult r = engine.calculate(in);
        assertEquals(0, BigDecimal.ONE.compareTo(r.getPerformance()));
    }

    @Test
    void quality_and_overallOee_compose() {
        // loading 8, loss 2, operating 6; theoretical 600, actual 600 (performance 1.0);
        // defect 30 -> quality = (600-30)/600 = 0.95
        OeeInputs in = OeeInputs.builder()
            .calendarHours(new BigDecimal("8"))
            .downtimes(List.of(OeeInputs.Downtime.builder().type("breakdown").hours(new BigDecimal("2")).build()))
            .actualQty(new BigDecimal("600")).defectQty(new BigDecimal("30")).capacityPerHour(new BigDecimal("100"))
            .build();
        OeeResult r = engine.calculate(in);
        assertEquals(0, new BigDecimal("0.7500").compareTo(r.getAvailability().setScale(4, RoundingMode.HALF_UP))); // 6/8
        assertEquals(0, new BigDecimal("0.9500").compareTo(r.getQuality().setScale(4, RoundingMode.HALF_UP)));
        // OEE = 0.75 x 1.0 x 0.95 = 0.7125
        assertEquals(0, new BigDecimal("0.7125").compareTo(r.getOee().setScale(4, RoundingMode.HALF_UP)));
    }
}
