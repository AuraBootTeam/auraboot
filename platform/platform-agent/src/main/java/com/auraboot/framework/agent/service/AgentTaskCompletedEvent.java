package com.auraboot.framework.agent.service;

import com.auraboot.framework.event.AuraEvent;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Published when an {@code ab_agent_task} reaches a terminal state
 * ({@code done} / {@code blocked} / {@code failed} / {@code cancelled}).
 *
 * <p>This is the task-level completion signal promised by the agent
 * collaboration protocol (CHILD_TASK_COMPLETED): listeners watching a
 * delegation parent can react without polling {@code ab_agent_task}.
 * Complements the run-level {@link ChildRunCompletedEvent} — runs and tasks
 * have independent parent chains ({@code parent_run_id} vs {@code parent_id}).
 *
 * <p>Scope: Spring application events are <em>in-process only</em>. In
 * multi-instance deployments a task may complete on another node, so polling
 * remains the authority everywhere ({@link TaskJoinService} is a latency
 * optimization, never a correctness dependency).
 *
 * <p>Contract:
 * <ul>
 *   <li>{@code taskPid} non-null — the task that reached terminal state.</li>
 *   <li>{@code parentTaskPid} nullable — {@code ab_agent_task.parent_id};
 *       null for root tasks.</li>
 *   <li>{@code status} non-null — the terminal {@code task_status} value.</li>
 * </ul>
 */
public class AgentTaskCompletedEvent extends AuraEvent {

    @Getter
    private final String taskPid;

    @Getter
    private final String parentTaskPid;

    @Getter
    private final String status;

    public AgentTaskCompletedEvent(Long tenantId, String taskPid, String parentTaskPid, String status) {
        super(Objects.requireNonNull(tenantId, "tenantId"),
                "agent_task_completed",
                "ab_agent_task",
                Objects.requireNonNull(taskPid, "taskPid"),
                buildPayload(taskPid, parentTaskPid, status));
        if (taskPid.isBlank()) {
            throw new IllegalArgumentException("taskPid must not be blank");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        this.taskPid = taskPid;
        this.parentTaskPid = parentTaskPid;
        this.status = status;
    }

    private static Map<String, Object> buildPayload(String taskPid, String parentTaskPid, String status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskPid", taskPid);
        // AuraEvent copies the payload with Map.copyOf, which rejects null
        // values — omit the key for root tasks instead.
        if (parentTaskPid != null) {
            payload.put("parentTaskPid", parentTaskPid);
        }
        payload.put("status", status);
        return payload;
    }
}
