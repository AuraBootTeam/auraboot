package com.auraboot.framework.semantic.compiler;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * Resolves {@link SemanticQueryRequest.TimeRange} into a {@code [from, to]}
 * {@link LocalDate} pair suitable for parameterised {@code BETWEEN ? AND ?}.
 *
 * <p>v0.1 preset set: {@code ytd, mtd, qtd, last_7_days, last_30_days,
 * last_month, custom}. Anything else throws
 * {@link MetricCompileException}{@code (TIMERANGE_INVALID)}.
 *
 * <p>{@code custom} requires both {@code from} and {@code to}.
 *
 * <p>"Today" is sourced from {@code LocalDate.now()} which uses the JVM
 * default zone. v0.2 will accept an explicit tenant timezone; for v0.1 we
 * accept the convention used by the rest of the platform.
 */
public final class TimeRangeResolver {

    private TimeRangeResolver() {}

    public static List<LocalDate> resolve(SemanticQueryRequest.TimeRange tr) {
        if (tr == null) {
            return List.of();
        }
        return resolve(tr, LocalDate.now());
    }

    /** Test-friendly overload with injectable "today". */
    static List<LocalDate> resolve(SemanticQueryRequest.TimeRange tr, LocalDate today) {
        if (tr == null) {
            return List.of();
        }
        String preset = tr.getPreset() == null ? "" : tr.getPreset().toLowerCase();
        switch (preset) {
            case "ytd":
                return List.of(today.with(TemporalAdjusters.firstDayOfYear()), today);
            case "mtd":
                return List.of(today.with(TemporalAdjusters.firstDayOfMonth()), today);
            case "qtd": {
                int q = today.get(IsoFields.QUARTER_OF_YEAR);
                LocalDate qStart = LocalDate.of(today.getYear(), (q - 1) * 3 + 1, 1);
                return List.of(qStart, today);
            }
            case "last_7_days":
                return List.of(today.minus(7, ChronoUnit.DAYS), today);
            case "last_30_days":
                return List.of(today.minus(30, ChronoUnit.DAYS), today);
            case "last_month": {
                LocalDate firstOfThisMonth = today.with(TemporalAdjusters.firstDayOfMonth());
                LocalDate lastMonth = firstOfThisMonth.minusDays(1);
                return List.of(lastMonth.with(TemporalAdjusters.firstDayOfMonth()), lastMonth);
            }
            case "custom":
                if (tr.getFrom() == null || tr.getTo() == null) {
                    throw new MetricCompileException("TIMERANGE_INVALID",
                            "preset=custom requires both from and to");
                }
                try {
                    return List.of(LocalDate.parse(tr.getFrom()), LocalDate.parse(tr.getTo()));
                } catch (RuntimeException e) {
                    throw new MetricCompileException("TIMERANGE_INVALID",
                            "from/to must be ISO-8601 dates: " + e.getMessage(), e);
                }
            default:
                throw new MetricCompileException("TIMERANGE_INVALID",
                        "Unknown preset: " + tr.getPreset());
        }
    }
}
