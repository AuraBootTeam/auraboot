package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import com.auraboot.framework.agent.dto.CapabilityView;
import com.auraboot.framework.agent.service.AgentApprovalGateService;
import com.auraboot.framework.agent.service.AgentBpmBridge;
import com.auraboot.framework.agent.service.AgentCollaborationService;
import com.auraboot.framework.agent.service.AgentCostReportService;
import com.auraboot.framework.agent.service.AgentHeartbeatService;
import com.auraboot.framework.agent.service.AgentSelfImprovementService;
import com.auraboot.framework.agent.service.CapabilityEvalService;
import com.auraboot.framework.agent.service.AgentContractDeriver;
import com.auraboot.framework.agent.service.AgentDispatchHandler;
import com.auraboot.framework.agent.service.AgentHintEnhancer;
import com.auraboot.framework.agent.service.AgentRunService;
import com.auraboot.framework.agent.service.AgentScheduleService;
import com.auraboot.framework.agent.service.AgentSkillService;
import com.auraboot.framework.agent.service.SkillAutoGenerator;
import com.auraboot.framework.agent.service.CapabilityViewService;
import com.auraboot.framework.agent.service.PluginScaffoldService;
import com.auraboot.framework.agent.service.ToolDryRunService;
import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentRuntimeController {

    private final AgentProperties agentProperties;
    private final AgentApprovalGateService approvalGateService;
    private final AgentBpmBridge bpmBridge;
    private final AgentCollaborationService collaborationService;
    private final AgentContractDeriver contractDeriver;
    private final AgentDispatchHandler dispatchHandler;
    private final AgentHeartbeatService heartbeatService;
    private final AgentHintEnhancer hintEnhancer;
    private final AgentRunService runService;
    private final AgentScheduleService scheduleService;
    private final AgentSkillService skillService;
    private final CapabilityEvalService evalService;
    private final CapabilityViewService capabilityViewService;
    private final ToolDryRunService dryRunService;
    private final PluginScaffoldService scaffoldService;
    private final AgentSelfImprovementService selfImprovementService;
    private final AgentCostReportService costReportService;
    private final SkillAutoGenerator skillAutoGenerator;
    private final AgentChatPort agentChatPort;
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;

    @PostMapping("/dispatch")
    public ApiResponse<String> dispatch(@RequestBody Map<String, String> body) {
        if (!agentProperties.isEnabled()) {
            return ApiResponse.error("Agent runtime is disabled");
        }
        String taskPid = body.get("taskPid");
        String agentCode = body.get("agentCode");
        if (taskPid == null || agentCode == null) {
            return ApiResponse.error("taskPid and agentCode are required");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        dispatchHandler.dispatch(tenantId, taskPid, agentCode);
        return ApiResponse.success("Task dispatched to agent: " + agentCode);
    }

    // ==================== Heartbeat ====================

    /**
     * Run an immediate system health check for the current tenant.
     *
     * <p>Returns a report with four counters and an overall {@code healthy} flag:
     * <ul>
     *   <li>{@code timeout_approvals}         — PENDING approvals older than 24 h</li>
     *   <li>{@code stale_tasks}               — IN_PROGRESS tasks not updated in 1 h</li>
     *   <li>{@code recent_failures}           — FAILED runs in the last 1 h</li>
     *   <li>{@code memory_overloaded_agents}  — agents with &gt; 1000 memory entries</li>
     * </ul>
     */
    @PostMapping("/heartbeat")
    public ApiResponse<Map<String, Object>> runHeartbeat() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(heartbeatService.runHeartbeat(tenantId));
    }

    // ==================== Plugin Scaffold ====================

    /**
     * Generate plugin JSON structures from a model specification.
     * Agents use this to implement "one-sentence plugin" creation without file I/O.
     *
     * <p>Request body:
     * <pre>{
     *   "modelCode": "equipment_inspection",
     *   "namespace": "insp",
     *   "description": "Equipment inspection record",
     *   "fields": [
     *     {"code": "name", "dataType": "string"},
     *     {"code": "status", "dataType": "select"},
     *     {"code": "customer", "dataType": "reference", "referenceModel": "crm_customer"}
     *   ]
     * }</pre>
     *
     * <p>Response keys: model, fields, fieldBindings, commands
     */
    @PostMapping("/scaffold")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> scaffoldPlugin(@RequestBody Map<String, Object> body) {
        String modelCode = (String) body.get("modelCode");
        String namespace = (String) body.get("namespace");
        String description = (String) body.get("description");
        List<Map<String, Object>> fields = (List<Map<String, Object>>) body.get("fields");
        if (modelCode == null || modelCode.isBlank()) {
            return ApiResponse.error("modelCode is required");
        }
        if (namespace == null || namespace.isBlank()) {
            return ApiResponse.error("namespace is required");
        }
        if (fields == null) {
            fields = List.of();
        }
        return ApiResponse.success(scaffoldService.scaffold(modelCode, namespace, fields, description));
    }

    @PostMapping("/schedules/reload")
    public ApiResponse<String> reloadSchedules() {
        scheduleService.loadAndScheduleAll();
        return ApiResponse.success("Schedules reloaded");
    }

    /**
     * Seed the HEARTBEAT schedule template for the current tenant (idempotent).
     * The template is created with status=INACTIVE; activate it manually when ready.
     */
    @PostMapping("/schedules/seed-heartbeat")
    public ApiResponse<Map<String, String>> seedHeartbeatTemplate() {
        Long tenantId = MetaContext.getCurrentTenantId();
        String pid = scheduleService.seedHeartbeatTemplate(tenantId);
        return ApiResponse.success(Map.of(
                "pid", pid,
                "cron", AgentScheduleService.HEARTBEAT_CRON,
                "status", "inactive"
        ));
    }

    /** Derive agent contracts from CapabilityView for all active tools */
    @PostMapping("/tools/derive-contracts")
    public ApiResponse<Map<String, Object>> deriveContracts() {
        Long tenantId = MetaContext.getCurrentTenantId();
        int derived = contractDeriver.deriveContracts(tenantId);
        return ApiResponse.success(Map.of("derived", derived));
    }

    /** Dry-run a single tool call to validate inputs and check preconditions */
    @PostMapping("/tools/dry-run")
    public ApiResponse<Map<String, Object>> dryRunTool(@RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String toolCode = (String) body.get("toolCode");
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) body.get("input");
        if (toolCode == null) {
            return ApiResponse.error("toolCode is required");
        }
        return ApiResponse.success(dryRunService.dryRun(tenantId, toolCode, input));
    }

    /** Dry-run an entire execution plan (list of tool calls) */
    @PostMapping("/tools/dry-run-plan")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> dryRunPlan(@RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Map<String, Object>> steps = (List<Map<String, Object>>) body.get("steps");
        if (steps == null || steps.isEmpty()) {
            return ApiResponse.error("steps array is required");
        }
        return ApiResponse.success(dryRunService.dryRunPlan(tenantId, steps));
    }

    /** Manually trigger a schedule to run immediately ("Run Now") */
    @PostMapping("/schedule/{schedulePid}/trigger")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, String>> triggerSchedule(@PathVariable String schedulePid) {
        if (!agentProperties.isEnabled()) {
            return ApiResponse.error("Agent runtime is disabled");
        }
        Long tenantId = MetaContext.getCurrentTenantId();

        // Load the schedule
        String sql = "SELECT * FROM ab_agent_schedule WHERE tenant_id = #{params.tenantId} " +
                "AND pid = #{params.pid} AND deleted_flag = FALSE";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "pid", schedulePid));
        if (rows.isEmpty()) {
            return ApiResponse.error("Schedule not found: " + schedulePid);
        }

        Map<String, Object> schedule = rows.get(0);
        if (!"active".equals(schedule.get("schedule_status"))) {
            return ApiResponse.error("Schedule is not active, current status: " + schedule.get("schedule_status"));
        }

        // Parse task template
        String templateJson = (String) schedule.get("task_template");
        Map<String, Object> template;
        try {
            template = (templateJson != null && !templateJson.isBlank())
                    ? objectMapper.readValue(templateJson, Map.class)
                    : Map.of();
        } catch (Exception e) {
            return ApiResponse.error("Invalid task_template JSON: " + e.getMessage());
        }

        // Create a new task from the template
        String taskPid = UniqueIdGenerator.generate();
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> task = new HashMap<>();
        task.put("pid", taskPid);
        task.put("tenant_id", tenantId);
        task.put("title", template.getOrDefault("title", "Manual trigger: " + schedule.get("title")));
        task.put("description", template.getOrDefault("description", "Manually triggered from schedule " + schedulePid));
        task.put("task_status", "todo");
        task.put("task_priority", template.getOrDefault("task_priority", "medium"));
        task.put("assignee_type", "agent");
        task.put("assignee_id", template.getOrDefault("assignee_id", template.getOrDefault("agent_code", "")));
        task.put("mission_id", schedule.get("mission_id"));
        task.put("created_at", now);
        task.put("updated_at", now);
        dynamicDataMapper.insert("ab_agent_task", task);

        // Update schedule run stats
        Map<String, Object> scheduleUpdate = new HashMap<>();
        scheduleUpdate.put("last_run_at", now);
        scheduleUpdate.put("run_count", ((Number) schedule.getOrDefault("run_count", 0)).intValue() + 1);
        scheduleUpdate.put("updated_at", now);
        dynamicDataMapper.update("ab_agent_schedule", scheduleUpdate, Map.of("pid", schedulePid));

        // Dispatch the task
        String agentCode = (String) task.get("assignee_id");
        if (agentCode != null && !agentCode.isBlank()) {
            dispatchHandler.dispatch(tenantId, taskPid, agentCode);
        }

        return ApiResponse.success(Map.of("taskPid", taskPid, "schedulePid", schedulePid));
    }

    /** Retry a failed or timed-out run */
    @PostMapping("/run/{runPid}/retry")
    public ApiResponse<Map<String, String>> retryRun(@PathVariable String runPid) {
        if (!agentProperties.isEnabled()) {
            return ApiResponse.error("Agent runtime is disabled");
        }
        Long tenantId = MetaContext.getCurrentTenantId();

        // Load the original run
        String runSql = "SELECT * FROM ab_agent_run WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid}";
        List<Map<String, Object>> runRows = dynamicDataMapper.selectByQuery(runSql,
                Map.of("tenantId", tenantId, "pid", runPid));
        if (runRows.isEmpty()) {
            return ApiResponse.error("Run not found: " + runPid);
        }

        Map<String, Object> run = runRows.get(0);
        String runStatus = (String) run.get("run_status");
        if (!"failed".equals(runStatus) && !"timeout".equals(runStatus)) {
            return ApiResponse.error("Only FAILED or TIMEOUT runs can be retried, current status: " + runStatus);
        }

        String taskPid = (String) run.get("task_id");
        String agentCode = (String) run.get("agent_id");

        // Load the associated task
        String taskSql = "SELECT * FROM ab_agent_task WHERE tenant_id = #{params.tenantId} " +
                "AND pid = #{params.taskPid} AND deleted_flag = FALSE";
        List<Map<String, Object>> taskRows = dynamicDataMapper.selectByQuery(taskSql,
                Map.of("tenantId", tenantId, "taskPid", taskPid));
        if (taskRows.isEmpty()) {
            return ApiResponse.error("Associated task not found: " + taskPid);
        }

        Map<String, Object> task = taskRows.get(0);
        String taskStatus = (String) task.get("task_status");

        // Reset task to TODO if it's not in a terminal success/cancel state
        if (!"done".equals(taskStatus) && !"cancelled".equals(taskStatus)) {
            LocalDateTime now = LocalDateTime.now();
            int retryCount = task.get("retry_count") != null ? ((Number) task.get("retry_count")).intValue() : 0;
            int maxRetries = task.get("max_retries") != null ? ((Number) task.get("max_retries")).intValue() : 3;

            if (retryCount >= maxRetries) {
                return ApiResponse.error("Max retries exceeded (" + maxRetries + ") for task: " + taskPid);
            }

            Map<String, Object> taskUpdate = new HashMap<>();
            taskUpdate.put("task_status", "todo");
            taskUpdate.put("retry_count", retryCount + 1);
            taskUpdate.put("updated_at", now);
            dynamicDataMapper.update("ab_agent_task", taskUpdate, Map.of("pid", taskPid));
        }

        // Re-dispatch the task
        dispatchHandler.dispatch(tenantId, taskPid, agentCode);

        return ApiResponse.success(Map.of(
                "taskPid", taskPid,
                "originalRunPid", runPid,
                "agentCode", agentCode));
    }

    /** Resume a FAILED/TIMEOUT/PENDING run from where it left off (using saved execution plan) */
    @PostMapping("/run/{runPid}/resume")
    public ApiResponse<Map<String, String>> resumeRun(@PathVariable String runPid) {
        if (!agentProperties.isEnabled()) {
            return ApiResponse.error("Agent runtime is disabled");
        }
        Long tenantId = MetaContext.getCurrentTenantId();

        String runSql = "SELECT * FROM ab_agent_run WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid}";
        List<Map<String, Object>> runRows = dynamicDataMapper.selectByQuery(runSql,
                Map.of("tenantId", tenantId, "pid", runPid));
        if (runRows.isEmpty()) {
            return ApiResponse.error("Run not found: " + runPid);
        }

        Map<String, Object> run = runRows.get(0);
        String status = (String) run.get("run_status");
        if (!"failed".equals(status) && !"timeout".equals(status) && !"pending".equals(status)) {
            return ApiResponse.error("Can only resume FAILED/TIMEOUT/PENDING runs, current: " + status);
        }

        String taskPid = (String) run.get("task_id");
        String agentCode = (String) run.get("agent_id");

        dispatchHandler.dispatchWithResume(tenantId, taskPid, agentCode, runPid);

        return ApiResponse.success(Map.of(
                "taskPid", taskPid,
                "originalRunPid", runPid,
                "agentCode", agentCode));
    }

    /** Approve a pending approval request. Auto-resumes the paused agent run. */
    @PostMapping("/approval/{approvalPid}/approve")
    @RequirePermission(value = "acp_agent_approval", message = "Insufficient permission to approve agent actions")
    public ApiResponse<Map<String, Object>> approveApproval(@PathVariable String approvalPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        // Verify the caller is an authorized approver per policy-level rules (if any)
        if (!approvalGateService.isAuthorizedApprover(tenantId, approvalPid, userId)) {
            log.warn("Unauthorized approve attempt: userId={}, approvalPid={}", userId, approvalPid);
            return ApiResponse.error("Insufficient permission to approve this request");
        }

        Map<String, Object> result;
        try {
            result = approvalGateService.approve(tenantId, approvalPid, userId);
        } catch (IllegalStateException e) {
            log.warn("Double-execution blocked for approval {}: {}", approvalPid, e.getMessage());
            return ApiResponse.error("Approval already processed: " + approvalPid);
        }
        if (result == null) {
            return ApiResponse.error("Approval not found or not in PENDING state: " + approvalPid);
        }
        Map<String, Object> response = new LinkedHashMap<>(result);
        Map<String, Object> chatToolResult = agentChatPort.executeApprovedPendingTool(tenantId, approvalPid);
        if (Boolean.TRUE.equals(chatToolResult.get("handled"))) {
            response.put("toolExecutionResult", chatToolResult);
        }
        return ApiResponse.success(response);
    }

    /** Reject a pending approval request. Marks the associated agent run as FAILED. */
    @PostMapping("/approval/{approvalPid}/reject")
    @RequirePermission(value = "acp_agent_approval", message = "Insufficient permission to reject agent actions")
    public ApiResponse<Map<String, Object>> rejectApproval(@PathVariable String approvalPid,
                                                            @RequestBody(required = false) Map<String, String> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String reason = body != null ? body.get("reason") : null;

        // Verify the caller is an authorized approver per policy-level rules (if any)
        if (!approvalGateService.isAuthorizedApprover(tenantId, approvalPid, userId)) {
            log.warn("Unauthorized reject attempt: userId={}, approvalPid={}", userId, approvalPid);
            return ApiResponse.error("Insufficient permission to reject this request");
        }

        Map<String, Object> result = approvalGateService.reject(tenantId, approvalPid, userId, reason);
        if (result == null) {
            return ApiResponse.error("Approval not found or not in PENDING state: " + approvalPid);
        }
        return ApiResponse.success(result);
    }

    /** List pending approvals for the current tenant */
    @GetMapping("/approvals/pending")
    public ApiResponse<List<Map<String, Object>>> listPendingApprovals() {
        Long tenantId = MetaContext.getCurrentTenantId();
        String sql = "SELECT * FROM ab_agent_approval WHERE tenant_id = #{params.tenantId} " +
                "AND approval_status = 'pending' ORDER BY created_at DESC";
        List<Map<String, Object>> approvals = dynamicDataMapper.selectByQuery(sql, Map.of("tenantId", tenantId));
        return ApiResponse.success(approvals);
    }

    /** Batch-enhance agent_hint for commands with missing or generic hints using LLM */
    @PostMapping("/admin/enhance-hints")
    public ApiResponse<Map<String, Object>> enhanceHints(
            @RequestParam(defaultValue = "50") int batchSize) {
        Long tenantId = MetaContext.getCurrentTenantId();
        int enhanced = hintEnhancer.enhanceBatch(tenantId, batchSize);
        return ApiResponse.success(Map.of("enhanced", enhanced, "batchSize", batchSize));
    }

    // ==================== Capability View ====================

    /** Get a single capability view by code (command code or nq:query_code) */
    @GetMapping("/capabilities/{code}")
    public ApiResponse<CapabilityView> getCapability(@PathVariable String code) {
        Long tenantId = MetaContext.getCurrentTenantId();
        CapabilityView view = capabilityViewService.getCapability(tenantId, code);
        if (view == null) {
            return ApiResponse.error("Capability not found: " + code);
        }
        return ApiResponse.success(view);
    }

    /** List capabilities by model code, with optional type filter (COMMAND, QUERY, AUTOMATION, WORKFLOW) */
    @GetMapping("/capabilities")
    public ApiResponse<List<CapabilityView>> listCapabilities(
            @RequestParam(required = false) String modelCode,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (modelCode != null && !modelCode.isBlank()) {
            return ApiResponse.success(capabilityViewService.listByModel(tenantId, modelCode));
        }
        return ApiResponse.success(capabilityViewService.listAll(tenantId, limit, offset, type));
    }

    // ==================== Capability Evaluation ====================

    /** Auto-generate evaluation cases from published capabilities */
    @GetMapping("/eval/generate-cases")
    public ApiResponse<List<CapabilityEvalCase>> generateEvalCases(
            @RequestParam(required = false) String modelCode,
            @RequestParam(defaultValue = "20") int maxCases) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(evalService.generateEvalCases(tenantId, modelCode, maxCases));
    }

    /** Run evaluation: generate cases and test tool selection accuracy */
    @PostMapping("/eval/run")
    public ApiResponse<Map<String, Object>> runEval(
            @RequestParam(required = false) String modelCode,
            @RequestParam(defaultValue = "20") int maxCases) {
        Long tenantId = MetaContext.getCurrentTenantId();
        var cases = evalService.generateEvalCases(tenantId, modelCode, maxCases);
        return ApiResponse.success(evalService.evaluateToolSelection(tenantId, cases));
    }

    // ==================== Agent Collaboration ====================

    /** Delegate a sub-task to another agent */
    @PostMapping("/collaborate/delegate")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, String>> delegateTask(@RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String parentTaskPid = (String) body.get("parentTaskPid");
        String parentRunPid = (String) body.get("parentRunPid");
        String targetAgentCode = (String) body.get("targetAgentCode");
        String title = (String) body.get("title");
        String description = (String) body.get("description");
        Map<String, Object> inputData = (Map<String, Object>) body.get("inputData");

        if (parentTaskPid == null || targetAgentCode == null || title == null) {
            return ApiResponse.error("parentTaskPid, targetAgentCode, and title are required");
        }

        String childPid = collaborationService.delegateTask(
                tenantId, parentTaskPid, parentRunPid, targetAgentCode, title, description, inputData);
        return ApiResponse.success(Map.of("childTaskPid", childPid, "targetAgent", targetAgentCode));
    }

    /** Check delegation status for a parent task */
    @GetMapping("/collaborate/{parentTaskPid}/status")
    public ApiResponse<Map<String, Object>> checkDelegation(@PathVariable String parentTaskPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(collaborationService.checkDelegationComplete(tenantId, parentTaskPid));
    }

    /** Aggregate results from child tasks */
    @GetMapping("/collaborate/{parentTaskPid}/results")
    public ApiResponse<Map<String, Object>> aggregateResults(@PathVariable String parentTaskPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(collaborationService.aggregateChildResults(tenantId, parentTaskPid));
    }

    /** Broadcast the same task to multiple agents in parallel and collect all results */
    @PostMapping("/collaborate/broadcast")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> broadcastTask(@RequestBody Map<String, Object> request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String parentTaskPid = (String) request.get("parentTaskPid");
        List<String> agentCodes = (List<String>) request.get("agentCodes");
        String taskDescription = (String) request.get("taskDescription");
        Map<String, Object> input = (Map<String, Object>) request.getOrDefault("input", Map.of());
        int timeoutSeconds = ((Number) request.getOrDefault("timeoutSeconds", 300)).intValue();
        if (parentTaskPid == null || agentCodes == null || taskDescription == null) {
            return ApiResponse.error("parentTaskPid, agentCodes, and taskDescription are required");
        }
        Map<String, Object> result = collaborationService.broadcastTask(
                tenantId, parentTaskPid, agentCodes, taskDescription, input, timeoutSeconds);
        return ApiResponse.success(result);
    }

    /** Score broadcast results and return the best-performing agent result */
    @GetMapping("/collaborate/{parentTaskPid}/score")
    public ApiResponse<Map<String, Object>> scoreBroadcastResults(@PathVariable String parentTaskPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Object> result = collaborationService.scoreBroadcastResults(tenantId, parentTaskPid);
        return ApiResponse.success(result);
    }

    /** Execute a serial pipeline where each agent's output feeds the next agent's input */
    @PostMapping("/collaborate/pipeline")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> pipelineTask(@RequestBody Map<String, Object> request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String parentTaskPid = (String) request.get("parentTaskPid");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) request.get("steps");
        Map<String, Object> initialInput = (Map<String, Object>) request.getOrDefault("initialInput", Map.of());
        if (parentTaskPid == null || steps == null || steps.isEmpty()) {
            return ApiResponse.error("parentTaskPid and steps are required");
        }
        Map<String, Object> result = collaborationService.pipelineTask(
                tenantId, parentTaskPid, steps, initialInput);
        return ApiResponse.success(result);
    }

    // ==================== Skills ====================

    /** List active skills */
    @GetMapping("/skills")
    public ApiResponse<List<Map<String, Object>>> listSkills(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String level) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(skillService.listSkills(tenantId, category, level));
    }

    /** Resolve a skill into its tool definitions */
    @GetMapping("/skills/{skillCode}/tools")
    public ApiResponse<List<AgentToolDefinition>> resolveSkillTools(@PathVariable String skillCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(skillService.resolveSkillTools(tenantId, skillCode));
    }

    /** Execute a skill — returns an execution plan */
    @PostMapping("/skills/{skillCode}/execute")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> executeSkill(
            @PathVariable String skillCode,
            @RequestBody(required = false) Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Object> input = body != null ? (Map<String, Object>) body.get("input") : null;
        String agentCode = body != null ? (String) body.get("agentCode") : null;
        return ApiResponse.success(skillService.executeSkill(tenantId, skillCode, input, agentCode));
    }

    /** Sync auto-generated skills from DSL model definitions */
    @PostMapping("/skills/sync")
    public ApiResponse<Map<String, Object>> syncSkills() {
        Long tenantId = MetaContext.getCurrentTenantId();
        var result = skillAutoGenerator.syncSkills(tenantId);
        return ApiResponse.success(Map.of(
                "created", result.created(),
                "updated", result.updated(),
                "skipped", result.skipped()));
    }

    /** Get skill contract (four-contract fields) */
    @GetMapping("/skills/{skillCode}/contract")
    public ApiResponse<Map<String, Object>> getSkillContract(@PathVariable String skillCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Object> contract = skillService.loadSkillContract(tenantId, skillCode);
        if (contract == null) return ApiResponse.error("Skill not found: " + skillCode);
        return ApiResponse.success(contract);
    }

    // ==================== Agent-BPM Bridge ====================

    /** Delegate a BPM task to an Agent */
    @PostMapping("/bpm/delegate-to-agent")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> bpmDelegateToAgent(@RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String processInstanceId = (String) body.get("processInstanceId");
        String activityId = (String) body.get("activityId");
        String agentCode = (String) body.get("agentCode");
        String title = (String) body.get("title");
        String description = (String) body.get("description");
        Map<String, Object> contextData = (Map<String, Object>) body.get("contextData");

        if (agentCode == null || title == null) {
            return ApiResponse.error("agentCode and title are required");
        }

        String taskPid = bpmBridge.delegateToAgent(
                tenantId, processInstanceId, activityId, agentCode, title, description, contextData);
        return ApiResponse.success(Map.of("taskPid", taskPid, "agentCode", agentCode));
    }

    /** Poll agent task status (for BPM process to check) */
    @GetMapping("/bpm/agent-task/{taskPid}/status")
    public ApiResponse<Map<String, Object>> pollAgentTask(@PathVariable String taskPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(bpmBridge.pollAgentTaskStatus(tenantId, taskPid));
    }

    /** Start a BPM process from an Agent */
    @PostMapping("/bpm/start-process")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> agentStartBpm(@RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String runPid = (String) body.get("runPid");
        String processCode = (String) body.get("processCode");
        Map<String, Object> initiatorData = (Map<String, Object>) body.get("initiatorData");

        if (processCode == null) {
            return ApiResponse.error("processCode is required");
        }

        return ApiResponse.success(bpmBridge.startBpmProcess(tenantId, runPid, processCode, initiatorData));
    }

    /** Poll BPM process status from Agent */
    @GetMapping("/bpm/process/{instancePid}/status")
    public ApiResponse<Map<String, Object>> pollBpmProcess(@PathVariable String instancePid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(bpmBridge.pollBpmProcessStatus(tenantId, instancePid));
    }

    // ==================== Sandbox & Contract ====================

    /** Execute a tool in a sandbox transaction that is always rolled back — real validation, no DB changes */
    @PostMapping("/tools/sandbox-run")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> sandboxRun(@RequestBody Map<String, Object> request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String toolCode = (String) request.get("toolCode");
        Map<String, Object> input = (Map<String, Object>) request.getOrDefault("input", Map.of());
        if (toolCode == null) {
            return ApiResponse.error("toolCode is required");
        }
        Map<String, Object> result = dryRunService.sandboxRun(tenantId, toolCode, input);
        return ApiResponse.success(result);
    }

    /** Manually trigger capability sync to ab_capability table for the current tenant */
    @PostMapping("/capabilities/sync")
    public ApiResponse<Map<String, Object>> syncCapabilities() {
        Long tenantId = MetaContext.getCurrentTenantId();
        capabilityViewService.syncCapabilities(tenantId);
        return ApiResponse.success(Map.of("status", "sync_triggered", "tenantId", tenantId));
    }

    /** Derive agent contracts scoped to a specific model code */
    @PostMapping("/tools/derive-contracts/{modelCode}")
    public ApiResponse<Map<String, Object>> deriveContractsForModel(@PathVariable String modelCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        int count = contractDeriver.deriveForModel(tenantId, modelCode);
        return ApiResponse.success(Map.of("derived", count, "modelCode", modelCode));
    }

    // ==================== G1: Self-Improvement ====================

    /**
     * Extract LESSON memories from a FAILED run.
     *
     * <p>POST /api/agent/self-improve/{runPid}?agentCode=xxx
     * Returns {"lessonsExtracted": 0|1}.
     */
    @PostMapping("/self-improve/{runPid}")
    public ApiResponse<Map<String, Object>> extractLessons(
            @PathVariable String runPid,
            @RequestParam String agentCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        int extracted = selfImprovementService.extractLessonsFromFailedRun(tenantId, agentCode, runPid);
        return ApiResponse.success(Map.of("lessonsExtracted", extracted, "runPid", runPid, "agentCode", agentCode));
    }

    /**
     * Get success-rate and lesson-count stats for an agent.
     *
     * <p>GET /api/agent/stats/improvement?agentCode=xxx
     */
    @GetMapping("/stats/improvement")
    public ApiResponse<Map<String, Object>> getImprovementStats(@RequestParam String agentCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(selfImprovementService.getImprovementStats(tenantId, agentCode));
    }

    // ==================== G3: Cost Reporting ====================

    /**
     * Aggregate total cost and run counts per agent.
     *
     * <p>GET /api/agent/stats/cost-by-agent
     */
    @GetMapping("/stats/cost-by-agent")
    public ApiResponse<List<Map<String, Object>>> getCostByAgent() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(costReportService.getCostByAgent(tenantId));
    }

    /**
     * Aggregate daily cost for the last N days.
     *
     * <p>GET /api/agent/stats/cost-by-day?days=30
     */
    @GetMapping("/stats/cost-by-day")
    public ApiResponse<List<Map<String, Object>>> getCostByDay(
            @RequestParam(defaultValue = "30") int days) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(costReportService.getCostByDay(tenantId, days));
    }

    /**
     * High-level tenant cost summary (totals across all agents).
     *
     * <p>GET /api/agent/stats/cost-summary
     */
    @GetMapping("/stats/cost-summary")
    public ApiResponse<Map<String, Object>> getCostSummary() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(costReportService.getTenantCostSummary(tenantId));
    }

    // ==================== ActionEngine ====================

    /**
     * Query Actions by Run ID.
     * GET /api/agent/actions?runId={runId}
     */
    @GetMapping("/actions")
    public ApiResponse<List<Map<String, Object>>> getActions(
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String targetModel,
            @RequestParam(required = false, defaultValue = "7") int days) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String sql;
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);

        if (runId != null && !runId.isBlank()) {
            sql = "SELECT * FROM ab_agent_action WHERE tenant_id = #{params.tenantId} AND run_id = #{params.runId} ORDER BY executed_at ASC";
            params.put("runId", runId);
        } else if (targetModel != null && !targetModel.isBlank()) {
            sql = "SELECT * FROM ab_agent_action WHERE tenant_id = #{params.tenantId} AND target_model = #{params.targetModel} AND executed_at >= NOW() - INTERVAL '" + days + " days' ORDER BY executed_at DESC LIMIT 100";
            params.put("targetModel", targetModel);
        } else {
            sql = "SELECT * FROM ab_agent_action WHERE tenant_id = #{params.tenantId} AND executed_at >= NOW() - INTERVAL '" + days + " days' ORDER BY executed_at DESC LIMIT 100";
        }

        List<Map<String, Object>> actions = dynamicDataMapper.selectByQuery(sql, params);
        return ApiResponse.success(actions);
    }

    /**
     * Get a single Action by PID.
     * GET /api/agent/actions/{pid}
     */
    @GetMapping("/actions/{pid}")
    public ApiResponse<Map<String, Object>> getAction(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String sql = "SELECT * FROM ab_agent_action WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "pid", pid));
        if (rows.isEmpty()) {
            return ApiResponse.error("Action not found: " + pid);
        }
        return ApiResponse.success(rows.get(0));
    }
}
