package com.auraboot.framework.agent.service;

import com.auraboot.framework.event.AuraEvent;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Published by {@link ParentJoinService} when a child run reaches a terminal
 * state (success / cancelled / failed). Carries the parent run id so a
 * listener watching for "my child finished" can correlate without polling
 * {@code ab_agent_run}.
 *
 * <p>Lineage: this event is derived from {@link com.auraboot.framework.agent.memory.SessionEndedEvent}
 * by joining {@code ab_agent_run.parent_run_id}. Root runs (no parent) do not
 * emit this event — the listener short-circuits on a null parent_run_id.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@code parentRunId} non-null — the {@code ab_agent_run.pid} of the parent
 *       that should be notified.</li>
 *   <li>{@code childRunId} non-null — the child run that reached terminal state.</li>
 *   <li>{@code outcome} non-null — one of {@code "succeeded"}, {@code "cancelled"},
 *       {@code "failed"} (lowercase to mirror the {@code SessionEndedEvent}
 *       outcome label convention).</li>
 *   <li>{@code tenantId} non-null — for tenant isolation on listener side.</li>
 * </ul>
 *
 * <p>Red-line: no fallback / placeholder values — Objects.requireNonNull on every
 * non-null field at construction.
 */
public class ChildRunCompletedEvent extends AuraEvent {

    @Getter
    private final String parentRunId;

    @Getter
    private final String childRunId;

    @Getter
    private final String outcome;

    public ChildRunCompletedEvent(Long tenantId, String parentRunId, String childRunId, String outcome) {
        super(Objects.requireNonNull(tenantId, "tenantId"),
                "agent_child_run_completed",
                "ab_agent_run",
                Objects.requireNonNull(childRunId, "childRunId"),
                buildPayload(parentRunId, childRunId, outcome));
        if (parentRunId == null || parentRunId.isBlank()) {
            throw new IllegalArgumentException("parentRunId must not be blank");
        }
        if (childRunId.isBlank()) {
            throw new IllegalArgumentException("childRunId must not be blank");
        }
        if (outcome == null || outcome.isBlank()) {
            throw new IllegalArgumentException("outcome must not be blank");
        }
        this.parentRunId = parentRunId;
        this.childRunId = childRunId;
        this.outcome = outcome;
    }

    private static Map<String, Object> buildPayload(String parentRunId, String childRunId, String outcome) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("parentRunId", parentRunId);
        payload.put("childRunId", childRunId);
        payload.put("outcome", outcome);
        return payload;
    }
}
