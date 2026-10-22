package com.auraboot.framework.agent.memory;

import com.auraboot.framework.event.AuraEvent;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Fired when an agent run / chat session ends and the corresponding L1
 * ({@code category='session'}) memories should be evaluated for promotion to
 * L2 ({@code category='user'}).
 *
 * <p>Design: {@code docs/plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md §4.1 / §6}.
 *
 * <p>Fired on every terminal run state — success, cancellation, and failure.
 * The L1 evaluation pipeline does not care which terminal path was taken:
 * candidate memories are selected by {@code (tenant_id, source_run_id,
 * category='session')}, so the listener runs the same logic regardless of
 * outcome. The {@link TerminalOutcome} field is published only so the promoter
 * listener can emit an outcome-labeled
 * {@code auraboot_memory_tier_session_ended_total} counter — cancelled /
 * failed runs with zero candidates naturally become no-ops.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@code tenantId} non-null — required for tenant isolation.</li>
 *   <li>{@code runId} non-null — the {@code ab_agent_memory.source_run_id} to
 *       scope candidate selection; any promotion written by this event carries
 *       the same id in {@code promoted_from_run_id}.</li>
 *   <li>{@code agentCode} non-null — used for logging / metrics only; does
 *       not affect the SQL predicate (design §6 scopes by tenant + run).</li>
 *   <li>{@code userId} / {@code scopeKey} optional — when present, promoted
 *       rows are written with {@code scope='user', scope_key=userId}. When
 *       absent, only {@code scope='tenant'} L1 candidates can be promoted.</li>
 *   <li>{@code outcome} non-null — one of {@link TerminalOutcome#SUCCEEDED},
 *       {@link TerminalOutcome#CANCELLED}, {@link TerminalOutcome#FAILED}.</li>
 * </ul>
 *
 * <p>No fallback: all non-null fields are validated at construction. Callers
 * must not emit the event for runs that never reached a terminal state —
 * skip the publish instead of inventing placeholder values.
 */
public class SessionEndedEvent extends AuraEvent {

    /**
     * Terminal state of the run that emitted this event. Used for
     * outcome-labelled metrics on the listener side; the promotion SQL
     * pipeline itself does not branch on this value.
     */
    public enum TerminalOutcome {
        SUCCEEDED,
        CANCELLED,
        FAILED
    }

    @Getter
    private final String runId;

    @Getter
    private final String agentCode;

    @Getter
    private final String userId;

    @Getter
    private final TerminalOutcome outcome;

    public SessionEndedEvent(Long tenantId, String runId, String agentCode, String userId,
                             TerminalOutcome outcome) {
        super(Objects.requireNonNull(tenantId, "tenantId"),
                "agent_session_ended",
                "ab_agent_run",
                Objects.requireNonNull(runId, "runId"),
                buildPayload(runId, agentCode, userId, outcome));
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (agentCode == null || agentCode.isBlank()) {
            throw new IllegalArgumentException("agentCode must not be blank");
        }
        this.runId = runId;
        this.agentCode = agentCode;
        this.userId = (userId == null || userId.isBlank()) ? null : userId;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    private static Map<String, Object> buildPayload(String runId, String agentCode, String userId,
                                                    TerminalOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        Map<String, Object> payload = new HashMap<>();
        payload.put("runId", runId);
        payload.put("agentCode", agentCode);
        payload.put("userId", userId == null ? "" : userId);
        payload.put("outcome", outcome.name());
        return payload;
    }
}
