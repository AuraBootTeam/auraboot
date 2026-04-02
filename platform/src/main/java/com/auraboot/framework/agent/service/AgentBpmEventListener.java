package com.auraboot.framework.agent.service;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Listens for BPM process completion events and resumes waiting Agent tasks.
 *
 * <p>When a BPM process completes, this listener finds any agent tasks that were
 * delegated from that process (identified by {@code _bpm_process_id} stored in
 * {@code input_data}) and dispatches a resumed run so the agent can finalize its work.</p>
 *
 * <p>Uses Spring {@link EventListener} with an SpEL condition to match BPM completion
 * events without creating a hard compile-time dependency on BPM event classes — the
 * event payload is accessed via reflection.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentBpmEventListener {

    private final AgentDispatchHandler dispatchHandler;
    private final DynamicDataMapper dynamicDataMapper;

    /**
     * Handles BPM process completion events.
     *
     * <p>Uses {@code Object} parameter type and an SpEL condition to avoid a hard
     * classpath dependency on the BPM module's event class. The process instance ID
     * is extracted via reflection using common getter naming conventions.</p>
     *
     * @param event a {@code BpmProcessCompletedEvent} (matched by simple class name)
     */
    @EventListener(condition = "#event.class.simpleName == 'BpmProcessCompletedEvent'")
    public void onBpmProcessCompleted(Object event) {
        try {
            String processInstanceId = extractField(event, "processInstanceId");
            if (processInstanceId == null) {
                processInstanceId = extractField(event, "instancePid");
            }
            if (processInstanceId == null) {
                log.debug("BPM completion event has no processInstanceId/instancePid, skipping");
                return;
            }

            log.info("BPM process completed: {}, scanning for waiting agent tasks", processInstanceId);

            // Find agent tasks delegated from this BPM process instance.
            // input_data is a JSON text column containing _bpm_process_id.
            String sql = "SELECT t.pid AS task_pid, t.tenant_id, t.assignee_id AS agent_code, " +
                    "r.pid AS run_pid " +
                    "FROM ab_agent_task t " +
                    "LEFT JOIN ab_agent_run r ON r.task_id = t.pid AND r.run_status = 'waiting_bpm' " +
                    "WHERE t.task_status IN ('in_progress', 'todo') " +
                    "AND t.input_data::text LIKE #{params.processIdPattern} " +
                    "AND (t.deleted_flag = FALSE OR t.deleted_flag IS NULL)";

            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("processIdPattern", "%" + processInstanceId + "%"));

            if (rows.isEmpty()) {
                log.debug("No waiting agent tasks found for BPM process {}", processInstanceId);
                return;
            }

            for (Map<String, Object> row : rows) {
                String taskPid = (String) row.get("task_pid");
                String agentCode = (String) row.get("agent_code");
                Object tenantIdObj = row.get("tenant_id");
                String runPid = (String) row.get("run_pid");

                if (taskPid == null || agentCode == null || tenantIdObj == null) {
                    log.warn("Incomplete agent task row for BPM process {}: {}", processInstanceId, row);
                    continue;
                }

                Long tenantId = ((Number) tenantIdObj).longValue();
                log.info("Resuming agent task {} (agent={}) after BPM process {} completed",
                        taskPid, agentCode, processInstanceId);

                try {
                    if (runPid != null) {
                        dispatchHandler.dispatchWithResume(tenantId, taskPid, agentCode, runPid);
                    } else {
                        dispatchHandler.dispatch(tenantId, taskPid, agentCode);
                    }
                } catch (Exception e) {
                    log.error("Failed to resume agent task {} for BPM process {}: {}",
                            taskPid, processInstanceId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Unexpected error handling BPM process completion event: {}", e.getMessage(), e);
        }
    }

    /**
     * Extracts a field value from an event object via reflection using standard getter convention.
     * Returns {@code null} if the method does not exist or invocation fails.
     */
    private String extractField(Object event, String fieldName) {
        try {
            String getterName = "get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
            var method = event.getClass().getMethod(getterName);
            Object value = method.invoke(event);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
