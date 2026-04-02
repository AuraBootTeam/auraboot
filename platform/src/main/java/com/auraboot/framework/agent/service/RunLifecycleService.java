package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RunLifecycleService {

    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;
    private final AgentMemoryService memoryService;
    private final AgentObservationService observationService;
    private final LlmProviderFactory providerFactory;

    static final int DEFAULT_MAX_CONCURRENT_RUNS = 3;
    static final int HEARTBEAT_INTERVAL_SECONDS = 30;
    static final int STALE_RUN_THRESHOLD_MINUTES = 5;

    // Heartbeat: single-thread scheduler updates updated_at for active runs
    final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "agent-heartbeat");
                t.setDaemon(true);
                return t;
            });
    final ConcurrentHashMap<String, ScheduledFuture<?>> activeHeartbeats = new ConcurrentHashMap<>();

    // =========================================================================
    // Run record CRUD
    // =========================================================================

    void createRunRecord(Long tenantId, String runPid, String taskPid, String agentCode,
                         String model, LocalDateTime startedAt) {
        Map<String, Object> run = new HashMap<>();
        run.put("pid", runPid);
        run.put("tenant_id", tenantId);
        run.put("task_id", taskPid);
        run.put("agent_id", agentCode);
        run.put("run_status", "running");
        run.put("run_model", model);
        run.put("started_at", startedAt);
        run.put("input_tokens", 0);
        run.put("output_tokens", 0);
        run.put("total_cost", 0);
        run.put("created_at", startedAt);
        run.put("updated_at", startedAt);
        dynamicDataMapper.insert("ab_agent_run", run);

        Map<String, Object> taskUpdate = Map.of("task_status", "in_progress", "started_at", startedAt, "updated_at", LocalDateTime.now());
        dynamicDataMapper.update("ab_agent_task", taskUpdate, Map.of("pid", taskPid));
    }

    /**
     * Mark run as success/failed, update tokens/cost/duration on both run and task records.
     * Returns true if the run was successful (caller handles post-success dispatch).
     */
    boolean completeRunRecord(Long tenantId, String runPid, String taskPid, LocalDateTime startedAt,
                              AgentRunService.AgentLoopResult result, String model) {
        LocalDateTime completedAt = LocalDateTime.now();
        long durationMs = ChronoUnit.MILLIS.between(startedAt, completedAt);

        Map<String, Object> runUpdate = new HashMap<>();
        runUpdate.put("run_status", result.success ? "success" : "failed");
        runUpdate.put("run_model", model);
        runUpdate.put("completed_at", completedAt);
        runUpdate.put("duration_ms", durationMs);
        runUpdate.put("input_tokens", result.totalInputTokens);
        runUpdate.put("output_tokens", result.totalOutputTokens);
        runUpdate.put("total_cost", result.totalCost);
        runUpdate.put("updated_at", completedAt);
        dynamicDataMapper.update("ab_agent_run", runUpdate, Map.of("pid", runPid));

        Map<String, Object> taskUpdate = new HashMap<>();
        taskUpdate.put("task_status", result.success ? "done" : "blocked");
        taskUpdate.put("completed_at", completedAt);
        taskUpdate.put("actual_cost", result.totalCost);
        taskUpdate.put("updated_at", completedAt);
        if (result.lastResponse != null && !result.lastResponse.isBlank()) {
            taskUpdate.put("output_data", result.lastResponse);
        }
        dynamicDataMapper.update("ab_agent_task", taskUpdate, Map.of("pid", taskPid));

        return result.success;
    }

    // =========================================================================
    // Failure handling
    // =========================================================================

    void failRun(Long tenantId, String runPid, String taskPid, LocalDateTime startedAt, String error) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> runUpdate = new HashMap<>();
        runUpdate.put("run_status", "failed");
        runUpdate.put("completed_at", now);
        runUpdate.put("duration_ms", ChronoUnit.MILLIS.between(startedAt, now));
        runUpdate.put("error_message", error);
        runUpdate.put("updated_at", now);
        dynamicDataMapper.update("ab_agent_run", runUpdate, Map.of("pid", runPid));
        failTask(tenantId, taskPid, error);
    }

    void failTask(Long tenantId, String taskPid, String error) {
        Map<String, Object> taskUpdate = new HashMap<>();
        taskUpdate.put("task_status", "blocked");
        taskUpdate.put("updated_at", LocalDateTime.now());
        dynamicDataMapper.update("ab_agent_task", taskUpdate, Map.of("pid", taskPid));

        // Cancel pending child tasks when parent fails
        cancelChildTasks(tenantId, taskPid);
    }

    void cancelChildTasks(Long tenantId, String parentTaskPid) {
        String sql = "SELECT pid FROM ab_agent_task " +
                "WHERE tenant_id = #{params.tenantId} " +
                "AND parent_id = #{params.parentPid} " +
                "AND task_status IN ('todo', 'backlog') " +
                "AND deleted_flag = FALSE";
        List<Map<String, Object>> children = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "parentPid", parentTaskPid));

        if (children.isEmpty()) {
            return;
        }

        log.info("Cancelling {} child tasks for failed parent task {}", children.size(), parentTaskPid);

        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> child : children) {
            String childPid = (String) child.get("pid");
            Map<String, Object> cancelUpdate = Map.of("task_status", "cancelled", "updated_at", now);
            dynamicDataMapper.update("ab_agent_task", cancelUpdate, Map.of("pid", childPid));
        }
    }

    // =========================================================================
    // Mission progress
    // =========================================================================

    /**
     * Update mission KPI progress after a task completes.
     * Counts total vs done tasks for the mission; auto-transitions to COMPLETED when all done.
     */
    void updateMissionProgress(Long tenantId, String missionPid) {
        try {
            String countSql = "SELECT " +
                    "COUNT(*) AS total, " +
                    "COUNT(*) FILTER (WHERE task_status = 'done') AS done, " +
                    "COUNT(*) FILTER (WHERE task_status IN ('blocked','cancelled')) AS failed " +
                    "FROM ab_agent_task " +
                    "WHERE tenant_id = #{params.tenantId} AND mission_id = #{params.missionPid} " +
                    "AND deleted_flag = FALSE";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(countSql,
                    Map.of("tenantId", tenantId, "missionPid", missionPid));

            if (rows.isEmpty()) return;
            Map<String, Object> counts = rows.get(0);
            long total = ((Number) counts.get("total")).longValue();
            long done = ((Number) counts.get("done")).longValue();
            long failed = ((Number) counts.get("failed")).longValue();

            // Build KPI JSON
            String kpis = String.format("{\"totalTasks\":%d,\"completedTasks\":%d,\"failedTasks\":%d,\"progress\":%.1f}",
                    total, done, failed, total > 0 ? (done * 100.0 / total) : 0);

            Map<String, Object> missionUpdate = new HashMap<>();
            missionUpdate.put("kpis", kpis);
            missionUpdate.put("updated_at", LocalDateTime.now());

            // Auto-transition mission status when all tasks are terminal
            if (total > 0 && (done + failed) >= total) {
                missionUpdate.put("mission_status", failed > 0 ? "paused" : "completed");
            }

            dynamicDataMapper.update("ab_mission", missionUpdate, Map.of("pid", missionPid));
            log.debug("Mission progress updated: pid={}, done={}/{}", missionPid, done, total);

            // Publish SSE event for dashboard refresh
            observationService.publish(tenantId, "mission_progress", null, "mission", missionPid,
                    Map.of("total", total, "done", done, "failed", failed,
                           "progress", total > 0 ? (done * 100.0 / total) : 0));
        } catch (Exception e) {
            log.warn("Failed to update mission progress for {}: {}", missionPid, e.getMessage());
        }
    }

    // =========================================================================
    // Memory extraction
    // =========================================================================

    /**
     * After a successful run, extract structured memories using LLM.
     * Falls back to basic summary if LLM extraction fails.
     *
     * @param agentCode agent code (pre-resolved by caller)
     * @param taskTitle task title (pre-resolved by caller)
     * @param providerCode LLM provider code (pre-resolved by caller)
     * @param model LLM model name (pre-resolved by caller)
     */
    void saveRunMemory(Long tenantId, String runPid, String taskPid,
                       AgentRunService.AgentLoopResult result,
                       String agentCode, String taskTitle,
                       String providerCode, String model) {
        try {
            if (result.lastResponse == null || result.lastResponse.isBlank()) return;

            // Try LLM-powered extraction
            boolean extracted = extractMemoriesViaLlm(tenantId, runPid, agentCode, taskTitle, result,
                    providerCode, model);

            if (!extracted) {
                // Fallback: save basic summary
                String summary = result.lastResponse.length() > 500
                        ? result.lastResponse.substring(0, 500) + "..."
                        : result.lastResponse;
                saveMemoryEntry(tenantId, agentCode, "lesson", "Task completed: " + (taskTitle != null ? taskTitle : taskPid), summary, 3, runPid);
            }

            // Deduplicate after extraction
            memoryService.deduplicateMemories(tenantId, agentCode);

            observationService.publish(tenantId, "memory_saved", agentCode, "agent_memory", runPid,
                    Map.of("task", taskTitle != null ? taskTitle : taskPid));
        } catch (Exception e) {
            log.warn("Failed to save run memory for run {}: {}", runPid, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    boolean extractMemoriesViaLlm(Long tenantId, String runPid, String agentCode, String taskTitle,
                                   AgentRunService.AgentLoopResult result,
                                   String providerCode, String model) {
        try {
            // Use the response as extraction source
            String content = result.lastResponse;
            if (content.length() > 3000) content = content.substring(0, 3000) + "...";

            String extractionPrompt = "You are a memory extraction system. Analyze this agent execution result and extract useful memories.\n\n"
                    + "Task: " + taskTitle + "\nResult:\n" + content + "\n\n"
                    + "Extract memories as a JSON array. Each memory has:\n"
                    + "- \"type\": one of FACT, LESSON, PREFERENCE, DECISION\n"
                    + "- \"title\": short title (max 100 chars)\n"
                    + "- \"content\": the memory content (max 500 chars)\n"
                    + "- \"importance\": 1-10 (10 = critical, 1 = trivial)\n\n"
                    + "Rules:\n"
                    + "- FACT: verified data point discovered during execution\n"
                    + "- LESSON: something learned that improves future execution\n"
                    + "- PREFERENCE: user preference or pattern detected\n"
                    + "- DECISION: important decision made and its rationale\n"
                    + "- Only extract genuinely useful memories, not routine operations\n"
                    + "- Respond with JSON array only. If nothing worth remembering, respond with []";

            LlmProvider provider = providerFactory.getProvider(providerCode);
            LlmProviderFactory.ProviderConfig config = providerFactory.resolveConfig(tenantId, providerCode);
            if (config == null || config.getApiKey() == null || config.getApiKey().isBlank()) return false;

            LlmChatRequest req = LlmChatRequest.builder()
                    .model(model)
                    .messages(List.of(LlmChatRequest.Message.builder().role("user").content(extractionPrompt).build()))
                    .maxTokens(1000)
                    .build();

            LlmChatResponse resp = provider.chat(req, config.getApiKey(), config.getBaseUrl());
            String responseText = resp.getContent().stream()
                    .filter(b -> "text".equals(b.getType()))
                    .map(LlmChatResponse.ContentBlock::getText)
                    .collect(Collectors.joining());

            String jsonStr = extractJsonArray(responseText);
            if (jsonStr != null) {
                List<Map<String, Object>> memories = objectMapper.readValue(jsonStr, new TypeReference<>() {});
                int saved = 0;
                for (Map<String, Object> mem : memories) {
                    String type = (String) mem.get("type");
                    String title = (String) mem.get("title");
                    String memContent = (String) mem.get("content");
                    int importance = mem.containsKey("importance") ? ((Number) mem.get("importance")).intValue() : 5;
                    if (type == null || memContent == null) continue;
                    saveMemoryEntry(tenantId, agentCode, type, title, memContent, importance, runPid);
                    saved++;
                }
                log.info("Extracted {} memories from run {} for agent {}", saved, runPid, agentCode);
                return saved > 0;
            }
        } catch (Exception e) {
            log.warn("LLM memory extraction failed for run {}: {}", runPid, e.getMessage());
        }
        return false;
    }

    void saveMemoryEntry(Long tenantId, String agentCode, String type, String title, String content,
                         int importance, String sourceRunId) {
        Map<String, Object> data = new HashMap<>();
        data.put("pid", UniqueIdGenerator.generate());
        data.put("tenant_id", tenantId);
        data.put("memory_agent_id", agentCode);
        data.put("memory_type", type);
        data.put("memory_title", title != null && title.length() <= 100 ? title : (content != null && content.length() > 100 ? content.substring(0, 100) : content));
        data.put("memory_content", content != null && content.length() > 2000 ? content.substring(0, 2000) : content);
        data.put("importance", importance);
        data.put("source_run_id", sourceRunId);
        data.put("access_count", 0);
        data.put("created_at", LocalDateTime.now());
        data.put("updated_at", LocalDateTime.now());
        dynamicDataMapper.insert("ab_agent_memory", data);
    }

    // =========================================================================
    // Tool call log
    // =========================================================================

    void updateRunToolCalls(String runPid, List<Map<String, Object>> toolCallLog) {
        try {
            String json = objectMapper.writeValueAsString(toolCallLog);
            dynamicDataMapper.update("ab_agent_run", Map.of("tool_calls", json, "updated_at", LocalDateTime.now()),
                    Map.of("pid", runPid));
        } catch (Exception e) {
            log.error("Failed to update tool_calls: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Concurrency control & heartbeat
    // =========================================================================

    /**
     * Count runs that are actively executing for a given agent, excluding the current run.
     */
    int countActiveRuns(Long tenantId, String agentCode, String excludeRunPid) {
        String sql = "SELECT COUNT(*) AS cnt FROM ab_agent_run " +
                "WHERE tenant_id = #{params.tenantId} " +
                "AND agent_id = #{params.agentCode} " +
                "AND run_status = 'running' " +
                "AND pid != #{params.excludeRunPid}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "agentCode", agentCode, "excludeRunPid", excludeRunPid));
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get("cnt")).intValue();
    }

    /**
     * Start a periodic heartbeat that updates updated_at every 30 seconds for the given run.
     */
    void startHeartbeat(String runPid) {
        ScheduledFuture<?> future = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                dynamicDataMapper.update("ab_agent_run",
                        Map.of("updated_at", LocalDateTime.now()),
                        Map.of("pid", runPid));
            } catch (Exception e) {
                log.debug("Heartbeat update failed for run {}: {}", runPid, e.getMessage());
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        activeHeartbeats.put(runPid, future);
    }

    /**
     * Stop the heartbeat for a completed/failed run.
     */
    void stopHeartbeat(String runPid) {
        ScheduledFuture<?> future = activeHeartbeats.remove(runPid);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * Scheduled job: detect stale runs whose heartbeat has stopped (updated_at older than 5 minutes)
     * and mark them as FAILED.
     */
    @Scheduled(fixedRate = 300000) // every 5 minutes
    public void detectStaleRuns() {
        try {
            String sql = "SELECT pid, tenant_id, task_id, started_at FROM ab_agent_run " +
                    "WHERE run_status = 'running' " +
                    "AND updated_at < #{params.threshold}";
            LocalDateTime threshold = LocalDateTime.now().minusMinutes(STALE_RUN_THRESHOLD_MINUTES);
            List<Map<String, Object>> staleRuns = dynamicDataMapper.selectByQueryWithoutTenant(sql,
                    Map.of("threshold", threshold));

            for (Map<String, Object> run : staleRuns) {
                String pid = (String) run.get("pid");
                Long tenantId = ((Number) run.get("tenant_id")).longValue();
                String taskPid = (String) run.get("task_id");
                Object startedAtRaw = run.get("started_at");
                LocalDateTime startedAt = startedAtRaw instanceof LocalDateTime ldt ? ldt : LocalDateTime.now();

                log.warn("Stale run detected: pid={}, marking as FAILED", pid);
                stopHeartbeat(pid); // Clean up any orphaned heartbeat

                MetaContext.setSystemTenantContext(tenantId);
                try {
                    failRun(tenantId, pid, taskPid, startedAt,
                            "Run stalled — no heartbeat for " + STALE_RUN_THRESHOLD_MINUTES + " minutes");
                } finally {
                    MetaContext.clear();
                }
            }

            if (!staleRuns.isEmpty()) {
                log.info("Marked {} stale agent runs as FAILED", staleRuns.size());
            }
        } catch (Exception e) {
            log.error("Failed to detect stale runs: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private String extractJsonArray(String text) {
        if (text == null) return null;
        int codeStart = text.indexOf("```json");
        if (codeStart >= 0) {
            int jsonStart = text.indexOf('[', codeStart);
            int jsonEnd = text.lastIndexOf(']');
            if (jsonStart >= 0 && jsonEnd > jsonStart) return text.substring(jsonStart, jsonEnd + 1);
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return null;
    }
}
