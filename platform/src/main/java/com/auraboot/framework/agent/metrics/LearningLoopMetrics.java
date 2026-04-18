package com.auraboot.framework.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Operational metrics for the Learning Loop (PR-49).
 *
 * Two Micrometer counters let operators tune thresholds and catch
 * scheduler drift from Grafana without reading DB rows:
 *
 * <ul>
 *   <li>{@code auraboot_shadow_run_outcome_total{tenant,outcome}} —
 *       every ShadowExecutor run emits here. {@code outcome} ∈
 *       {executed, skipped_ineligible, skipped_not_found}.</li>
 *   <li>{@code auraboot_promotion_decision_total{tenant,decision}} —
 *       every PromotionEvaluator invocation emits here. {@code decision}
 *       ∈ {PROMOTE, BELOW_THRESHOLD, INSUFFICIENT_RUNS, NOT_FOUND}.</li>
 * </ul>
 *
 * Tags are bounded: outcome/decision are enum-valued, tenant is the
 * numeric tenant_id. Cardinality is O(tenant × enum) which is fine.
 */
@Component
@RequiredArgsConstructor
public class LearningLoopMetrics {

    public static final String SHADOW_RUN_OUTCOME = "auraboot_shadow_run_outcome_total";
    public static final String PROMOTION_DECISION = "auraboot_promotion_decision_total";

    private final MeterRegistry registry;

    public void recordShadowRunOutcome(Long tenantId, String outcome) {
        Counter.builder(SHADOW_RUN_OUTCOME)
                .description("ShadowExecutor outcome per invocation")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment();
    }

    public void recordPromotionDecision(Long tenantId, String decision) {
        Counter.builder(PROMOTION_DECISION)
                .description("PromotionEvaluator decision per invocation")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .tag("decision", decision == null ? "unknown" : decision)
                .register(registry)
                .increment();
    }
}
