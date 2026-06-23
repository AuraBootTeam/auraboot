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

    @Test
    void teep_equalsOeeTimesLoadingOverCalendar() {
        // calendar 8h; planned 1h -> loading 7h; breakdown 1h -> operating 6h.
        // availability = 6/7; theoretical = 6 x 100 = 600, actual 600 -> performance 1.0; defect 0 -> quality 1.0.
        // TEEP must equal oee x (loading/calendar) = oee x (7/8).
        OeeInputs in = OeeInputs.builder()
            .calendarHours(new BigDecimal("8"))
            .downtimes(List.of(dt("planned", "1"), dt("breakdown", "1")))
            .actualQty(new BigDecimal("600")).defectQty(BigDecimal.ZERO)
            .capacityPerHour(new BigDecimal("100"))
            .build();
        OeeResult r = engine.calculate(in);
        // expected TEEP = oee x 7/8, computed from the engine's own oee and compared with tolerance.
        BigDecimal expectedTeep = r.getOee()
            .multiply(new BigDecimal("7").divide(new BigDecimal("8"), 10, RoundingMode.HALF_UP));
        assertEquals(0, expectedTeep.setScale(6, RoundingMode.HALF_UP)
            .compareTo(r.getTeep().setScale(6, RoundingMode.HALF_UP)));
        // sanity: TEEP < OEE because loading (7) < calendar (8)
        assertEquals(-1, r.getTeep().compareTo(r.getOee()));
    }

    @Test
    void sixBigLosses_breakdownSetupSpeedProcessFields() {
        // calendar 10h; planned 2h -> loading 8h; breakdown 1h + unplanned 1h -> operating 6h.
        // theoretical = operating(6) x capacity(100) = 600; actual 500 -> speed loss = 600 - 500 = 100.
        // defect 40 -> processDefect = 40. setupHours = planned = 2. breakdownHours = 1.
        OeeInputs in = OeeInputs.builder()
            .calendarHours(new BigDecimal("10"))
            .downtimes(List.of(dt("planned", "2"), dt("breakdown", "1"), dt("unplanned", "1")))
            .actualQty(new BigDecimal("500")).defectQty(new BigDecimal("40"))
            .capacityPerHour(new BigDecimal("100"))
            .build();
        OeeResult.SixBigLosses l = engine.calculate(in).getLosses();
        assertEquals(0, new BigDecimal("1").compareTo(l.getBreakdownHours()));          // sum of breakdown hours
        assertEquals(0, new BigDecimal("2").compareTo(l.getSetupHours()));              // engine: setup = planned downtime
        assertEquals(0, new BigDecimal("100").compareTo(l.getSpeedLossUnits()));        // operating(6)x100 - actual(500)
        assertEquals(0, new BigDecimal("40").compareTo(l.getProcessDefectUnits()));     // = defectQty
        // placeholders that the engine currently does not derive (documented as NOT_AVAILABLE / folded-in)
        assertEquals(0, BigDecimal.ZERO.compareTo(l.getMinorStopHours()));
        assertEquals(0, BigDecimal.ZERO.compareTo(l.getStartupDefectUnits()));
    }

    @Test
    void speedLoss_neverNegative_whenActualExceedsTheoretical() {
        // operating 6h x 100 = theoretical 600; actual 700 (overproduction) -> speed loss floored at 0.
        OeeInputs in = OeeInputs.builder()
            .calendarHours(new BigDecimal("8"))
            .downtimes(List.of(dt("breakdown", "2")))
            .actualQty(new BigDecimal("700")).defectQty(BigDecimal.ZERO)
            .capacityPerHour(new BigDecimal("100"))
            .build();
        OeeResult.SixBigLosses l = engine.calculate(in).getLosses();
        assertEquals(0, BigDecimal.ZERO.compareTo(l.getSpeedLossUnits()));
    }

    @Test
    void multipleDowntimes_aggregatePerTypeAndComputeAvailability() {
        // calendar 10h; 2 planned (0.5+0.5=1h) -> loading 9h;
        // 2 unplanned (0.5+0.5=1h) + 1 breakdown (1h) = 2h availability loss -> operating 7h.
        // availability = operating 7 / loading 9.
        OeeInputs in = OeeInputs.builder()
            .calendarHours(new BigDecimal("10"))
            .downtimes(List.of(
                dt("planned", "0.5"), dt("planned", "0.5"),
                dt("unplanned", "0.5"), dt("unplanned", "0.5"),
                dt("breakdown", "1")))
            .actualQty(new BigDecimal("700")).defectQty(BigDecimal.ZERO)
            .capacityPerHour(new BigDecimal("100"))
            .build();
        OeeResult r = engine.calculate(in);
        // aggregated loss buckets surface through the loss fields
        assertEquals(0, new BigDecimal("1").compareTo(r.getLosses().getBreakdownHours()));   // 1 breakdown row
        assertEquals(0, new BigDecimal("1").compareTo(r.getLosses().getSetupHours()));        // 0.5 + 0.5 planned
        // availability = 7/9 = 0.7778
        assertEquals(0, new BigDecimal("0.7778").compareTo(r.getAvailability().setScale(4, RoundingMode.HALF_UP)));
    }

    @Test
    void emptyDowntimeList_noLoss_loadingEqualsCalendar() {
        // no downtime rows -> loading = calendar = 8h, operating = 8h, availability = 1.0, all losses 0.
        OeeInputs in = OeeInputs.builder()
            .calendarHours(new BigDecimal("8")).downtimes(List.of())
            .actualQty(new BigDecimal("800")).defectQty(BigDecimal.ZERO)
            .capacityPerHour(new BigDecimal("100"))
            .build();
        OeeResult r = engine.calculate(in);
        assertEquals(0, BigDecimal.ONE.compareTo(r.getAvailability()));                       // operating 8 / loading 8
        OeeResult.SixBigLosses l = r.getLosses();
        assertEquals(0, BigDecimal.ZERO.compareTo(l.getBreakdownHours()));
        assertEquals(0, BigDecimal.ZERO.compareTo(l.getSetupHours()));
        assertEquals(0, BigDecimal.ZERO.compareTo(l.getSpeedLossUnits()));                    // theoretical 800 - actual 800
        assertEquals(0, BigDecimal.ZERO.compareTo(l.getProcessDefectUnits()));
    }

    @Test
    void nullDowntimeList_doesNotNpe_treatedAsNoDowntime() {
        // downtimes == null must not throw; engine guards with a null check.
        OeeInputs in = OeeInputs.builder()
            .calendarHours(new BigDecimal("8")).downtimes(null)
            .actualQty(new BigDecimal("400")).defectQty(new BigDecimal("20"))
            .capacityPerHour(new BigDecimal("100"))
            .build();
        OeeResult r = engine.calculate(in);  // no NPE
        assertEquals(0, BigDecimal.ONE.compareTo(r.getAvailability()));   // loading = calendar = 8, operating = 8
        OeeResult.SixBigLosses l = r.getLosses();
        assertEquals(0, BigDecimal.ZERO.compareTo(l.getBreakdownHours()));
        assertEquals(0, BigDecimal.ZERO.compareTo(l.getSetupHours()));
        assertEquals(0, new BigDecimal("20").compareTo(l.getProcessDefectUnits()));  // defect still flows through
    }

    @Test
    void telemetrySourcedAPQ_overrideDowntimeDerivation_butLossBreakdownStaysFromDowntimes() {
        // Option A / GreptimeDB convergence (DDR-2026-06-21 D5): when telemetry-measured
        // run-time / output / good are present, A/P/Q come from the real device signals;
        // downtimes drive ONLY the six-big-losses reason breakdown.
        // calendar 10h; planned 2h -> loading 8h. Telemetry operating = 6h (real run signal),
        // INDEPENDENT of downtime subtraction (which would give loading 8 - breakdown 1 = 7h).
        // availability must be 6/8 = 0.75 (telemetry), NOT the downtime-derived 7/8 = 0.875.
        OeeInputs in = OeeInputs.builder()
            .calendarHours(new BigDecimal("10"))
            .downtimes(List.of(dt("planned", "2"), dt("breakdown", "1")))
            // Postgres output/defect are intentionally wrong; telemetry must win:
            .actualQty(new BigDecimal("999")).defectQty(new BigDecimal("999"))
            .capacityPerHour(new BigDecimal("100"))
            // telemetry-derived signals (the new optional block):
            .telemetryOperatingHours(new BigDecimal("6"))
            .telemetryOutputQty(new BigDecimal("540"))
            .telemetryGoodQty(new BigDecimal("513"))
            .build();
        OeeResult r = engine.calculate(in);
        // A/P/Q from telemetry:
        assertEquals(0, new BigDecimal("0.7500").compareTo(r.getAvailability().setScale(4, RoundingMode.HALF_UP))); // 6/8, not 7/8
        assertEquals(0, new BigDecimal("0.9000").compareTo(r.getPerformance().setScale(4, RoundingMode.HALF_UP)));  // 540/(6*100)
        assertEquals(0, new BigDecimal("0.9500").compareTo(r.getQuality().setScale(4, RoundingMode.HALF_UP)));      // 513/540
        assertEquals(0, new BigDecimal("0.6413").compareTo(r.getOee().setScale(4, RoundingMode.HALF_UP)));          // 0.75*0.90*0.95
        // six-big-losses breakdown STILL from downtime records, unaffected by telemetry:
        OeeResult.SixBigLosses l = r.getLosses();
        assertEquals(0, new BigDecimal("1").compareTo(l.getBreakdownHours()));      // breakdown downtime
        assertEquals(0, new BigDecimal("2").compareTo(l.getSetupHours()));          // planned downtime
        assertEquals(0, new BigDecimal("60").compareTo(l.getSpeedLossUnits()));     // 600 - 540 (telemetry output)
        assertEquals(0, new BigDecimal("27").compareTo(l.getProcessDefectUnits())); // 540 - 513 (telemetry-derived defect)
    }

    @Test
    void telemetryOperatingHoursAboveLoading_capsAvailabilityAtOne() {
        // Real run-time signals can be noisy at the window boundary; cap measured operating time at
        // loading time so dashboard rates never exceed 100%.
        OeeInputs in = OeeInputs.builder()
            .calendarHours(new BigDecimal("8"))
            .downtimes(List.of())
            .actualQty(BigDecimal.ZERO).defectQty(BigDecimal.ZERO)
            .capacityPerHour(new BigDecimal("100"))
            .telemetryOperatingHours(new BigDecimal("10"))
            .telemetryOutputQty(new BigDecimal("800"))
            .telemetryGoodQty(new BigDecimal("760"))
            .build();
        OeeResult r = engine.calculate(in);
        assertEquals(0, BigDecimal.ONE.compareTo(r.getAvailability()));
        assertEquals(0, BigDecimal.ONE.compareTo(r.getPerformance()));
        assertEquals(0, new BigDecimal("0.9500").compareTo(r.getQuality().setScale(4, RoundingMode.HALF_UP)));
    }

    @Test
    void telemetryAbsent_fallsBackToDowntimeDerivation_backwardCompatible() {
        // No telemetry block -> engine behaves exactly as before (downtime-derived A/P/Q).
        // Same downtimes as above: loading 8, operating = 8 - breakdown 1 = 7, availability = 7/8 = 0.875.
        OeeInputs in = OeeInputs.builder()
            .calendarHours(new BigDecimal("10"))
            .downtimes(List.of(dt("planned", "2"), dt("breakdown", "1")))
            .actualQty(new BigDecimal("700")).defectQty(new BigDecimal("35"))
            .capacityPerHour(new BigDecimal("100"))
            .build();
        OeeResult r = engine.calculate(in);
        assertEquals(0, new BigDecimal("0.8750").compareTo(r.getAvailability().setScale(4, RoundingMode.HALF_UP))); // 7/8 downtime-derived
    }
}
