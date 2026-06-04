package com.auraboot.module.oee.service;

import com.auraboot.module.oee.dto.OeeEquipmentRef;
import com.auraboot.module.oee.dto.OeeFleetRow;
import com.auraboot.module.oee.dto.OeeFleetSummary;
import com.auraboot.module.oee.dto.OeeInputs;
import com.auraboot.module.oee.dto.OeeRequest;
import com.auraboot.module.oee.dto.OeeResult;
import com.auraboot.module.oee.engine.OeeCalculationEngine;
import com.auraboot.module.oee.port.OeeDataQueryPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link OeeFleetService} with a hand-written fake port and the real (pure)
 * {@link OeeCalculationEngine} — so the per-equipment numbers and the fleet roll-up are checked
 * against the actual engine, not hand-math.
 */
class OeeFleetServiceTest {

    private final OeeCalculationEngine engine = new OeeCalculationEngine();
    private final LocalDateTime start = LocalDateTime.parse("2026-06-01T00:00:00");
    private final LocalDateTime end = LocalDateTime.parse("2026-06-02T00:00:00");

    /** eqA: real data — calendar 8h, planned 1h + breakdown 1h -> loading 7, operating 6. */
    private OeeInputs eqAInputs() {
        return OeeInputs.builder()
            .calendarHours(new BigDecimal("8"))
            .downtimes(List.of(
                OeeInputs.Downtime.builder().type("planned").hours(new BigDecimal("1")).build(),
                OeeInputs.Downtime.builder().type("breakdown").hours(new BigDecimal("1")).build()))
            .actualQty(new BigDecimal("570")).defectQty(new BigDecimal("30"))
            .capacityPerHour(new BigDecimal("100"))
            .build();
    }

    /** eqB: idle — zero inputs (no loading). */
    private OeeInputs eqBInputs() {
        return OeeInputs.builder()
            .calendarHours(BigDecimal.ZERO).downtimes(List.of())
            .actualQty(BigDecimal.ZERO).defectQty(BigDecimal.ZERO).capacityPerHour(new BigDecimal("100"))
            .build();
    }

    private OeeDataQueryPort fakePort() {
        return new OeeDataQueryPort() {
            @Override
            public OeeInputs fetch(OeeRequest req) {
                return "eqA".equals(req.getEquipmentId()) ? eqAInputs() : eqBInputs();
            }

            @Override
            public List<OeeEquipmentRef> listEquipment(Long tenantId) {
                return List.of(
                    OeeEquipmentRef.builder().equipmentId("eqA").code("EQ-A").name("SMT Line A").build(),
                    OeeEquipmentRef.builder().equipmentId("eqB").code("EQ-B").name("AOI B").build());
            }
        };
    }

    private BigDecimal pct(BigDecimal rate) {
        return rate.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP);
    }

    @Test
    void fleet_returnsOneRowPerEquipment_withEnginePercentages() {
        OeeFleetService svc = new OeeFleetService(engine, fakePort());
        List<OeeFleetRow> rows = svc.fleet(1L, start, end);

        assertEquals(2, rows.size());
        OeeFleetRow a = rows.stream().filter(r -> "eqA".equals(r.getEquipmentId())).findFirst().orElseThrow();
        OeeFleetRow b = rows.stream().filter(r -> "eqB".equals(r.getEquipmentId())).findFirst().orElseThrow();

        OeeResult expectedA = engine.calculate(eqAInputs());
        assertEquals("SMT Line A", a.getName());
        assertEquals("EQ-A", a.getCode());
        assertEquals(0, pct(expectedA.getAvailability()).compareTo(a.getAvailabilityPct()));
        assertEquals(0, pct(expectedA.getPerformance()).compareTo(a.getPerformancePct()));
        assertEquals(0, pct(expectedA.getQuality()).compareTo(a.getQualityPct()));
        assertEquals(0, pct(expectedA.getOee()).compareTo(a.getOeePct()));
        assertTrue(a.getOeePct().signum() > 0, "eqA should have a non-zero OEE");
        // losses surfaced: breakdown 1h, planned/setup 1h
        assertEquals(0, expectedA.getLosses().getBreakdownHours().compareTo(a.getBreakdownHours()));

        // idle equipment -> zero, never null/NaN
        assertEquals(0, BigDecimal.ZERO.compareTo(b.getOeePct()));
    }

    @Test
    void summary_averagesRatesOverEquipmentWithData_sumsLosses_countsBoth() {
        OeeFleetService svc = new OeeFleetService(engine, fakePort());
        OeeFleetSummary s = svc.summary(1L, start, end);

        assertEquals(2, s.getEquipmentCount());
        assertEquals(1, s.getEquipmentWithDataCount(), "only eqA produced loading hours");

        // avg over the single with-data equipment == eqA's rates (idle eqB excluded from the avg)
        OeeResult expectedA = engine.calculate(eqAInputs());
        assertEquals(0, pct(expectedA.getOee()).compareTo(s.getOeePct()));
        assertEquals(0, pct(expectedA.getAvailability()).compareTo(s.getAvailabilityPct()));

        // losses summed across the fleet (eqB contributes 0)
        assertEquals(0, expectedA.getLosses().getBreakdownHours().compareTo(s.getBreakdownHours()));
    }

    @Test
    void fleet_noEquipment_returnsEmpty_summaryZero() {
        OeeDataQueryPort empty = new OeeDataQueryPort() {
            @Override public OeeInputs fetch(OeeRequest req) { return eqBInputs(); }
            @Override public List<OeeEquipmentRef> listEquipment(Long tenantId) { return List.of(); }
        };
        OeeFleetService svc = new OeeFleetService(engine, empty);

        assertTrue(svc.fleet(1L, start, end).isEmpty());
        OeeFleetSummary s = svc.summary(1L, start, end);
        assertEquals(0, s.getEquipmentCount());
        assertEquals(0, s.getEquipmentWithDataCount());
        assertEquals(0, BigDecimal.ZERO.compareTo(s.getOeePct()));
    }
}
