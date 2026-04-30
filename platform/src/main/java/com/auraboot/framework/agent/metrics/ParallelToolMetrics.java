package com.auraboot.framework.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Operational metrics for ACP P0-5 parallel tool execution
 * (see {@link com.auraboot.framework.agent.service.StepLoopService#processToolUseBlocksParallel}).
 *
 * <p>Four meters let operators tune the {@code aura.agent.parallel.*} knobs
 * (max-fanout, total-timeout-ms) and catch runaway agent behaviour from
 * Grafana without scraping run logs:
 *
 * <ul>
 *   <li>{@code aura_agent_parallel_tool_fanout{tenant}} — DistributionSummary,
 *       one observation per dispatched batch (value = number of tool_use blocks
 *       in that LLM turn). p50/p95 here drives the right max-fanout setting.</li>
 *   <li>{@code aura_agent_parallel_tool_max_latency_ms{tenant}} —
 *       DistributionSummary, one observation per batch (value = the slowest
 *       per-tool latency within the batch in ms). This is the user-perceived
 *       wall clock of the parallel batch, not the sum.</li>
 *   <li>{@code aura_agent_parallel_tool_rejected_total{tenant}} — Counter,
 *       one increment per batch rejected because {@code fanout > maxFanout}.</li>
 *   <li>{@code aura_agent_parallel_tool_timeout_total{tenant}} — Counter,
 *       one increment when {@link java.util.concurrent.CompletableFuture#allOf}
 *       waits past {@code totalTimeoutMs} and we have to cancel still-running
 *       worker futures.</li>
 * </ul>
 *
 * <p>Cardinality: O(tenants) per meter — Prometheus-safe. Tenant tag falls
 * back to {@code "unknown"} when the call site has no resolvable tenant
 * (e.g. system-context boot-time invocation).
 */
@Component
@RequiredArgsConstructor
public class ParallelToolMetrics {

    public static final String FANOUT_NAME = "aura_agent_parallel_tool_fanout";
    public static final String MAX_LATENCY_NAME = "aura_agent_parallel_tool_max_latency_ms";
    public static final String REJECTED_NAME = "aura_agent_parallel_tool_rejected_total";
    public static final String TIMEOUT_NAME = "aura_agent_parallel_tool_timeout_total";

    private final MeterRegistry registry;

    /**
     * Record the fanout (number of tool_use blocks) of a parallel batch. Called
     * once per dispatched batch — including the rejected and serial-fallback
     * paths — so the histogram reflects the LLM's raw tool-emission shape, not
     * just the parallel-eligible subset.
     */
    public void recordFanout(Long tenantId, int fanout) {
        DistributionSummary.builder(FANOUT_NAME)
                .description("Number of tool_use blocks in a parallel batch dispatched by StepLoopService")
                .baseUnit("tools")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .register(registry)
                .record(fanout);
    }

    /**
     * Record the slowest per-tool latency within a completed parallel batch (ms).
     * For batches of 1, this is just that tool's latency; for larger batches
     * this is {@code max(latencies)} since user-perceived wall clock equals the
     * worst tool, not the sum.
     */
    public void recordMaxLatency(Long tenantId, long ms) {
        DistributionSummary.builder(MAX_LATENCY_NAME)
                .description("Max per-tool latency (ms) within a completed parallel batch")
                .baseUnit("ms")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .register(registry)
                .record(ms);
    }

    /**
     * One increment per batch rejected because {@code fanout > maxFanout}.
     * Counted at batch level, not per tool — a runaway 50-tool batch that
     * trips the limit is one rejection event, not 50.
     */
    public void recordRejected(Long tenantId) {
        Counter.builder(REJECTED_NAME)
                .description("Parallel tool batches rejected because fanout exceeded max-fanout cap")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .register(registry)
                .increment();
    }

    /**
     * One increment per batch that hit the {@code totalTimeoutMs} wait on
     * {@code CompletableFuture.allOf().get(...)} and had to cancel still-running
     * worker futures. Aligns 1:1 with the {@code log.warn("Parallel tool batch
     * timed out ...")} entry.
     */
    public void recordTimeout(Long tenantId) {
        Counter.builder(TIMEOUT_NAME)
                .description("Parallel tool batches that exceeded total-timeout-ms wait on allOf().get(...)")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .register(registry)
                .increment();
    }
}
