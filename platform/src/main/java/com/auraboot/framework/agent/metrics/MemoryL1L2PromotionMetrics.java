package com.auraboot.framework.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Metrics for the L1 -> L2 lifecycle promotion pipeline (PR-83 / Phase 2).
 *
 * <p>Design: {@code docs/plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md §8}.
 *
 * <p>Counters (Phase 2 scope — orphan cron + demoter counters land in PR-84):
 * <ul>
 *   <li>{@code auraboot_memory_tier_promotion_total{tenant, outcome}} —
 *       {@code outcome ∈ {promoted, skipped_low_score, skipped_dup, failed}}.</li>
 *   <li>{@code auraboot_memory_tier_event_total{tenant, event_type}} —
 *       one increment per audit row written to
 *       {@code ab_agent_memory_tier_event}.</li>
 * </ul>
 *
 * <p>Cardinality: O(tenants × small-enum) — Prometheus-safe.
 */
@Component
@RequiredArgsConstructor
public class MemoryL1L2PromotionMetrics {

    public static final String PROMOTION_TOTAL = "auraboot_memory_tier_promotion_total";
    public static final String EVENT_TOTAL = "auraboot_memory_tier_event_total";
    public static final String DEMOTION_TOTAL = "auraboot_memory_tier_demotion_total";

    public static final String OUTCOME_PROMOTED = "promoted";
    public static final String OUTCOME_SKIPPED_LOW_SCORE = "skipped_low_score";
    public static final String OUTCOME_SKIPPED_DUP = "skipped_dup";
    /** Phase 3: cosine-based (semantic) dedup hit. */
    public static final String OUTCOME_SKIPPED_DUP_SEMANTIC = "skipped_dup_semantic";
    public static final String OUTCOME_FAILED = "failed";

    /** Phase 3 demoter outcomes. */
    public static final String OUTCOME_DEMOTED = "demoted";
    public static final String OUTCOME_DEMOTE_SKIPPED = "skipped";

    public static final String EVENT_TYPE_L1_PROMOTED = "L1_PROMOTED";
    public static final String EVENT_TYPE_DEDUP_HIT = "DEDUP_HIT";
    public static final String EVENT_TYPE_L2_DEMOTED = "L2_DEMOTED";

    /** Phase 3: dedup mode code stored on {@code ab_agent_memory_tier_event.dedup_mode}. */
    public static final String DEDUP_MODE_HASH = "hash";
    public static final String DEDUP_MODE_COSINE = "cosine";

    private final MeterRegistry registry;

    public void recordPromotionOutcome(Long tenantId, String outcome) {
        Counter.builder(PROMOTION_TOTAL)
                .description("L1 -> L2 promotion attempts broken down by outcome")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment();
    }

    public void recordTierEvent(Long tenantId, String eventType) {
        Counter.builder(EVENT_TOTAL)
                .description("Rows written to ab_agent_memory_tier_event by type")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .tag("event_type", eventType == null ? "unknown" : eventType)
                .register(registry)
                .increment();
    }

    /**
     * Phase 3 — one increment per L2 row considered by {@code MemoryL1L2Demoter},
     * broken down by {@code outcome ∈ {demoted, skipped}}. Skipped rows still
     * increment so we can compute a skip rate for tuning the threshold.
     */
    public void recordDemotionOutcome(Long tenantId, String outcome) {
        Counter.builder(DEMOTION_TOTAL)
                .description("L2 -> L1 demotion attempts broken down by outcome")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment();
    }
}
