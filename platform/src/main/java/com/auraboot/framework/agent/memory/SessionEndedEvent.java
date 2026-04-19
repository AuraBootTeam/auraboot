package com.auraboot.framework.agent.memory;

import com.auraboot.framework.event.AuraEvent;
import lombok.Getter;

import java.util.Map;
import java.util.Objects;

/**
 * Fired when an agent run / chat session ends and the corresponding L1
 * ({@code category='session'}) memories should be evaluated for promotion to
 * L2 ({@code category='user'}).
 *
 * <p>Design: {@code docs/plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md §4.1 / §6}.
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
 * </ul>
 *
 * <p>No fallback: all non-null fields are validated at construction. Callers
 * must not emit the event for failed runs that wrote no memory — skip the
 * publish instead of inventing placeholder values.
 */
public class SessionEndedEvent extends AuraEvent {

    @Getter
    private final String runId;

    @Getter
    private final String agentCode;

    @Getter
    private final String userId;

    public SessionEndedEvent(Long tenantId, String runId, String agentCode, String userId) {
        super(Objects.requireNonNull(tenantId, "tenantId"),
                "agent_session_ended",
                "ab_agent_run",
                Objects.requireNonNull(runId, "runId"),
                Map.of(
                        "runId", runId,
                        "agentCode", Objects.requireNonNull(agentCode, "agentCode"),
                        "userId", userId == null ? "" : userId));
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (agentCode.isBlank()) {
            throw new IllegalArgumentException("agentCode must not be blank");
        }
        this.runId = runId;
        this.agentCode = agentCode;
        this.userId = (userId == null || userId.isBlank()) ? null : userId;
    }
}
