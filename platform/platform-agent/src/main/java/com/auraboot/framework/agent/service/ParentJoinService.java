package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.crosstenant.CrossTenantAclDeniedException;
import com.auraboot.framework.agent.crosstenant.CrossTenantAclService;
import com.auraboot.framework.agent.crosstenant.CrossTenantDecision;
import com.auraboot.framework.agent.crosstenant.CrossTenantGrantType;
import com.auraboot.framework.agent.memory.SessionEndedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridges {@link SessionEndedEvent} (fired by every run that reaches a
 * terminal state) into {@link ChildRunCompletedEvent} (fired only for runs
 * that have a {@code parent_run_id}, so the parent run / dispatcher can
 * observe child completion without polling), and exposes a synchronous
 * {@link #joinChildRun(String, String, long)} blocking-join API on top of
 * the same event for DSL workflows / multi-step LLM reasoning that need
 * "wait for child, then continue".
 *
 * <p>P1 fire-and-forget mode (existing): the parent does NOT block; it just
 * receives a {@code ChildRunCompletedEvent} when the child terminates.
 *
 * <p>P2 blocking-join (new — ACP backlog C.1): {@link #joinChildRun} blocks
 * the calling thread up to {@code timeoutMs} for a specific (parent, child)
 * pair, returning the {@link ChildRunOutcome} (terminal label + cost +
 * tokens) read from {@code ab_agent_run}. Implementation is a per-key
 * {@link CountDownLatch} populated by the existing {@code @EventListener},
 * with a race-free DB readback so that a child that already terminated
 * before the join call returns immediately rather than dead-locking on a
 * latch that no event will ever signal.
 *
 * <p>Listener semantics (unchanged):
 * <p>Backlog D.3 — child cost reverse rollup:
 * <ul>
 *   <li>The bridge SELECT now also pulls {@code total_cost / input_tokens /
 *       output_tokens} from the child row and forwards them on the event so
 *       any listener (rollup, future shadow-run replay) sees the full cost
 *       picture without re-querying.</li>
 *   <li>{@link #onChildCompleted(ChildRunCompletedEvent)} performs an atomic
 *       single-statement {@code UPDATE} on the parent row, incrementing
 *       {@code child_aggregate_cost} and {@code child_aggregate_tokens}. The
 *       UPDATE runs even when the parent row is already terminal — the
 *       rollup column is independent of {@code run_status}, so finance /
 *       quota accounting reconciles regardless of finish-order.</li>
 *   <li>Cross-tenant defence: the rollup UPDATE filters on
 *       {@code tenant_id = ?} from the event; a malformed event whose tenant
 *       does not match the parent row would update zero rows (logged at WARN,
 *       no fallback).</li>
 * </ul>
 *
 * <p>Listener semantics for the bridge:
 * <ul>
 *   <li>SELECT {@code parent_run_id, tenant_id, total_cost, input_tokens,
 *       output_tokens} from {@code ab_agent_run} for the run id in the
 *       {@code SessionEndedEvent}.</li>
 *   <li>If the row is missing or {@code parent_run_id IS NULL} (root run):
 *       short-circuit, no event published.</li>
 *   <li>Otherwise publish {@code ChildRunCompletedEvent} carrying parent pid +
 *       child pid + outcome label + cost + total tokens.</li>
 * </ul>
 *
 * <p>Red-line: no fallback / placeholder values; if a row is malformed
 * (missing tenant_id), the listener logs and skips. The bridge is
 * non-transactional — it only does a SELECT + publish. The rollup listener
 * is also non-transactional — it relies on the atomicity of the single
 * {@code UPDATE} statement, not on the surrounding tx.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParentJoinService {

    private final ApplicationEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;
    private final CrossTenantAclService crossTenantAclService;

    /**
     * Active join slots keyed by (parentRunId, childRunId). A slot exists only
     * while at least one thread is awaiting that pair; the joiner removes its
     * own slot in a {@code finally} block so the map cannot grow unboundedly.
     *
     * <p>Concurrency model: one slot per (parent, child) pair is shared by all
     * threads calling {@link #joinChildRun} for the same pair. They all await
     * the same {@link CountDownLatch} and read the same result reference, so
     * each receives the same {@link ChildRunOutcome}. The first joiner to
     * enter the {@code finally} block removes the slot; subsequent late
     * arrivals (very rare — they'd need to enter while latch is still 0) re-
     * register a fresh slot and immediately observe terminal state via the
     * DB-readback path.
     */
    private final ConcurrentHashMap<JoinKey, JoinSlot> slots = new ConcurrentHashMap<>();

    @EventListener
    public void onSessionEnded(SessionEndedEvent event) {
        String childRunId = event.getRunId();
        if (childRunId == null || childRunId.isBlank()) {
            log.debug("ParentJoinService: SessionEndedEvent with blank runId, skipping");
            return;
        }

        // D.3: SELECT now pulls cost / tokens too so the rollup event carries
        // the full payload without a second round-trip.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT parent_run_id, tenant_id, total_cost, input_tokens, output_tokens " +
                        "FROM ab_agent_run WHERE pid = ?",
                childRunId);
        if (rows.isEmpty()) {
            log.debug("ParentJoinService: run {} not found, skipping (already deleted?)", childRunId);
            return;
        }
        Map<String, Object> row = rows.get(0);
        String parentRunId = (String) row.get("parent_run_id");
        if (parentRunId == null || parentRunId.isBlank()) {
            // Root run — no parent to notify.
            log.debug("ParentJoinService: run {} is a root run (parent_run_id is null), no notification", childRunId);
            return;
        }
        Long tenantId = row.get("tenant_id") == null
                ? null : ((Number) row.get("tenant_id")).longValue();
        if (tenantId == null) {
            log.warn("ParentJoinService: run {} missing tenant_id, skipping ChildRunCompletedEvent", childRunId);
            return;
        }

        // Cross-tenant gate: if parent and child live in different tenants
        // an explicit grant is required. Without one, silently drop the
        // event (DEBUG log only) — same behaviour as pre-C.2 cross-tenant
        // events that were dropped unconditionally; C.2 only opens the
        // gate when ACL approves.
        Long parentTenantId = readTenantId(parentRunId);
        if (parentTenantId != null && !Objects.equals(parentTenantId, tenantId)) {
            CrossTenantDecision decision = crossTenantAclService.evaluate(
                    parentTenantId, tenantId, CrossTenantGrantType.SPAWN_SUB_AGENT);
            if (!decision.isAllowed()) {
                log.debug("ParentJoinService: dropping cross-tenant ChildRunCompletedEvent "
                                + "(parent={}, child={}, parent_tenant={}, child_tenant={}, decision={})",
                        parentRunId, childRunId, parentTenantId, tenantId, decision.code());
                return;
            }
        }
        // Zero-normalise nullable numeric columns. Column DEFAULTs are 0 in
        // schema.sql, but defence-in-depth in case a row was inserted from
        // an external loader that bypassed the default.
        BigDecimal totalCost = row.get("total_cost") == null
                ? BigDecimal.ZERO : new BigDecimal(row.get("total_cost").toString());
        long inputTokens = row.get("input_tokens") == null
                ? 0L : ((Number) row.get("input_tokens")).longValue();
        long outputTokens = row.get("output_tokens") == null
                ? 0L : ((Number) row.get("output_tokens")).longValue();
        long totalTokens = inputTokens + outputTokens;

        String outcome = event.getOutcome() == null
                ? "unknown" : event.getOutcome().name().toLowerCase();
        eventPublisher.publishEvent(new ChildRunCompletedEvent(
                tenantId, parentRunId, childRunId, outcome, totalCost, totalTokens));
        log.info("ParentJoinService: child={} → parent={} outcome={} cost={} tokens={}",
                childRunId, parentRunId, outcome, totalCost, totalTokens);
    }

    /**
     * Backlog D.3 — reverse rollup of child run cost / tokens into the parent.
     *
     * <p>Atomic single-statement {@code UPDATE} keyed on {@code (tenant_id,
     * pid)}. Runs regardless of parent {@code run_status} (the parent may
     * already be terminal — late-arrival is the whole point of D.3). The
     * cross-tenant filter is defence-in-depth: a malformed event whose
     * {@code tenantId} does not match the parent row updates zero rows.
     *
     * <p>Skips zero-amount events to avoid pointless UPDATEs (no-op rows
     * still flush WAL). When both cost and tokens are zero, the child either
     * never made an LLM call or the row was reset before terminal — either
     * way nothing to roll up.
     */
    @EventListener
    public void onChildCompleted(ChildRunCompletedEvent event) {
        BigDecimal cost = event.getTotalCost();
        long tokens = event.getTotalTokens();
        if (cost.signum() == 0 && tokens == 0L) {
            log.debug("ParentJoinService: child={} → parent={} zero cost/tokens, skipping rollup",
                    event.getChildRunId(), event.getParentRunId());
            return;
        }

        // Atomic add-and-set on the parent row. COALESCE guards against any
        // legacy NULL that escaped the column DEFAULT (e.g. row inserted
        // before the migration ran on a stale environment).
        int updated = jdbcTemplate.update(
                "UPDATE ab_agent_run SET " +
                        "  child_aggregate_cost   = COALESCE(child_aggregate_cost,   0) + ?, " +
                        "  child_aggregate_tokens = COALESCE(child_aggregate_tokens, 0) + ?, " +
                        "  updated_at             = CURRENT_TIMESTAMP " +
                        " WHERE pid = ? AND tenant_id = ?",
                cost, tokens, event.getParentRunId(), event.getTenantId());

        if (updated == 0) {
            // Parent row missing OR cross-tenant mismatch. Either way no
            // rollup happened — log so the gap is visible. No fallback /
            // retry: a real bug should surface, not silently no-op.
            log.warn("ParentJoinService: rollup updated 0 rows — parent={} tenant={} child={} "
                            + "(parent missing or cross-tenant?)",
                    event.getParentRunId(), event.getTenantId(), event.getChildRunId());
            return;
        }
        log.debug("ParentJoinService: rolled up child={} cost={} tokens={} into parent={}",
                event.getChildRunId(), cost, tokens, event.getParentRunId());
    }

    /**
     * Listener that wakes any thread blocked in {@link #joinChildRun}. The
     * listener method is split from the bridge above so a slot waiter is not
     * coupled to the SessionEndedEvent → ChildRunCompletedEvent translation
     * order: even if the slot was registered AFTER the bridge published, this
     * listener still fires (Spring delivers ChildRunCompletedEvent
     * synchronously to all @EventListener methods on the bean).
     */
    @EventListener
    public void onChildRunCompleted(ChildRunCompletedEvent event) {
        JoinKey key = new JoinKey(event.getParentRunId(), event.getChildRunId());
        JoinSlot slot = slots.get(key);
        if (slot == null) {
            // No one is waiting for this (parent, child) pair right now.
            // The join API itself does a DB-readback after registering the
            // slot, so a join call that arrives later still sees terminal
            // state without depending on this signal.
            return;
        }
        slot.outcomeLabel.compareAndSet(null, event.getOutcome());
        slot.latch.countDown();
    }

    /**
     * Block until {@code childRunId} reaches terminal state (or timeout).
     *
     * <p>Race-free against pre-existing terminal state: the slot is
     * registered BEFORE the DB read, so a child that flips to terminal
     * concurrently with the registration either (a) signals the freshly-
     * registered slot via {@link #onChildRunCompleted} or (b) is already
     * terminal in the DB readback below. Either way the joiner returns
     * without dead-locking.
     *
     * @param parentRunId non-null parent {@code ab_agent_run.pid}
     * @param childRunId  non-null child {@code ab_agent_run.pid}
     * @param timeoutMs   maximum wait in milliseconds; must be ≥ 0
     * @return {@link ChildRunOutcome} populated from {@code ab_agent_run}
     * @throws JoinTimeoutException     if the latch was not signaled within
     *                                  {@code timeoutMs} and the DB still
     *                                  shows a non-terminal status
     * @throws IllegalStateException    if the child run is missing, has a
     *                                  different tenant than the parent, or
     *                                  the parent run row does not exist
     * @throws IllegalArgumentException if any argument is null/blank/negative
     */
    public ChildRunOutcome joinChildRun(String parentRunId, String childRunId, long timeoutMs) {
        if (parentRunId == null || parentRunId.isBlank()) {
            throw new IllegalArgumentException("parentRunId required");
        }
        if (childRunId == null || childRunId.isBlank()) {
            throw new IllegalArgumentException("childRunId required");
        }
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("timeoutMs must be ≥ 0");
        }

        // --- Tenant-isolation guard -----------------------------------------
        // Mirror SubAgentRunner.spawn: a join across tenants is a caller bug,
        // not a soft permission denial. We read both tenant_ids in one round-
        // trip and compare; mismatch → IllegalStateException with no waiting.
        Map<String, Long> tenants = readTenantsForBoth(parentRunId, childRunId);
        Long parentTenant = tenants.get("parent");
        Long childTenant = tenants.get("child");
        if (parentTenant == null) {
            throw new IllegalStateException("parent run not found: " + parentRunId);
        }
        if (childTenant == null) {
            throw new IllegalStateException("child run not found: " + childRunId);
        }
        if (!Objects.equals(parentTenant, childTenant)) {
            // Cross-tenant join: consult ACL. Allowed → fall through and
            // proceed with the normal latch/DB-readback path. Denied →
            // throw CrossTenantAclDeniedException (still IllegalStateException
            // by inheritance for callers that catch the parent type).
            CrossTenantDecision decision = crossTenantAclService.evaluate(
                    parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);
            if (!decision.isAllowed()) {
                throw new CrossTenantAclDeniedException(parentTenant, childTenant, decision);
            }
            // ACL approved cross-tenant join — fall through into the regular
            // latch / DB-readback path below.
        }

        JoinKey key = new JoinKey(parentRunId, childRunId);
        // Slot registration MUST precede the DB readback. If the order were
        // reversed (DB read first, slot register second), a terminal-state
        // event delivered between the read and the register would be lost.
        // computeIfAbsent ensures only one slot exists per key, shared by all
        // concurrent waiters on the same pair.
        JoinSlot slot = slots.computeIfAbsent(key, k -> new JoinSlot());

        long started = System.currentTimeMillis();
        try {
            // DB readback: if the child is already terminal, populate the
            // slot from the DB and return immediately. We still go through
            // the latch so concurrent joiners see a consistent signal.
            ChildRunOutcome existingTerminal = readTerminalOutcome(childRunId);
            if (existingTerminal != null) {
                slot.outcomeLabel.compareAndSet(null, existingTerminal.terminalStatus());
                slot.latch.countDown();
                return existingTerminal;
            }

            boolean signaled = slot.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!signaled) {
                long waited = System.currentTimeMillis() - started;
                // One last DB readback before declaring timeout — covers the
                // narrow window where the row was flipped to terminal but the
                // SessionEndedEvent hasn't been dispatched yet (e.g. the
                // publisher thread is still inside its transaction).
                ChildRunOutcome lateTerminal = readTerminalOutcome(childRunId);
                if (lateTerminal != null) {
                    return lateTerminal;
                }
                throw new JoinTimeoutException(parentRunId, childRunId, waited);
            }

            ChildRunOutcome out = readTerminalOutcome(childRunId);
            if (out == null) {
                // Latch fired but DB shows non-terminal — only happens if the
                // event was published before the row UPDATE committed. In
                // practice the publish happens after the run-status flip, but
                // we don't trust that contract from a foreign caller. Treat
                // as a race and surface it as an exception so the caller can
                // retry rather than silently mislabel the outcome.
                throw new IllegalStateException(
                        "join signal received but child run " + childRunId
                                + " is not yet terminal in DB");
            }
            return out;
        } catch (InterruptedException ie) {
            // Preserve interrupt state — never swallow.
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "joinChildRun interrupted: parent=" + parentRunId
                            + " child=" + childRunId, ie);
        } finally {
            // Cleanup: remove only if WE own the slot. Two concurrent waiters
            // share one slot; the second `remove` is a no-op which is fine.
            slots.remove(key, slot);
        }
    }

    /**
     * Single-row tenant lookup used by {@link #onSessionEnded} to read the
     * parent's tenant in order to gate cross-tenant event delivery.
     * Returns {@code null} when the row does not exist or has a null tenant.
     */
    private Long readTenantId(String runPid) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT tenant_id FROM ab_agent_run WHERE pid = ?", runPid);
        if (rows.isEmpty()) {
            return null;
        }
        Object t = rows.get(0).get("tenant_id");
        return t == null ? null : ((Number) t).longValue();
    }

    /**
     * Read tenant_id for both rows in one round-trip. Returns map with keys
     * {@code "parent"} / {@code "child"}; missing rows map to null.
     */
    private Map<String, Long> readTenantsForBoth(String parentRunId, String childRunId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid, tenant_id FROM ab_agent_run WHERE pid IN (?, ?)",
                parentRunId, childRunId);
        java.util.HashMap<String, Long> result = new java.util.HashMap<>();
        result.put("parent", null);
        result.put("child", null);
        for (Map<String, Object> row : rows) {
            String pid = (String) row.get("pid");
            Long tenantId = row.get("tenant_id") == null
                    ? null : ((Number) row.get("tenant_id")).longValue();
            if (parentRunId.equals(pid)) {
                result.put("parent", tenantId);
            } else if (childRunId.equals(pid)) {
                result.put("child", tenantId);
            }
        }
        return result;
    }

    /**
     * Read terminal outcome from {@code ab_agent_run}; returns null if the
     * run is not yet terminal. Terminal statuses: {@code success},
     * {@code cancelled}, {@code failed}, {@code timeout} — same set the
     * SessionEndedEvent emitter uses (see {@code RunLifecycleService}).
     */
    private ChildRunOutcome readTerminalOutcome(String childRunId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT run_status, input_tokens, output_tokens, total_cost " +
                        "FROM ab_agent_run WHERE pid = ?",
                childRunId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        String runStatus = (String) row.get("run_status");
        if (runStatus == null) {
            return null;
        }
        String terminal = mapRunStatusToTerminalLabel(runStatus);
        if (terminal == null) {
            return null;
        }
        long inputTokens = row.get("input_tokens") == null
                ? 0L : ((Number) row.get("input_tokens")).longValue();
        long outputTokens = row.get("output_tokens") == null
                ? 0L : ((Number) row.get("output_tokens")).longValue();
        BigDecimal totalCost = row.get("total_cost") == null
                ? BigDecimal.ZERO : (BigDecimal) row.get("total_cost");
        return new ChildRunOutcome(childRunId, terminal, inputTokens, outputTokens, totalCost);
    }

    /**
     * Map {@code ab_agent_run.run_status} to the lowercase terminal label
     * used by {@link ChildRunCompletedEvent#getOutcome()}. Returns null for
     * non-terminal statuses (running / queued).
     */
    private static String mapRunStatusToTerminalLabel(String runStatus) {
        switch (runStatus) {
            case "success":
                return "succeeded";
            case "cancelled":
                return "cancelled";
            case "failed":
                return "failed";
            case "timeout":
                return "failed";
            default:
                return null;
        }
    }

    /**
     * Test-only probe used by integration tests to assert the slot map is
     * cleaned up after a join completes (red-line: no unbounded map growth).
     * Public because the test lives in a different package; the value is
     * inherently observational and exposing it carries no production risk.
     */
    public int activeSlotCount() {
        return slots.size();
    }

    private static final class JoinKey {
        private final String parentRunId;
        private final String childRunId;

        JoinKey(String parentRunId, String childRunId) {
            this.parentRunId = parentRunId;
            this.childRunId = childRunId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof JoinKey)) return false;
            JoinKey k = (JoinKey) o;
            return parentRunId.equals(k.parentRunId) && childRunId.equals(k.childRunId);
        }

        @Override
        public int hashCode() {
            return parentRunId.hashCode() * 31 + childRunId.hashCode();
        }
    }

    private static final class JoinSlot {
        final CountDownLatch latch = new CountDownLatch(1);
        /** First terminal label observed (event-supplied). */
        final AtomicReference<String> outcomeLabel = new AtomicReference<>();
    }
}
