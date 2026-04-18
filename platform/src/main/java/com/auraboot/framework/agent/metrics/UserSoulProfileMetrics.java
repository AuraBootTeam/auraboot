package com.auraboot.framework.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Operational metrics for the User Soul Profile pipeline (PR-75, plan §8).
 *
 * <p>Phase 1 only instruments the deriver side — DRAFT creation outcomes.
 * Activation / edit / stale counters come with later phases.
 *
 * <ul>
 *   <li>{@code auraboot_user_soul_profile_derivation_total{tenant, outcome}} —
 *       emitted by {@code UserSoulProfileDeriver} once per user attempt.
 *       {@code outcome} ∈ {drafted, skipped_no_change,
 *       skipped_too_little_signal, failed}.</li>
 * </ul>
 *
 * <p>Cardinality: O(tenant × outcome) where outcome is a bounded enum —
 * safe for Prometheus.
 */
@Component
@RequiredArgsConstructor
public class UserSoulProfileMetrics {

    public static final String DERIVATION_TOTAL = "auraboot_user_soul_profile_derivation_total";

    public static final String OUTCOME_DRAFTED = "drafted";
    public static final String OUTCOME_SKIPPED_NO_CHANGE = "skipped_no_change";
    public static final String OUTCOME_SKIPPED_TOO_LITTLE_SIGNAL = "skipped_too_little_signal";
    public static final String OUTCOME_FAILED = "failed";

    private final MeterRegistry registry;

    public void recordDerivation(Long tenantId, String outcome) {
        Counter.builder(DERIVATION_TOTAL)
                .description("User Soul Profile derivation outcomes per tenant")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment();
    }
}
