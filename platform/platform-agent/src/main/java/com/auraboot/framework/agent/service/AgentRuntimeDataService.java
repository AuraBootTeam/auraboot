package com.auraboot.framework.agent.service;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence boundary for the Agent runtime HTTP API.
 *
 * <p>Controllers express request/response policy only; platform SQL and mapper
 * access stay behind this service-layer boundary.
 */
@Service
@RequiredArgsConstructor
public class AgentRuntimeDataService {

    private final DynamicDataMapper dynamicDataMapper;

    public List<Map<String, Object>> findRun(long tenantId, String runPid) {
        String sql = "SELECT * FROM ab_agent_run "
                + "WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid}";
        return dynamicDataMapper.selectByQuery(sql, Map.of("tenantId", tenantId, "pid", runPid));
    }

    public List<Map<String, Object>> findTask(long tenantId, String taskPid) {
        String sql = "SELECT * FROM ab_agent_task WHERE tenant_id = #{params.tenantId} "
                + "AND pid = #{params.taskPid} AND deleted_flag = FALSE";
        return dynamicDataMapper.selectByQuery(sql, Map.of("tenantId", tenantId, "taskPid", taskPid));
    }

    public void resetTaskForRetry(String taskPid, int retryCount) {
        Map<String, Object> taskUpdate = new HashMap<>();
        taskUpdate.put("task_status", "todo");
        taskUpdate.put("retry_count", retryCount);
        taskUpdate.put("updated_at", LocalDateTime.now());
        dynamicDataMapper.update("ab_agent_task", taskUpdate, Map.of("pid", taskPid));
    }

    public List<Map<String, Object>> listPendingApprovals(long tenantId) {
        String sql = "SELECT * FROM ab_agent_approval WHERE tenant_id = #{params.tenantId} "
                + "AND approval_status = 'pending' ORDER BY created_at DESC";
        return dynamicDataMapper.selectByQuery(sql, Map.of("tenantId", tenantId));
    }

    public List<Map<String, Object>> listActions(
            long tenantId,
            String runId,
            String targetModel,
            int days) {
        String sql;
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);

        if (runId != null && !runId.isBlank()) {
            sql = "SELECT * FROM ab_agent_action WHERE tenant_id = #{params.tenantId} "
                    + "AND run_id = #{params.runId} ORDER BY executed_at ASC";
            params.put("runId", runId);
        } else if (targetModel != null && !targetModel.isBlank()) {
            sql = "SELECT * FROM ab_agent_action WHERE tenant_id = #{params.tenantId} "
                    + "AND target_model = #{params.targetModel} AND executed_at >= NOW() - INTERVAL '"
                    + days + " days' ORDER BY executed_at DESC LIMIT 100";
            params.put("targetModel", targetModel);
        } else {
            sql = "SELECT * FROM ab_agent_action WHERE tenant_id = #{params.tenantId} "
                    + "AND executed_at >= NOW() - INTERVAL '" + days
                    + " days' ORDER BY executed_at DESC LIMIT 100";
        }

        return dynamicDataMapper.selectByQuery(sql, params);
    }

    public List<Map<String, Object>> findAction(long tenantId, String actionPid) {
        String sql = "SELECT * FROM ab_agent_action "
                + "WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid}";
        return dynamicDataMapper.selectByQuery(
                sql, Map.of("tenantId", tenantId, "pid", actionPid));
    }
}
