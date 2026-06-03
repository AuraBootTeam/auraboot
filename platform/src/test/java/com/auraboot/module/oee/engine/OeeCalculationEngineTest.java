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
}
