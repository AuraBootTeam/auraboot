package com.auraboot.framework.agent.memory.audit;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * Pure-function metrics for Spike-2 annotated conflict samples.
 *
 * <p>Stateless / no DB / no LLM — same discipline as
 * {@link com.auraboot.framework.rag.eval.RetrievalMetrics}.
 *
 * <p>See {@code docs/backlog/2026-05-27-spike-2-memory-prompt-audit-design.md} §2.2 / §5.
 */
public final class ConflictMetrics {

    private ConflictMetrics() {}

    /**
     * Distribution by tag. Returns immutable map keyed by {@link ConflictTag}.
     */
    public static Map<ConflictTag, Integer> distribution(Collection<ConflictAnnotation> annotations) {
        Map<ConflictTag, Integer> out = new EnumMap<>(ConflictTag.class);
        for (ConflictTag t : ConflictTag.values()) out.put(t, 0);
        if (annotations == null) return out;
        for (ConflictAnnotation a : annotations) {
            ConflictTag t = a.effectiveTag();
            out.merge(t, 1, Integer::sum);
        }
        return out;
    }

    /**
     * 矛盾召回率 = (temporal + factual + granularity) / total.
     *
     * <p>{@code unclear} annotations are excluded from the denominator — they
     * represent reviewer disagreement, not a clear data point. If every
     * annotation is unclear, the rate is reported as {@code NaN} (caller
     * must escalate to second-round review).
     *
     * @throws IllegalArgumentException if input is null or empty
     */
    public static double conflictRate(Collection<ConflictAnnotation> annotations) {
        if (annotations == null || annotations.isEmpty()) {
            throw new IllegalArgumentException(
                    "conflictRate undefined for empty annotation set");
        }
        Map<ConflictTag, Integer> dist = distribution(annotations);
        int denominator = annotations.size() - dist.get(ConflictTag.UNCLEAR);
        if (denominator == 0) return Double.NaN;
        long conflicts = 0;
        for (Map.Entry<ConflictTag, Integer> e : dist.entrySet()) {
            if (e.getKey().isConflict()) conflicts += e.getValue();
        }
        return (double) conflicts / denominator;
    }

    /**
     * Whether the observed rate clears the DDR-B "worth-it" threshold.
     * Per design doc §1: "if the rate is < 5%, the dual-zone is solving a
     * non-problem."  Threshold defaults to 0.05.
     */
    public static boolean justifiesDualZoneSchema(double rate) {
        return justifiesDualZoneSchema(rate, 0.05);
    }

    public static boolean justifiesDualZoneSchema(double rate, double threshold) {
        if (Double.isNaN(rate)) return false;
        return rate >= threshold;
    }
}
