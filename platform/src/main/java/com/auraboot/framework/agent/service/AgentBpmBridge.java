package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Bridges Agent Control Plane and BPM (SmartEngine) systems.
 *
 * Direction 1 — BPM → Agent:
 *   A BPMN ServiceTask can delegate work to an AI Agent.
 *   The BPM process pauses, Agent executes, and on completion
 *   the result is written back to process variables.
 *
 * Direction 2 — Agent → BPM:
 *   An Agent tool can start a BPM process (e.g., approval workflow).
 *   The agent monitors the process and reacts to its outcome.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBpmBridge {

    private final DynamicDataMapper dynamicDataMapper;
    private final AgentDispatchHandler dispatchHandler;
    private final AgentObservationService observationService;
    private final ObjectMapper objectMapper;

    // ==================== Direction 1: BPM → Agent ====================

    /**
     * Called from a BPM ServiceTask to delegate work to an Agent.
     * Creates an agent task, dispatches it, and returns the task PID
     * for the BPM process to track.
     *
     * @param tenantId tenant
     * @param processInstanceId BPM process instance ID
     * @param activityId BPM activity/node ID
     * @param agentCode target agent code
     * @param taskTitle task title
     * @param taskDescription what the agent should do
     * @param contextData process variables relevant to the task
     * @return agent task PID (store in process variable for polling)
     */
    public String delegateToAgent(Long tenantId, String processInstanceId, String activityId,
                                   String agentCode, String taskTitle, String taskDescription,
                                   Map<String, Object> contextData) {
        return delegateToAgent(tenantId, processInstanceId, activityId, agentCode,
                taskTitle, taskDescription, contextData, 0);
    }

    /**
     * Called from a BPM ServiceTask to delegate work to an Agent, with timeout support.
     * Variables are prefixed with {@code _bpm_} for clear provenance in the agent context.
     *
     * @param tenantId tenant
     * @param processInstanceId BPM process instance ID
     * @param activityId BPM activity/node ID
     * @param agentCode target agent code
     * @param taskTitle task title
     * @param taskDescription what the agent should do
     * @param contextData process variables relevant to the task (stored as _bpm_variables.*)
     * @param timeoutSeconds seconds before the task is considered timed out (0 = no timeout)
     * @return agent task PID (store in process variable for polling)
     */
    public String delegateToAgent(Long tenantId, String processInstanceId, String activityId,
                                   String agentCode, String taskTitle, String taskDescription,
                                   Map<String, Object> contextData, int timeoutSeconds) {
        String taskPid = UniqueIdGenerator.generate();
        LocalDateTime now = LocalDateTime.now();

        // Build input data with standardized _bpm_ prefixed variables
        Map<String, Object> agentInput = new LinkedHashMap<>();
        agentInput.put("_bpm_process_id", processInstanceId);
        agentInput.put("_bpm_activity_id", activityId);
        agentInput.put("_bpm_delegated", true);
        if (contextData != null) {
            contextData.forEach((k, v) -> agentInput.put("_bpm_variables." + k, v));
        }
        if (timeoutSeconds > 0) {
            agentInput.put("_bpm_timeout_seconds", timeoutSeconds);
        }

        Map<String, Object> task = new HashMap<>();
        task.put("pid", taskPid);
        task.put("tenant_id", tenantId);
        task.put("title", taskTitle);
        task.put("description", taskDescription);
        task.put("task_status", "todo");
        task.put("task_priority", "high");
        task.put("assignee_type", "AI");
        task.put("assignee_id", agentCode);
        try {
            task.put("input_data", objectMapper.writeValueAsString(agentInput));
        } catch (Exception e) {
            log.warn("Failed to serialize BPM context: {}", e.getMessage());
        }
        task.put("created_at", now);
        task.put("updated_at", now);
        dynamicDataMapper.insert("ab_agent_task", task);

        // Publish observation
        observationService.publish(tenantId, "bpm_agent_delegated", agentCode,
                "agent_task", taskPid,
                Map.of("processInstanceId", processInstanceId, "activityId", activityId));

        // Dispatch
        dispatchHandler.dispatch(tenantId, taskPid, agentCode);

        log.info("BPM->Agent delegation: process={}, activity={}, agent={}, task={}, timeout={}s",
                processInstanceId, activityId, agentCode, taskPid, timeoutSeconds);
        return taskPid;
    }

    /**
     * Poll the agent task status. Called by BPM process to check completion.
     * Returns the task output if completed, or current status if still running.
     * Output variables are exposed with {@code _agent_output.*} prefix.
     */
    public Map<String, Object> pollAgentTaskStatus(Long tenantId, String taskPid) {
        String sql = "SELECT pid, task_status, output_data, completed_at FROM ab_agent_task " +
                "WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid} " +
                "AND deleted_flag = FALSE";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "pid", taskPid));
        if (rows.isEmpty()) {
            return Map.of("found", false, "taskPid", taskPid);
        }

        Map<String, Object> task = rows.get(0);
        String status = (String) task.get("task_status");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("taskPid", taskPid);
        result.put("status", status);
        result.put("completed", "done".equals(status) || "completed".equals(status));
        result.put("failed", "failed".equals(status));

        // Also get the latest run info and build standardized _agent_* variable mapping
        String runSql = "SELECT pid, run_status, duration_ms, total_cost, error_message " +
                "FROM ab_agent_run WHERE tenant_id = #{params.tenantId} AND task_id = #{params.taskId} " +
                "ORDER BY created_at DESC LIMIT 1";
        List<Map<String, Object>> runs = dynamicDataMapper.selectByQuery(runSql,
                Map.of("tenantId", tenantId, "taskId", taskPid));
        if (!runs.isEmpty()) {
            Map<String, Object> run = runs.get(0);
            String runPid = (String) run.get("pid");
            result.put("runPid", runPid);
            result.put("runStatus", run.get("run_status"));
            result.put("durationMs", run.get("duration_ms"));
            if (run.get("error_message") != null) {
                result.put("errorMessage", run.get("error_message"));
            }

            // Standardized BPM variable mapping for process variables
            Map<String, Object> bpmVariables = new LinkedHashMap<>();
            bpmVariables.put("_agent_run_pid", runPid);
            bpmVariables.put("_agent_status", run.get("run_status"));

            if (task.get("output_data") != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> outputData = objectMapper.readValue(
                            (String) task.get("output_data"), Map.class);
                    result.put("output", outputData);
                    outputData.forEach((k, v) -> bpmVariables.put("_agent_output." + k, v));
                } catch (Exception e) {
                    result.put("output", task.get("output_data"));
                }
            }
            result.put("bpmVariables", bpmVariables);
        } else if (task.get("output_data") != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> outputData = objectMapper.readValue(
                        (String) task.get("output_data"), Map.class);
                result.put("output", outputData);
            } catch (Exception e) {
                result.put("output", task.get("output_data"));
            }
        }

        return result;
    }

    /**
     * Poll agent task status with exponential backoff, blocking until completion or deadline.
     * Backoff sequence: 5s → 10s → 30s → 60s.
     *
     * @param tenantId tenant
     * @param taskPid agent task PID to monitor
     * @param maxWaitSeconds maximum total wait time in seconds
     * @return final status map, or {@code {"status":"timeout","taskPid":...}} on deadline
     */
    public Map<String, Object> pollWithBackoff(Long tenantId, String taskPid, int maxWaitSeconds) {
        long deadline = System.currentTimeMillis() + maxWaitSeconds * 1000L;
        long[] backoffMs = {5000, 10000, 30000, 60000}; // 5s → 10s → 30s → 60s
        int attempt = 0;

        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> status = pollAgentTaskStatus(tenantId, taskPid);
            if (Boolean.TRUE.equals(status.get("completed")) || Boolean.TRUE.equals(status.get("failed"))) {
                return status;
            }
            long sleep = backoffMs[Math.min(attempt, backoffMs.length - 1)];
            // Clamp sleep to remaining time
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            sleep = Math.min(sleep, remaining);
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            attempt++;
        }

        log.warn("pollWithBackoff timed out after {}s for task={}", maxWaitSeconds, taskPid);
        return Map.of("status", "timeout", "taskPid", taskPid);
    }

    // ==================== Direction 2: Agent → BPM ====================

    /**
     * Start a BPM process from an Agent tool call.
     * Creates a lightweight bridge record and returns the process instance info.
     *
     * @param tenantId tenant
     * @param runPid current agent run PID
     * @param processDefinitionCode BPM process definition code
     * @param initiatorData data to pass as process variables
     * @return process start result with instance ID
     */
    public Map<String, Object> startBpmProcess(Long tenantId, String runPid,
                                                 String processDefinitionCode,
                                                 Map<String, Object> initiatorData) {
        return startBpmProcess(tenantId, runPid, processDefinitionCode, initiatorData, 0);
    }

    /**
     * Start a BPM process from an Agent tool call, with timeout support.
     *
     * @param tenantId tenant
     * @param runPid current agent run PID
     * @param processDefinitionCode BPM process definition code
     * @param initiatorData data to pass as process variables
     * @param timeoutSeconds seconds the agent will wait for the process (0 = no timeout)
     * @return process start result with instance ID
     */
    public Map<String, Object> startBpmProcess(Long tenantId, String runPid,
                                                 String processDefinitionCode,
                                                 Map<String, Object> initiatorData,
                                                 int timeoutSeconds) {
        // Load process definition
        String defSql = "SELECT pid, code, definition_json FROM ab_bpm_definition " +
                "WHERE tenant_id = #{params.tenantId} AND code = #{params.code} " +
                "AND status = 'published' AND deleted_flag = FALSE";
        List<Map<String, Object>> defs = dynamicDataMapper.selectByQuery(defSql,
                Map.of("tenantId", tenantId, "code", processDefinitionCode));
        if (defs.isEmpty()) {
            return Map.of("success", false, "error", "BPM process not found: " + processDefinitionCode);
        }

        // Enrich initiator data with agent context
        Map<String, Object> processVars = new LinkedHashMap<>();
        processVars.put("_agent_initiated", true);
        processVars.put("_agent_run_pid", runPid);
        if (timeoutSeconds > 0) {
            processVars.put("_agent_timeout_seconds", timeoutSeconds);
        }
        if (initiatorData != null) {
            processVars.putAll(initiatorData);
        }

        // Create process instance record
        String instancePid = UniqueIdGenerator.generate();
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> instance = new HashMap<>();
        instance.put("pid", instancePid);
        instance.put("tenant_id", tenantId);
        instance.put("definition_id", defs.get(0).get("pid"));
        instance.put("definition_code", processDefinitionCode);
        instance.put("instance_status", "running");
        try {
            instance.put("variables", objectMapper.writeValueAsString(processVars));
        } catch (Exception e) {
            log.warn("Failed to serialize process variables: {}", e.getMessage());
        }
        instance.put("created_at", now);
        instance.put("updated_at", now);

        try {
            dynamicDataMapper.insert("ab_bpm_instance", instance);
        } catch (Exception e) {
            log.warn("BPM instance table may not exist or insert failed: {}", e.getMessage());
            return Map.of("success", false, "error", "Failed to create process instance: " + e.getMessage());
        }

        // Publish observation
        observationService.publish(tenantId, "agent_bpm_started", null,
                "bpm_instance", instancePid,
                Map.of("processCode", processDefinitionCode, "agentRunPid", runPid));

        log.info("Agent->BPM: started process={}, instance={}, agentRun={}, timeout={}s",
                processDefinitionCode, instancePid, runPid, timeoutSeconds);

        return Map.of(
                "success", true,
                "processInstancePid", instancePid,
                "processCode", processDefinitionCode,
                "status", "running");
    }

    /**
     * Poll BPM process status from an Agent.
     */
    public Map<String, Object> pollBpmProcessStatus(Long tenantId, String instancePid) {
        String sql = "SELECT pid, definition_code, instance_status, variables, " +
                "created_at, updated_at FROM ab_bpm_instance " +
                "WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "pid", instancePid));
        if (rows.isEmpty()) {
            return Map.of("found", false, "instancePid", instancePid);
        }

        Map<String, Object> inst = rows.get(0);
        String status = (String) inst.get("instance_status");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("instancePid", instancePid);
        result.put("processCode", inst.get("definition_code"));
        result.put("status", status);
        result.put("completed", "completed".equals(status) || "approved".equals(status));
        result.put("rejected", "rejected".equals(status));
        return result;
    }
}
