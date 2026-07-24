package com.auraboot.framework.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Compensation-outcome counter for durable tool executions (see
 * {@link com.auraboot.framework.agent.runtime.DurableToolCompensationService}).
 *
 * <p>The scheduled compensation sweep dispatches each compensation-required record to the first
 * {@link com.auraboot.framework.agent.runtime.DurableToolCompensationHandler} that supports it, or
 * — when none does — leaves it pending. That pending path previously produced only a repeated INFO
 * log every cron tick, invisible to alerting: an operator could not see compensations piling up
 * unhandled, which is exactly the signal that a domain still needs its own handler ("按域补").
 *
 * <p>{@code aura_agent_tool_compensation_total{tenant,outcome}} — one increment per record the sweep
 * processes, tagged by outcome:
 * <ul>
 *   <li>{@code compensated} — a handler rolled it back; ledger marked compensated.</li>
 *   <li>{@code pending_no_handler} — no handler claimed it; still compensation-required. A non-zero
 *       rate here is the "a domain needs a handler" alert.</li>
 *   <li>{@code handler_incomplete} — a handler ran but did not complete the rollback.</li>
 *   <li>{@code failed} — a handler threw while compensating.</li>
 * </ul>
 *
 * <p>Cardinality: O(tenants × 4) — Prometheus-safe. Tenant tag falls back to {@code "unknown"}.
 */
@Component
@RequiredArgsConstructor
public class DurableToolCompensationMetrics {

    public static final String OUTCOME_NAME = "aura_agent_tool_compensation_total";

    public static final String OUTCOME_COMPENSATED = "compensated";
    public static final String OUTCOME_PENDING_NO_HANDLER = "pending_no_handler";
    public static final String OUTCOME_HANDLER_INCOMPLETE = "handler_incomplete";
    public static final String OUTCOME_FAILED = "failed";

    private final MeterRegistry registry;

    /** Record one processed compensation-required record with its outcome. */
    public void record(Long tenantId, String outcome) {
        Counter.builder(OUTCOME_NAME)
                .description("Durable tool compensation-required records processed by the sweep, by outcome")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
