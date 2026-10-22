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
    /** Phase 4 (PR-85) — admin-triggered promote-now counter. */
    public static final String ADMIN_PROMOTE_TOTAL = "auraboot_memory_tier_admin_promote_total";
    /**
     * Phase 4 Round-3 (I2) — counter fired when a scheduler tick is skipped
     * because the current JVM did not win the {@code ab_scheduler_leader}
     * election for its job. Gives operators a Prometheus signal distinguishing
     * "scheduler healthy but other instance is leader" from "scheduler broken"
     * (silent log.debug alone left the healthy-but-idle case indistinguishable
     * from the broken case).
     */
    public static final String LEADER_SKIPPED_TOTAL = "auraboot_memory_l1l2_leader_skipped_total";

    /**
     * Phase 4 (terminal-state fix 2026-04-19) — one increment per
     * {@link com.auraboot.framework.agent.memory.SessionEndedEvent} received by
     * the promoter, labelled by the run's terminal outcome
     * {@code succeeded | cancelled | failed}. Lets alerting distinguish
     * "session-end pipeline healthy" from "only success events are firing"
     * (which was the pre-fix state — cancel/fail did not publish).
     */
    public static final String SESSION_ENDED_TOTAL = "auraboot_memory_tier_session_ended_total";

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
    /** Phase 4 (PR-85) — admin override flipped a session row to user. */
    public static final String EVENT_TYPE_ADMIN_PROMOTED = "admin_promoted";
    /**
     * Phase 3 Round-2: audit event emitted when a candidate is skipped on the
     * race path (atomic UPDATE matched 0 rows because a concurrent promoter
     * already flipped {@code category}). Separate from {@code DEDUP_HIT} so
     * that hash/cosine dedup and race-loss can be counted independently in
     * audit-driven analytics.
     */
    public static final String EVENT_TYPE_DEDUP_SKIPPED = "dedup_skipped";

    /** Phase 3: dedup mode code stored on {@code ab_agent_memory_tier_event.dedup_mode}. */
    public static final String DEDUP_MODE_HASH = "hash";
    public static final String DEDUP_MODE_COSINE = "cosine";
    /**
     * Phase 3 Round-2: race-path dedup. Used when the atomic UPDATE that flips
     * {@code category session -> user} affects 0 rows because another process
     * won the race and already promoted the same L1 pid. Audit invariant: every
     * outcome writes an audit row — race path is indistinguishable from a hash
     * hit at the DB layer, but we tag it {@code race} so postmortems can tell
     * the two apart.
     */
    public static final String DEDUP_MODE_RACE = "race";

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
    /**
     * Phase 4 (PR-85) — one increment per admin-triggered
     * {@code POST /api/admin/memory/{pid}/promote-now} call, broken down by
     * {@code outcome ∈ {promoted, skipped_dup, skipped_dup_semantic, conflict}}.
     */
    public void recordAdminPromoteOutcome(Long tenantId, String outcome) {
        Counter.builder(ADMIN_PROMOTE_TOTAL)
                .description("Admin-triggered L1->L2 promote-now attempts by outcome")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment();
    }

    /**
     * Phase 4 Round-3 (I2) — record a scheduler tick that was skipped because
     * this instance is not the current leader for {@code jobCode}. Labelled
     * with {@code instance_id} so per-replica behaviour is observable;
     * cardinality is bounded by deploy size and stable per JVM (UUID assigned
     * once at startup — see {@link com.auraboot.framework.agent.memory.MemoryL1L2LeaderElection}).
     */
    public void recordLeaderSkipped(String jobCode, String instanceId) {
        Counter.builder(LEADER_SKIPPED_TOTAL)
                .description("Scheduler ticks skipped because another instance holds the leader lease")
                .tag("job_code", jobCode == null ? "unknown" : jobCode)
                .tag("instance_id", instanceId == null ? "unknown" : instanceId)
                .register(registry)
                .increment();
    }

    /**
     * Record one SessionEndedEvent received by the promoter listener. The
     * {@code outcome} label is the lowercase {@link com.auraboot.framework.agent.memory.SessionEndedEvent.TerminalOutcome}
     * name ({@code succeeded}, {@code cancelled}, {@code failed}).
     */
    public void recordSessionEnded(Long tenantId, String outcome) {
        Counter.builder(SESSION_ENDED_TOTAL)
                .description("SessionEndedEvent received by L1->L2 promoter, labelled by terminal outcome")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment();
    }

    public void recordDemotionOutcome(Long tenantId, String outcome) {
        Counter.builder(DEMOTION_TOTAL)
                .description("L2 -> L1 demotion attempts broken down by outcome")
                .tag("tenant", tenantId == null ? "unknown" : tenantId.toString())
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment();
    }
}
