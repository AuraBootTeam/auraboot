package com.auraboot.framework.agent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * PR-85 / Phase 4 — coarse leader election for per-instance schedulers.
 *
 * <p>Design: {@code docs/plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md §9.2}.
 *
 * <p>Advisory locks {@code 7311} / {@code 7312} protect each instance from
 * overlapping its own ticks, but they are per-connection: two JVMs running
 * the same scheduler will BOTH acquire the lock on their own Postgres
 * sessions and BOTH scan the same rows. Content-hash dedup keeps the
 * outcome correct, but metrics double-count, DB load doubles, and the
 * {@code ab_agent_memory_tier_event} audit table grows twice as fast.
 *
 * <p>This component adds a coarser gate <b>before</b> the advisory-lock
 * attempt: the first instance to upsert into {@code ab_scheduler_leader}
 * within the last {@link #HEARTBEAT_WINDOW_SECONDS} becomes the leader.
 * Subsequent instances (or the same instance on later ticks) renew their
 * own leadership or skip the tick when another instance holds it.
 *
 * <p>Red-line compliance:
 * <ul>
 *   <li>No fallback / ensure / retry — a DB error propagates; the
 *       scheduler treats that tick as non-leader and skips.</li>
 *   <li>Disabled by default via {@code acp.memory.l1l2.leader-election.enabled}
 *       so existing single-instance deployments are unaffected.</li>
 *   <li>{@link #instanceId} is generated once per JVM via UUID; never
 *       persisted, never reused.</li>
 * </ul>
 */
@Slf4j
@Component
public class MemoryL1L2LeaderElection {

    /** Heartbeat window — leaders must renew within this many seconds or lose the lease. */
    public static final int HEARTBEAT_WINDOW_SECONDS = 60;

    /** Job code for the orphan scanner (PR-84 / Phase 3). */
    public static final String JOB_ORPHAN = "memory_l1l2_orphan";

    /** Job code for the demoter (PR-84 / Phase 3). */
    public static final String JOB_DEMOTER = "memory_l1l2_demoter";

    private final JdbcTemplate jdbc;
    private final String instanceId;

    @Value("${acp.memory.l1l2.leader-election.enabled:false}")
    private boolean enabled;

    @Autowired
    public MemoryL1L2LeaderElection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.instanceId = UUID.randomUUID().toString();
    }

    /**
     * Package-visible test constructor — lets tests drive two instances with
     * distinct synthetic ids against the same DB.
     */
    MemoryL1L2LeaderElection(JdbcTemplate jdbc, String instanceId, boolean enabled) {
        this.jdbc = jdbc;
        this.instanceId = instanceId;
        this.enabled = enabled;
    }

    /** Instance id assigned at startup. Exposed for logging / tests. */
    public String getInstanceId() {
        return instanceId;
    }

    /** Whether the feature flag is on. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Try to become (or renew as) leader for {@code jobCode}. Returns
     * {@code true} when this instance holds the lease; {@code false} when
     * another instance is leader within {@link #HEARTBEAT_WINDOW_SECONDS}.
     *
     * <p>Semantics:
     * <ul>
     *   <li>No row exists → insert as leader, return true.</li>
     *   <li>Row exists, {@code instance_id} matches self → bump heartbeat,
     *       return true.</li>
     *   <li>Row exists, {@code instance_id} differs, {@code heartbeat_at <
     *       NOW() - 60s} → stale leader; takeover, return true.</li>
     *   <li>Row exists, {@code instance_id} differs, {@code heartbeat_at}
     *       fresh → another instance is leader; return false.</li>
     * </ul>
     *
     * <p>When the feature flag is off, always returns {@code true} — the
     * legacy single-instance behaviour (every scheduler acts alone).
     */
    public boolean acquire(String jobCode) {
        if (!enabled) {
            return true;
        }
        if (jobCode == null || jobCode.isBlank()) {
            throw new IllegalArgumentException("jobCode required");
        }

        // Upsert that atomically (a) inserts our row when absent, or
        // (b) takes over when the existing row is stale or already ours.
        // The WHERE on the UPDATE branch is what prevents a fresh leader
        // from being overwritten.
        int updated = jdbc.update(
                "INSERT INTO ab_scheduler_leader (job_code, instance_id, heartbeat_at) "
                        + "VALUES (?, ?, NOW()) "
                        + "ON CONFLICT (job_code) DO UPDATE "
                        + "SET instance_id = EXCLUDED.instance_id, "
                        + "    heartbeat_at = NOW() "
                        + "WHERE ab_scheduler_leader.instance_id = EXCLUDED.instance_id "
                        + "   OR ab_scheduler_leader.heartbeat_at < NOW() - make_interval(secs => ?)",
                jobCode, instanceId, HEARTBEAT_WINDOW_SECONDS);

        if (updated == 0) {
            // Another live instance holds the lease — our ON CONFLICT UPDATE
            // WHERE filtered us out. No row change, no takeover.
            return false;
        }

        // The INSERT-or-UPDATE happened; we may still need to confirm the
        // current row actually belongs to us (paranoia: a concurrent
        // takeover by a third instance could race us between two ticks).
        List<String> owners = jdbc.queryForList(
                "SELECT instance_id FROM ab_scheduler_leader WHERE job_code = ?",
                String.class, jobCode);
        if (owners.isEmpty()) {
            // Should be impossible — we just wrote it. Treat as non-leader.
            return false;
        }
        return instanceId.equals(owners.get(0));
    }

    /**
     * Best-effort release — used by tests and graceful shutdown. Does not
     * affect correctness: a released job code simply reverts to "absent",
     * and the next {@link #acquire} re-elects. No-op when not leader.
     */
    public void release(String jobCode) {
        if (!enabled) return;
        jdbc.update(
                "DELETE FROM ab_scheduler_leader WHERE job_code = ? AND instance_id = ?",
                jobCode, instanceId);
    }
}
