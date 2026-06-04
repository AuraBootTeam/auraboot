package com.auraboot.module.oee.service;

import com.auraboot.module.oee.dto.OeeEquipmentRef;
import com.auraboot.module.oee.dto.OeeFleetRow;
import com.auraboot.module.oee.dto.OeeFleetSummary;
import com.auraboot.module.oee.dto.OeeInputs;
import com.auraboot.module.oee.dto.OeeRequest;
import com.auraboot.module.oee.dto.OeeResult;
import com.auraboot.module.oee.engine.OeeCalculationEngine;
import com.auraboot.module.oee.port.OeeDataQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Fleet OEE roll-up: computes per-equipment OEE for every equipment of a tenant over a window and a
 * fleet-level summary. Reuses {@link OeeCalculationEngine} + {@link OeeDataQueryPort} — no formula
 * duplication. Rates are exposed as 0-100 percentages (scale 1) so dashboard widgets bind directly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OeeFleetService {

    private static final int PCT_SCALE = 1;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final OeeCalculationEngine engine;
    private final OeeDataQueryPort port;

    /** Per-equipment OEE rows for every equipment of the tenant in the window. */
    public List<OeeFleetRow> fleet(Long tenantId, LocalDateTime start, LocalDateTime end) {
        return compute(tenantId, start, end).stream().map(Computed::row).toList();
    }

    /**
     * Fleet-level roll-up: average rates over the equipment that produced data (idle equipment is
     * excluded from the average so it does not drag rates to zero), total losses across the whole
     * fleet, and equipment counts.
     */
    public OeeFleetSummary summary(Long tenantId, LocalDateTime start, LocalDateTime end) {
        List<Computed> all = compute(tenantId, start, end);
        List<Computed> withData = all.stream().filter(Computed::hasData).toList();
        return OeeFleetSummary.builder()
            .availabilityPct(avgPct(withData, OeeFleetRow::getAvailabilityPct))
            .performancePct(avgPct(withData, OeeFleetRow::getPerformancePct))
            .qualityPct(avgPct(withData, OeeFleetRow::getQualityPct))
            .oeePct(avgPct(withData, OeeFleetRow::getOeePct))
            .teepPct(avgPct(withData, OeeFleetRow::getTeepPct))
            .breakdownHours(sum(all, OeeFleetRow::getBreakdownHours))
            .setupHours(sum(all, OeeFleetRow::getSetupHours))
            .minorStopHours(sum(all, OeeFleetRow::getMinorStopHours))
            .speedLossUnits(sum(all, OeeFleetRow::getSpeedLossUnits))
            .startupDefectUnits(sum(all, OeeFleetRow::getStartupDefectUnits))
            .processDefectUnits(sum(all, OeeFleetRow::getProcessDefectUnits))
            .equipmentCount(all.size())
            .equipmentWithDataCount(withData.size())
            .build();
    }

    /** One pass: fetch + calculate every equipment once; reused by fleet() and summary(). */
    private List<Computed> compute(Long tenantId, LocalDateTime start, LocalDateTime end) {
        List<Computed> out = new ArrayList<>();
        for (OeeEquipmentRef ref : port.listEquipment(tenantId)) {
            OeeRequest req = OeeRequest.builder()
                .tenantId(tenantId)
                .equipmentId(ref.getEquipmentId())
                .windowStart(start)
                .windowEnd(end)
                .build();
            OeeInputs inputs = port.fetch(req);
            OeeResult r = engine.calculate(inputs);
            boolean hasData = inputs.getCalendarHours() != null && inputs.getCalendarHours().signum() > 0;
            out.add(new Computed(toRow(ref, r), hasData));
        }
        return out;
    }

    private OeeFleetRow toRow(OeeEquipmentRef ref, OeeResult r) {
        OeeResult.SixBigLosses l = r.getLosses();
        return OeeFleetRow.builder()
            .equipmentId(ref.getEquipmentId())
            .code(ref.getCode())
            .name(ref.getName())
            .availabilityPct(pct(r.getAvailability()))
            .performancePct(pct(r.getPerformance()))
            .qualityPct(pct(r.getQuality()))
            .oeePct(pct(r.getOee()))
            .teepPct(pct(r.getTeep()))
            .breakdownHours(nz(l == null ? null : l.getBreakdownHours()))
            .setupHours(nz(l == null ? null : l.getSetupHours()))
            .minorStopHours(nz(l == null ? null : l.getMinorStopHours()))
            .speedLossUnits(nz(l == null ? null : l.getSpeedLossUnits()))
            .startupDefectUnits(nz(l == null ? null : l.getStartupDefectUnits()))
            .processDefectUnits(nz(l == null ? null : l.getProcessDefectUnits()))
            .build();
    }

    /** rate (0-1) -> percentage (0-100, scale 1). Null-safe. */
    private static BigDecimal pct(BigDecimal rate) {
        if (rate == null) {
            return BigDecimal.ZERO.setScale(PCT_SCALE, RoundingMode.HALF_UP);
        }
        return rate.multiply(HUNDRED).setScale(PCT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    private static BigDecimal avgPct(List<Computed> rows, Function<OeeFleetRow, BigDecimal> field) {
        if (rows.isEmpty()) {
            return BigDecimal.ZERO.setScale(PCT_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal total = rows.stream()
            .map(c -> nz(field.apply(c.row())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(rows.size()), PCT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<Computed> rows, Function<OeeFleetRow, BigDecimal> field) {
        return rows.stream()
            .map(c -> nz(field.apply(c.row())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private record Computed(OeeFleetRow row, boolean hasData) {
    }
}
