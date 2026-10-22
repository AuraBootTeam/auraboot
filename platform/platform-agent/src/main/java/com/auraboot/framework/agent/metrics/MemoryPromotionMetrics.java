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
    public static final String DECISION_TOTAL = "auraboot_memory_promotion_decision_total";
    public static final String SHADOW_RETRACTION_TOTAL = "auraboot_memory_promotion_shadow_retraction_total";
    public static final String EMBEDDING_DIM_MISMATCH_TOTAL = "auraboot_memory_embedding_dim_mismatch_total";

    public static final String REASON_CROSS_USER_AGREEMENT = "cross_user_agreement";
    public static final String REASON_IMPLICIT_CO_SIGN = "implicit_co_sign";
    public static final String REASON_IMPORTANCE_SPIKE = "importance_spike";
    public static final String REASON_SESSION_UPGRADE = "session_upgrade";

    public static final String DECISION_APPROVE = "APPROVE";
    public static final String DECISION_REJECT = "REJECT";
    public static final String DECISION_RETRACT = "RETRACT";
    public static final String DECISION_ACTIVATE = "ACTIVATE";
    public static final String DECISION_EXPIRE = "EXPIRE";

    private final MeterRegistry registry;

    public void recordProposal(Long tenantId, String reasonCode) {
        Counter.builder(PROPOSAL_TOTAL)
                .description("Memory promotion proposals emitted by the extractor")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .tag("reason_code", reasonCode == null ? "unknown" : reasonCode)
                .register(registry)
                .increment();
    }

    /**
     * Record a review/activation/expire decision. {@code rejectReason} applies
     * only when {@code decision == REJECT} — it is recorded as the "reason"
     * tag to enable per-reason breakdowns. Pass null for non-reject decisions
     * and "unknown" will be used.
     */
    public void recordDecision(Long tenantId, String decision, String rejectReason) {
        Counter.builder(DECISION_TOTAL)
                .description("Memory promotion review / lifecycle decisions")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .tag("decision", decision == null ? "unknown" : decision)
                .tag("reason", rejectReason == null ? "none" : rejectReason)
                .register(registry)
                .increment();
    }

    /**
     * Record an embedding dimension mismatch or null/empty vector returned
     * by the embedding provider (PR-74 / N5). Tagged with tenant + provider
     * + observed dim so ops can diagnose a misconfigured model without
     * reading logs.
     */
    public void recordEmbeddingDimMismatch(Long tenantId, String provider, int actualDim) {
        Counter.builder(EMBEDDING_DIM_MISMATCH_TOTAL)
                .description("Embedding vectors rejected due to null/empty/dim-mismatch")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .tag("provider", provider == null ? "unknown" : provider)
                .tag("actual_dim", Integer.toString(actualDim))
                .register(registry)
                .increment();
    }

    public void recordShadowRetraction(Long tenantId) {
        Counter.builder(SHADOW_RETRACTION_TOTAL)
                .description("Memory promotions retracted during the 7-day shadow window")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .register(registry)
                .increment();
    }
}
