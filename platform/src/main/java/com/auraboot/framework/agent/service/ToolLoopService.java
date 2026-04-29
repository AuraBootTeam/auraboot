package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.port.ToolExecutionPort;
import com.auraboot.framework.agent.provider.ProviderExecutionResult;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.agent.trace.SpanContext;
import com.auraboot.framework.agent.trace.TraceContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.dto.NamedQueryTestRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ACP Kernel: ToolExecutor service.
 *
 * Extracted from AgentRunService — handles all tool execution dispatch:
 * dsl_command, dsl_query, api_call, llm_native.
 *
 * Implements ToolExecutionPort so AuraBotChatService (core module) can delegate
 * tool execution here without depending on any external distribution boundary.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolLoopService implements ToolExecutionPort {

    private static final int MAX_HALLUCINATION_COUNT = 3;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ActionRecorder actionRecorder;
    private final AgentApprovalGateService approvalGate;
    private final ToolAclChecker toolAclChecker;
    private final AiTraceService aiTraceService;
    private final DynamicDataMapper dynamicDataMapper;
    private final CommandExecutor commandExecutor;
    private final NamedQueryService namedQueryService;
    private final ObjectMapper objectMapper;
    private final ToolProviderRegistry toolProviderRegistry;
    private final ResultContractEmitter resultContractEmitter;

    /**
     * Execute a tool call within an agent run.
     * This is the main entry point — dispatches to the appropriate executor based on tool type.
     */
    public String executeToolCall(Long tenantId, String runPid, String taskPid, String agentCode,
                                   String toolName, Map<String, Object> input,
                                   List<AgentToolDefinition> tools, TraceContext traceCtx) {
        AgentToolDefinition toolDef = tools.stream()
                .filter(t -> t.getName().equals(toolName))
                .findFirst().orElse(null);

        if (toolDef == null) {
            incrementMisuseCount(tenantId, toolName);
            incrementHallucinationCount(runPid);
            int hallucinationCount = getHallucinationCount(runPid);
            if (hallucinationCount >= MAX_HALLUCINATION_COUNT) {
                log.error("Hallucination circuit breaker triggered: run={}, tool={}, count={}",
                        runPid, toolName, hallucinationCount);
                throw new RuntimeException("Circuit breaker: Agent hallucinated " + hallucinationCount
                        + " non-existent tools. Terminating run.");
            }
            return "Error: Unknown tool '" + toolName + "'. Available tools: " +
                    tools.stream().map(AgentToolDefinition::getName).limit(10).collect(Collectors.joining(", "));
        }

        // ACP §5.5 — Tool ACL 5-dim pre-check. Runs BEFORE approval gate
        // because ACL is about "may this tuple invoke this tool at all"; if
        // ACL denies, there's nothing for a human to approve. Fail-secure:
        // no rule match → deny. BIF's profile_id/channel flow in naturally;
        // run_kind defaults to 'interactive' for the tool-loop path.
        com.auraboot.framework.agent.dto.BusinessIntentFrame bifForAcl = BifContext.getCurrentBif();
        String profileId = bifForAcl != null ? bifForAcl.getProfileId() : null;
        String channel   = bifForAcl != null ? bifForAcl.getChannel()   : null;
        String runKind   = "interactive";
        ToolAclChecker.Decision acl = toolAclChecker.check(tenantId, profileId, channel, runKind, toolName);
        if (!acl.isAllowed()) {
            String reason = acl.getReason() == null ? "denied_by_tool_acl" : acl.getReason();
            log.info("Tool ACL deny: tenant={} profile={} channel={} run_kind={} tool={} → {}",
                    tenantId, profileId, channel, runKind, toolName, reason);
            return "Error: Tool '" + toolName + "' is not permitted for this agent profile / channel (ACL: " + reason + ")";
        }

        // BIF-risk-aware approval: force approval when current-turn BIF says risk ≥ L3
        // AND this is a write tool (cmd_*), regardless of per-tool flag. Closes the
        // D1 Grounding → Approval Gate loop (spec §5.1 RiskEvaluator quality gate).
        boolean requiresApproval = toolDef.isRequiresApproval();
        if (!requiresApproval) {
            com.auraboot.framework.agent.dto.BusinessIntentFrame bif = BifContext.getCurrentBif();
            if (bif != null && isHighRisk(bif.getRiskLevel()) && isWriteTool(toolName, toolDef)) {
                requiresApproval = true;
                log.info("BIF risk={} escalates tool {} to approval-required (was false)",
                        bif.getRiskLevel(), toolName);
            }
        }
        if (requiresApproval) {
            String approvalPid = approvalGate.checkAndRequestApproval(
                    tenantId, runPid, taskPid, toolName, toolDef.getDescription(), input, true);
            if (approvalPid != null) {
                return toJsonResult(Map.of(
                        "success", false,
                        "approvalRequired", true,
                        "approvalPid", approvalPid,
                        "message", "This tool requires human approval. Approval request " +
                                approvalPid + " has been created."));
            }
            return toJsonResult(Map.of(
                    "success", false,
                    "approvalRequired", true,
                    "error", "This tool requires human approval, but no matching approval policy could create a request. No data was changed."));
        }

        if (toolDef.isRequiresConfirmation()) {
            resultContractEmitter.emitConfirmationRequired(toolName, toolDef, input, 0);
            return "Error: This tool requires user confirmation before execution. No data was changed.";
        }

        // Start trace span
        SpanContext spanCtx = null;
        try {
            spanCtx = aiTraceService.startSpan(traceCtx, null, "tool", toolName, input);
        } catch (Exception e) {
            log.debug("Failed to start span for tool {}: {}", toolName, e.getMessage());
        }

        long startMs = System.currentTimeMillis();
        try {
            String toolType = toolDef.getToolType();
            String result;
            if ("dsl_command".equals(toolType)) {
                result = executeDslCommandWithAction(toolDef.getSourceCode(), input, tenantId, runPid, toolDef);
            } else if ("dsl_query".equals(toolType)) {
                result = executeDslQueryWithAction(toolDef.getSourceCode(), input, tenantId, runPid, toolDef);
            } else if ("api_call".equals(toolType)) {
                result = executeApiCall(toolDef.getSourceCode(), input);
            } else if ("llm_native".equals(toolType)) {
                try { aiTraceService.endSpan(spanCtx, Map.of("status", "delegated"), "success"); }
                catch (Exception traceEx) { log.debug("Failed to end span: {}", traceEx.getMessage()); }
                return objectMapper.writeValueAsString(Map.of(
                        "status", "delegated",
                        "message", "This tool is handled natively by the LLM provider."));
            } else {
                try { aiTraceService.endSpan(spanCtx, "Unsupported tool type: " + toolType, "error"); }
                catch (Exception traceEx) { log.debug("Failed to end span: {}", traceEx.getMessage()); }
                return "Error: Unsupported tool type: " + toolType;
            }

            long latencyMs = System.currentTimeMillis() - startMs;
            boolean success = isToolResultSuccess(result);
            updateToolStats(tenantId, toolName, success, latencyMs, success ? null : result);

            // Emit ResultContract for structured frontend rendering (PR-11). No-op if
            // ChatSseContext has no emitter (non-chat callers: tests, ad-hoc skill runs).
            if ("dsl_query".equals(toolDef.getToolType())) {
                resultContractEmitter.emitQueryResult(toolName, toolDef, result, latencyMs, success);
            } else if ("dsl_command".equals(toolDef.getToolType())) {
                resultContractEmitter.emitCommandResult(toolName, toolDef, result, latencyMs, success,
                        success ? null : result);
            }

            try { aiTraceService.endSpan(spanCtx, result, success ? "success" : "error"); }
            catch (Exception traceEx) { log.debug("Failed to end span for tool {}: {}", toolName, traceEx.getMessage()); }

            return result;
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startMs;
            updateToolStats(tenantId, toolName, false, latencyMs, e.getMessage());
            log.error("Tool execution failed: tool={}, error={}", toolName, e.getMessage());

            if (toolDef != null) {
                String type = toolDef.getToolType();
                if ("dsl_query".equals(type)) {
                    resultContractEmitter.emitQueryResult(toolName, toolDef, "Error: " + e.getMessage(), latencyMs, false);
                } else if ("dsl_command".equals(type)) {
                    resultContractEmitter.emitCommandResult(toolName, toolDef, null, latencyMs, false, e.getMessage());
                }
            }

            try { aiTraceService.endSpan(spanCtx, e.getMessage(), "error"); }
            catch (Exception traceEx) { log.debug("Failed to end span for tool {}: {}", toolName, traceEx.getMessage()); }

            return "Error: " + e.getMessage();
        }
    }

    // ========== DSL Command ==========

    String executeDslCommand(String commandCode, Map<String, Object> input) {
        return executeDslCommandWithAction(commandCode, input, null, null, null);
    }

    private String executeDslCommandWithAction(String commandCode, Map<String, Object> input,
                                                Long tenantId, String runPid,
                                                AgentToolDefinition toolDef) {
        Map<String, Object> beforeData = null;
        String error = null;
        CommandExecuteResult cmdResult = null;

        try {
            String recordPid = extractRecordPidFromInput(input);
            if (tenantId != null && recordPid != null) {
                String modelCode = resolveModelCodeForCommand(tenantId, commandCode);
                if (modelCode != null) {
                    beforeData = actionRecorder.readRecordByPid(modelCode, recordPid);
                }
            }

            CommandExecuteRequest request = new CommandExecuteRequest();
            request.setPayload(input);
            if (recordPid != null) {
                request.setTargetRecordId(recordPid);
            }
            cmdResult = commandExecutor.execute(commandCode, request);

            String jsonResult = objectMapper.writeValueAsString(Map.of(
                    "success", true,
                    "data", cmdResult.getData() != null ? cmdResult.getData() : Map.of(),
                    "message", "OK"));

            Map<String, Object> afterData = null;
            if (tenantId != null) {
                String modelCode = resolveModelCodeForCommand(tenantId, commandCode);
                if (modelCode != null) {
                    if (recordPid == null && cmdResult.getData() != null) {
                        Object newPid = cmdResult.getData().get("pid");
                        if (newPid == null) newPid = cmdResult.getData().get("id");
                        if (newPid != null) recordPid = newPid.toString();
                    }
                    if (recordPid != null) {
                        afterData = actionRecorder.readRecordByPid(modelCode, recordPid);
                    }
                }
                actionRecorder.recordAction(tenantId, runPid, commandCode,
                        toolDef, input, cmdResult, beforeData, afterData, null);
            }

            return jsonResult;

        } catch (Exception e) {
            error = e.getMessage();
            if (tenantId != null) {
                actionRecorder.recordAction(tenantId, runPid, commandCode,
                        toolDef, input, cmdResult, beforeData, null, error);
            }
            return "Error executing command " + commandCode + ": " + e.getMessage();
        }
    }

    // ========== DSL Query ==========

    String executeDslQuery(String queryCode, Map<String, Object> input) {
        return executeDslQueryWithAction(queryCode, input, null, null, null);
    }

    private String executeDslQueryWithAction(String queryCode, Map<String, Object> input,
                                              Long tenantId, String runPid,
                                              AgentToolDefinition toolDef) {
        try {
            NamedQueryTestRequest nqRequest = new NamedQueryTestRequest();
            nqRequest.setParameters(input);
            PaginationResult<Map<String, Object>> result = namedQueryService.executeQuery(queryCode, nqRequest);
            List<Map<String, Object>> records = result.getRecords() != null ? result.getRecords() : List.of();
            if (records.size() > 20) {
                records = records.subList(0, 20);
            }

            if (tenantId != null) {
                actionRecorder.recordReadAction(tenantId, runPid,
                        queryCode, toolDef, input, records.size(), null);
            }

            return objectMapper.writeValueAsString(Map.of(
                    "total", result.getTotal(),
                    "returned", records.size(),
                    "records", records));
        } catch (Exception e) {
            if (tenantId != null) {
                actionRecorder.recordReadAction(tenantId, runPid,
                        queryCode, toolDef, input, 0, e.getMessage());
            }
            return "Error executing query " + queryCode + ": " + e.getMessage();
        }
    }

    // ========== API Call ==========

    private String executeApiCall(String apiPath, Map<String, Object> input) {
        try {
            String method = "get";
            String path = apiPath;
            if (apiPath.contains(" ")) {
                String[] parts = apiPath.split(" ", 2);
                method = parts[0].toUpperCase();
                path = parts[1];
            }

            if ("get".equals(method)) {
                String datasourceId = input != null ? (String) input.get("datasourceId") : null;
                if (datasourceId != null) {
                    return executeDslQuery(datasourceId.replace("nq:", ""), input);
                }
                return objectMapper.writeValueAsString(Map.of("error", "GET API_CALL requires datasourceId parameter"));
            } else {
                String commandCode = extractCommandCode(path);
                if (commandCode != null) {
                    return executeDslCommand(commandCode, input);
                }
                return objectMapper.writeValueAsString(Map.of("error", "POST API_CALL requires a valid command path"));
            }
        } catch (Exception e) {
            return "Error executing API call " + apiPath + ": " + e.getMessage();
        }
    }

    // ========== Helpers ==========

    /** Risk levels L3/L4 (and R3/R4 aliases) trigger mandatory Approval Gate routing. */
    private boolean isHighRisk(String riskLevel) {
        String normalized = normalizeRiskLevel(riskLevel, "L0");
        return "L3".equals(normalized) || "L4".equals(normalized);
    }

    /**
     * A write tool either declares a non-query tool_type or follows the cmd_*
     * naming convention. dsl_query / read tools are never escalated.
     */
    private boolean isWriteTool(String toolName, com.auraboot.framework.agent.dto.AgentToolDefinition toolDef) {
        if (toolDef != null) {
            String type = toolDef.getToolType();
            if ("dsl_query".equals(type) || "llm_native".equals(type)) return false;
            if ("dsl_command".equals(type) || "api_call".equals(type)) return true;
        }
        return toolName != null && (toolName.startsWith("cmd_") || toolName.startsWith("cmd:"));
    }

    private String normalizeRiskLevel(String riskLevel, String fallback) {
        if (riskLevel == null || riskLevel.isBlank()) {
            return fallback;
        }
        String normalized = riskLevel.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("R") && normalized.length() == 2) {
            normalized = "L" + normalized.substring(1);
        }
        return switch (normalized) {
            case "L0", "L1", "L2", "L3", "L4" -> normalized;
            default -> fallback;
        };
    }

    private String resolveModelCodeForCommand(Long tenantId, String commandCode) {
        try {
            String sql = "SELECT model_code FROM ab_command_definition WHERE tenant_id = #{params.tenantId} AND code = #{params.code} LIMIT 1";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "code", commandCode));
            if (!rows.isEmpty()) return (String) rows.get(0).get("model_code");
        } catch (Exception e) {
            log.debug("Cannot resolve model for command {}: {}", commandCode, e.getMessage());
        }
        return null;
    }

    private String extractRecordPidFromInput(Map<String, Object> input) {
        if (input == null) return null;
        Object pid = input.get("recordPid");
        if (pid == null) pid = input.get("recordId");
        if (pid == null) pid = input.get("pid");
        if (pid == null) pid = input.get("id");
        return pid != null ? pid.toString() : null;
    }

    private String extractCommandCode(String path) {
        if (path.contains("/commands/execute/")) {
            return path.substring(path.lastIndexOf("/commands/execute/") + "/commands/execute/".length());
        }
        if (path.contains("/execute/")) {
            return path.substring(path.lastIndexOf("/execute/") + "/execute/".length());
        }
        return null;
    }

    // ========== Stats & Hallucination ==========

    private void updateToolStats(Long tenantId, String toolCode, boolean success, long latencyMs, String error) {
        try {
            String sql = "SELECT call_count, avg_latency_ms, success_count FROM ab_agent_tool " +
                    "WHERE tenant_id = #{params.tenantId} AND tool_code = #{params.toolCode} " +
                    "AND deleted_flag = FALSE";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "toolCode", toolCode));
            if (rows.isEmpty()) return;

            long currentCount = ((Number) rows.get(0).getOrDefault("call_count", 0)).longValue();
            double currentAvg = ((Number) rows.get(0).getOrDefault("avg_latency_ms", 0.0)).doubleValue();

            Map<String, Object> update = new HashMap<>();
            update.put("call_count", currentCount + 1);
            if (success) {
                update.put("success_count", ((Number) rows.get(0).getOrDefault("success_count", 0)).longValue() + 1);
            } else if (error != null) {
                update.put("last_error", error.length() > 500 ? error.substring(0, 500) : error);
            }
            update.put("avg_latency_ms", currentCount > 0
                    ? (currentAvg * currentCount + latencyMs) / (currentCount + 1) : latencyMs);
            update.put("updated_at", LocalDateTime.now());
            dynamicDataMapper.update("ab_agent_tool", update,
                    Map.of("tenant_id", tenantId, "tool_code", toolCode));
        } catch (Exception e) {
            log.debug("Failed to update tool stats for {}: {}", toolCode, e.getMessage());
        }
    }

    private String toJsonResult(Map<String, Object> result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return String.valueOf(result);
        }
    }

    private boolean isToolResultSuccess(String result) {
        if (result == null || result.startsWith("Error")) {
            return false;
        }
        try {
            Object parsed = objectMapper.readValue(result, Object.class);
            if (parsed instanceof Map<?, ?> map && map.containsKey("success")) {
                return Boolean.TRUE.equals(map.get("success"));
            }
        } catch (Exception ignored) {
            // Non-JSON tool output is considered successful unless it starts with Error.
        }
        return true;
    }

    private void incrementHallucinationCount(String runPid) {
        try {
            String sql = "SELECT COALESCE(hallucination_count, 0) AS cnt FROM ab_agent_run WHERE pid = #{params.runPid}";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, Map.of("runPid", runPid));
            int current = rows.isEmpty() ? 0 : ((Number) rows.get(0).get("cnt")).intValue();
            dynamicDataMapper.update("ab_agent_run",
                    Map.of("hallucination_count", current + 1, "updated_at", LocalDateTime.now()),
                    Map.of("pid", runPid));
        } catch (Exception ignored) {}
    }

    private int getHallucinationCount(String runPid) {
        try {
            String sql = "SELECT COALESCE(hallucination_count, 0) AS cnt FROM ab_agent_run WHERE pid = #{params.runPid}";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, Map.of("runPid", runPid));
            return rows.isEmpty() ? 0 : ((Number) rows.get(0).get("cnt")).intValue();
        } catch (Exception e) {
            return 0;
        }
    }

    private void incrementMisuseCount(Long tenantId, String toolCode) {
        try {
            String sql = "SELECT misuse_count FROM ab_agent_tool " +
                    "WHERE tenant_id = #{params.tenantId} AND tool_code = #{params.toolCode} " +
                    "AND deleted_flag = FALSE";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "toolCode", toolCode));
            if (rows.isEmpty()) return;
            int current = ((Number) rows.get(0).getOrDefault("misuse_count", 0)).intValue();
            dynamicDataMapper.update("ab_agent_tool",
                    Map.of("misuse_count", current + 1, "updated_at", LocalDateTime.now()),
                    Map.of("tenant_id", tenantId, "tool_code", toolCode));
        } catch (Exception ignored) {}
    }

    // ========== ToolExecutionPort implementation (for AuraBot cross-module delegation) ==========

    @Override
    public Map<String, Object> executeDslCommand(Long tenantId, String runId, String commandCode, Map<String, Object> input) {
        try {
            String result = executeDslCommandWithAction(commandCode, input, tenantId, runId, null);
            return objectMapper.readValue(result, MAP_TYPE);
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> executeDslQuery(Long tenantId, String runId, String queryCode, Map<String, Object> input) {
        try {
            String result = executeDslQueryWithAction(queryCode, input, tenantId, runId, null);
            return objectMapper.readValue(result, MAP_TYPE);
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> executeTool(Long tenantId, String runId, String toolCode, Map<String, Object> input) {
        try {
            ProviderExecutionResult result = toolProviderRegistry.execute(
                    tenantId, toolCode, input != null ? input : Map.of());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", result.isSuccess());
            if (result.getData() != null) {
                response.putAll(result.getData());
            }
            if (result.getErrorMessage() != null) {
                response.put("error", result.getErrorMessage());
            }
            return response;
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }
}
