package com.auraboot.framework.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Operational metrics for the Memory Promotion pipeline (PR-65).
 *
 * <p>Phase 1 only instruments the extractor side — proposal creation. Decision
 * / retraction / activation counters come with Phase 2 once the applier and
 * activator exist.
 *
 * <ul>
 *   <li>{@code auraboot_memory_promotion_proposal_total{tenant, reason_code}} —
 *       every time the extractor inserts a DRAFT_PENDING_REVIEW row.</li>
 * </ul>
 *
 * Cardinality is O(tenant × reason_code) where reason_code is a small
 * bounded enum — safe for Prometheus.
 */
@Component
@RequiredArgsConstructor
public class MemoryPromotionMetrics {

    public static final String PROPOSAL_TOTAL = "auraboot_memory_promotion_proposal_total";

    public static final String REASON_CROSS_USER_AGREEMENT = "cross_user_agreement";
    public static final String REASON_IMPLICIT_CO_SIGN = "implicit_co_sign";
    public static final String REASON_IMPORTANCE_SPIKE = "importance_spike";
    public static final String REASON_SESSION_UPGRADE = "session_upgrade";

    private final MeterRegistry registry;

    public void recordProposal(Long tenantId, String reasonCode) {
        Counter.builder(PROPOSAL_TOTAL)
                .description("Memory promotion proposals emitted by the extractor")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .tag("reason_code", reasonCode == null ? "unknown" : reasonCode)
                .register(registry)
                .increment();
    }
}
