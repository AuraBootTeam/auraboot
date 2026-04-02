package com.auraboot.framework.agent.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Enables cross-agent collaboration through sub-task delegation.
 * An agent can delegate a sub-task to another agent and wait for its completion.
 *
 * Protocol:
 * 1. Parent agent calls delegateTask() — creates a child task linked via parent_id
 * 2. Child agent executes via AgentDispatchHandler
 * 3. On completion, parent agent is notified via observation event
 * 4. Parent agent can poll or be notified of child task results
 *
 * Collaboration modes:
 * - DELEGATE: Send task to a single agent (original mode)
 * - BROADCAST: Send same task to N agents, collect all results, return best
 * - PIPELINE: Serial execution chain where each agent's output feeds the next
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCollaborationService {

    private final DynamicDataMapper dynamicDataMapper;
    private final AgentDispatchHandler dispatchHandler;
    private final AgentObservationService observationService;
    private final ObjectMapper objectMapper;

    /**
     * Delegate a sub-task to another agent.
     * Creates a child task linked to the parent task and dispatches it.
     *
     * @return the child task PID
     */
    public String delegateTask(Long tenantId, String parentTaskPid, String parentRunPid,
                                String targetAgentCode, String title, String description,
                                Map<String, Object> inputData) {
        // Validate target agent exists
        String agentSql = "SELECT agent_code FROM ab_agent_definition " +
                "WHERE tenant_id = #{params.tenantId} AND agent_code = #{params.agentCode} " +
                "AND status = 'active' AND deleted_flag = FALSE";
        List<Map<String, Object>> agentRows = dynamicDataMapper.selectByQuery(agentSql,
                Map.of("tenantId", tenantId, "agentCode", targetAgentCode));
        if (agentRows.isEmpty()) {
            throw new IllegalArgumentException("Target agent not found or inactive: " + targetAgentCode);
        }

        // Create child task
        String childPid = UniqueIdGenerator.generate();
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> childTask = new HashMap<>();
        childTask.put("pid", childPid);
        childTask.put("tenant_id", tenantId);
        childTask.put("parent_id", parentTaskPid);
        childTask.put("title", title);
        childTask.put("description", description);
        childTask.put("task_status", "todo");
        childTask.put("task_priority", "high");
        childTask.put("assignee_type", "AI");
        childTask.put("assignee_id", targetAgentCode);
        if (inputData != null) {
            try {
                childTask.put("input_data", objectMapper.writeValueAsString(inputData));
            } catch (Exception e) {
                log.warn("Failed to serialize input_data: {}", e.getMessage());
            }
        }
        childTask.put("created_at", now);
        childTask.put("updated_at", now);
        dynamicDataMapper.insert("ab_agent_task", childTask);

        // Publish delegation event
        observationService.publish(tenantId, "task_delegated", targetAgentCode,
                "agent_task", childPid,
                Map.of("parentTaskPid", parentTaskPid,
                        "parentRunPid", parentRunPid != null ? parentRunPid : "",
                        "targetAgent", targetAgentCode));

        // Dispatch to target agent
        dispatchHandler.dispatch(tenantId, childPid, targetAgentCode);

        log.info("Task delegated: parent={} → child={}, targetAgent={}",
                parentTaskPid, childPid, targetAgentCode);
        return childPid;
    }

    /**
     * Dispatch the same task to N agents in parallel; collect all results.
     * After all child tasks complete, call {@link #scoreBroadcastResults} to pick the best.
     *
     * Scoring rubric (applied in scoreBroadcastResults):
     *   success    +10  (status COMPLETED or DONE)
     *   has output  +5
     *   faster      +3  (proportional to 1/(durationSec+1))
     *   cheaper     +2  (proportional to 1/(cost+1))
     *
     * @param agentCodes      list of agent codes to broadcast to
     * @param taskDescription shared description sent to every agent
     * @param input           shared input payload
     * @param timeoutSeconds  advisory timeout (stored in result for callers)
     * @return dispatch summary with all child task PIDs
     */
    public Map<String, Object> broadcastTask(Long tenantId, String parentTaskPid,
            List<String> agentCodes, String taskDescription, Map<String, Object> input,
            int timeoutSeconds) {
        List<String> childPids = new ArrayList<>();
        for (String agentCode : agentCodes) {
            String childPid = delegateTask(tenantId, parentTaskPid, null,
                    agentCode, taskDescription, taskDescription, input);
            childPids.add(childPid);
        }

        log.info("Broadcast dispatched: parent={}, agents={}, childPids={}",
                parentTaskPid, agentCodes.size(), childPids);

        return Map.of(
            "mode", "broadcast",
            "childTaskPids", childPids,
            "totalAgents", agentCodes.size(),
            "timeoutSeconds", timeoutSeconds
        );
    }

    /**
     * Score broadcast results and return the best-performing agent result.
     * Should be called after all child tasks have completed.
     *
     * @return map with bestResult, bestScore, and allResults
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> scoreBroadcastResults(Long tenantId, String parentTaskPid) {
        Map<String, Object> aggregate = aggregateChildResults(tenantId, parentTaskPid);
        List<Map<String, Object>> childResults =
                (List<Map<String, Object>>) aggregate.get("children");

        if (childResults == null || childResults.isEmpty()) {
            return Map.of("status", "no_results");
        }

        Map<String, Object> best = null;
        double bestScore = -1;

        for (Map<String, Object> result : childResults) {
            double score = 0;

            String taskStatus = (String) result.get("taskStatus");
            String runStatus = (String) result.get("runStatus");
            if ("completed".equals(taskStatus) || "done".equals(taskStatus)
                    || "completed".equals(runStatus)) {
                score += 10;
            }

            if (result.get("output") != null) {
                score += 5;
            }

            if (result.get("durationMs") instanceof Number durationNum) {
                long duration = durationNum.longValue();
                if (duration > 0) {
                    score += 3.0 * (1.0 / (duration / 1000.0 + 1));
                }
            }

            if (result.get("cost") instanceof Number costNum) {
                double cost = costNum.doubleValue();
                if (cost > 0) {
                    score += 2.0 * (1.0 / (cost + 1));
                }
            }

            if (score > bestScore) {
                bestScore = score;
                best = result;
            }
        }

        log.info("Broadcast scoring complete: parent={}, candidates={}, bestScore={}",
                parentTaskPid, childResults.size(), bestScore);

        if (best == null) {
            return Map.of("status", "no_results");
        }

        return Map.of(
            "bestResult", best,
            "bestScore", bestScore,
            "allResults", childResults
        );
    }

    /**
     * Execute a serial pipeline: Agent A output → Agent B input → Agent C input.
     * Stops at the first failure and reports which step failed.
     *
     * Each step map must contain:
     *   agentCode       — target agent code
     *   taskDescription — description for that step
     *
     * @param steps        ordered list of pipeline steps
     * @param initialInput input for the first step
     * @return pipeline execution result with status, step PIDs, and final output
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> pipelineTask(Long tenantId, String parentTaskPid,
            List<Map<String, Object>> steps, Map<String, Object> initialInput) {
        Map<String, Object> currentInput = new HashMap<>(initialInput);
        List<String> stepPids = new ArrayList<>();

        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            String agentCode = (String) step.get("agentCode");
            String title = (String) step.getOrDefault("title", (String) step.get("taskDescription"));
            String description = (String) step.getOrDefault("description", title);

            String childPid = delegateTask(tenantId, parentTaskPid, null,
                    agentCode, title, description, currentInput);
            stepPids.add(childPid);

            log.info("Pipeline step {}/{}: dispatched to agent={}, taskPid={}",
                    i + 1, steps.size(), agentCode, childPid);

            // Poll for this step to complete before proceeding
            Map<String, Object> status = waitForTaskCompletion(tenantId, childPid, 300);

            String statusVal = (String) status.get("status");
            if (!"done".equals(statusVal) && !"completed".equals(statusVal)) {
                log.warn("Pipeline failed at step {} (agent={}): {}", i, agentCode, status.get("error"));
                Map<String, Object> result = new HashMap<>();
                result.put("mode", "pipeline");
                result.put("status", "failed");
                result.put("failedAtStep", i);
                result.put("stepPids", stepPids);
                result.put("error", status.getOrDefault("error", "Step " + i + " failed"));
                return result;
            }

            // Pass output to next step as input
            Object output = status.get("output");
            if (output instanceof Map) {
                currentInput = new HashMap<>((Map<String, Object>) output);
            }
        }

        log.info("Pipeline completed: parent={}, steps={}", parentTaskPid, steps.size());

        Map<String, Object> result = new HashMap<>();
        result.put("mode", "pipeline");
        result.put("status", "completed");
        result.put("stepPids", stepPids);
        result.put("output", currentInput);
        return result;
    }

    /**
     * Poll until the given task reaches a terminal state or the timeout elapses.
     *
     * @param timeoutSeconds maximum wait time
     * @return map containing at minimum "status"; may contain "output" or "error"
     */
    private Map<String, Object> waitForTaskCompletion(Long tenantId, String taskPid,
            int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        while (System.currentTimeMillis() < deadline) {
            // Query the task row directly for a lightweight status check
            String sql = "SELECT task_status, output_data FROM ab_agent_task " +
                    "WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid} " +
                    "AND deleted_flag = FALSE";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "pid", taskPid));

            if (!rows.isEmpty()) {
                String taskStatus = (String) rows.get(0).get("task_status");
                if ("done".equals(taskStatus) || "completed".equals(taskStatus)
                        || "failed".equals(taskStatus)) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", taskStatus);
                    result.put("output", rows.get(0).get("output_data"));
                    if ("failed".equals(taskStatus)) {
                        result.put("error", "Task " + taskPid + " reported FAILED status");
                    }
                    return result;
                }
            }

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.warn("waitForTaskCompletion timed out for task={} after {}s", taskPid, timeoutSeconds);
        return Map.of("status", "timeout",
                "error", "Task timed out after " + timeoutSeconds + "s");
    }

    /**
     * Scheduled job that marks stale child tasks as FAILED.
     * Runs every 30 seconds; targets tasks that have been PENDING or IN_PROGRESS
     * for more than 5 minutes without a terminal state.
     */
    @Scheduled(fixedRate = 30000)
    public void checkDelegationTimeouts() {
        try {
            // Scheduled threads don't pass through JwtAuthFilter — use tenant-bypassing query
            String selectSql = "SELECT pid, tenant_id FROM ab_agent_task " +
                    "WHERE task_status IN ('todo', 'in_progress') " +
                    "AND parent_id IS NOT NULL " +
                    "AND created_at < NOW() - INTERVAL '5 minutes' " +
                    "AND deleted_flag = FALSE";
            List<Map<String, Object>> timedOut = dynamicDataMapper.selectByQueryWithoutTenant(selectSql, Map.of());

            for (Map<String, Object> task : timedOut) {
                String pid = (String) task.get("pid");
                Long tenantId = ((Number) task.get("tenant_id")).longValue();
                log.warn("Delegation timeout detected for task={}", pid);

                // Set MetaContext per-tenant for the update (TenantLineInterceptor requires it)
                MetaContext.setContext(tenantId, 0L, null, null);
                try {
                    Map<String, Object> updateData = new HashMap<>();
                    updateData.put("task_status", "failed");
                    updateData.put("updated_at", LocalDateTime.now());
                    dynamicDataMapper.update("ab_agent_task", updateData, Map.of("pid", pid));
                } finally {
                    MetaContext.clear();
                }
            }

            if (!timedOut.isEmpty()) {
                log.info("Timeout sweep: marked {} stale delegation tasks as FAILED", timedOut.size());
            }
        } catch (Exception e) {
            log.error("Error in checkDelegationTimeouts: {}", e.getMessage());
        }
    }

    /**
     * Check the status of all child tasks for a given parent task.
     */
    public List<Map<String, Object>> getChildTaskStatuses(Long tenantId, String parentTaskPid) {
        String sql = "SELECT pid, title, task_status, assignee_id, output_data, " +
                "started_at, completed_at " +
                "FROM ab_agent_task WHERE tenant_id = #{params.tenantId} " +
                "AND parent_id = #{params.parentId} " +
                "AND deleted_flag = FALSE " +
                "ORDER BY created_at";
        return dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "parentId", parentTaskPid));
    }

    /**
     * Check if all child tasks of a parent are completed.
     * Returns a summary with completion status and results.
     */
    public Map<String, Object> checkDelegationComplete(Long tenantId, String parentTaskPid) {
        List<Map<String, Object>> children = getChildTaskStatuses(tenantId, parentTaskPid);

        int total = children.size();
        int completed = 0;
        int failed = 0;
        int running = 0;
        List<Map<String, Object>> results = new ArrayList<>();

        for (Map<String, Object> child : children) {
            String status = (String) child.get("task_status");
            switch (status != null ? status : "") {
                case "done", "completed" -> completed++;
                case "failed" -> failed++;
                case "in_progress", "todo" -> running++;
            }
            results.add(Map.of(
                    "taskPid", child.get("pid"),
                    "agent", child.get("assignee_id") != null ? child.get("assignee_id") : "",
                    "status", status != null ? status : "unknown",
                    "output", child.get("output_data") != null ? child.get("output_data") : ""));
        }

        boolean allDone = (completed + failed) == total && total > 0;

        return Map.of(
                "parentTaskPid", parentTaskPid,
                "totalChildren", total,
                "completed", completed,
                "failed", failed,
                "running", running,
                "allDone", allDone,
                "children", results);
    }

    /**
     * Aggregate results from all completed child tasks.
     * Used by the parent agent to collect outputs after delegation.
     */
    public Map<String, Object> aggregateChildResults(Long tenantId, String parentTaskPid) {
        String sql = "SELECT t.pid, t.title, t.assignee_id, t.output_data, t.task_status, " +
                "r.run_status, r.tool_calls, r.duration_ms, r.total_cost " +
                "FROM ab_agent_task t " +
                "LEFT JOIN ab_agent_run r ON r.task_id = t.pid AND r.run_status IN ('completed', 'failed') " +
                "WHERE t.tenant_id = #{params.tenantId} AND t.parent_id = #{params.parentId} " +
                "AND (t.deleted_flag = FALSE OR t.deleted_flag IS NULL) " +
                "ORDER BY t.created_at";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "parentId", parentTaskPid));

        List<Map<String, Object>> childResults = new ArrayList<>();
        long totalDurationMs = 0;
        double totalCost = 0.0;

        for (Map<String, Object> row : rows) {
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("taskPid", row.get("pid"));
            child.put("title", row.get("title"));
            child.put("agent", row.get("assignee_id"));
            child.put("taskStatus", row.get("task_status"));
            child.put("runStatus", row.get("run_status"));
            child.put("output", row.get("output_data"));

            if (row.get("duration_ms") instanceof Number n) {
                totalDurationMs += n.longValue();
                child.put("durationMs", n.longValue());
            }
            if (row.get("total_cost") instanceof Number n) {
                totalCost += n.doubleValue();
                child.put("cost", n.doubleValue());
            }
            childResults.add(child);
        }

        return Map.of(
                "parentTaskPid", parentTaskPid,
                "childCount", childResults.size(),
                "totalDurationMs", totalDurationMs,
                "totalCost", totalCost,
                "children", childResults);
    }
}
