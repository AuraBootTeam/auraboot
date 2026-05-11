package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.authorization.BlastRadius;
import com.auraboot.framework.agent.authorization.EffectClass;
import com.auraboot.framework.agent.authorization.RuntimeAuthorizationService;
import com.auraboot.framework.agent.observability.AgentRuntimeObservabilityService;
import com.auraboot.framework.agent.provider.ProviderExecutionResult;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.agent.trace.SpanContext;
import com.auraboot.framework.agent.trace.TraceContext;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.provider.SkillToolExecutor;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ACP Kernel: ToolExecutor service.
 *
 * Extracted from AgentRunService — handles all tool execution dispatch:
 * dsl_command, dsl_query, api_call, llm_native.
 *
 * Entry adapters must call {@link #executeToolCall(Long, String, String, String, String, Map, List, TraceContext)}
 * or {@link #confirmAuraBotSkill(Long, String, String, String, String, Map, List, String, TraceContext)}
 * so authorization, effects, actions, result contracts, and traces stay in one
 * runtime path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolLoopService {

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
    private final RuntimeAuthorizationService runtimeAuthorizationService;

    @Autowired(required = false)
    private SkillToolExecutor skillToolExecutor;

    @Autowired(required = false)
    private AgentRuntimeObservabilityService observabilityService;

    /**
     * Execute a tool call within an agent run.
     * This is the main entry point — dispatches to the appropriate executor based on tool type.
     *
     * <p>Transaction: {@code NOT_SUPPORTED}. The outer agent-loop / approval-gate
     * pipeline does not own a transaction — tool execution is composed of many
     * independent SQL operations (CommandExecutor, NamedQuery, ActionRecorder,
     * tool stats, hallucination counters) each of which manages its own boundary.
     * Wrapping the whole dispatch in {@code REQUIRES_NEW} previously caused
     * {@code UnexpectedRollbackException} when an inner write threw and the
     * tool-loop's top-level {@code catch (Exception)} swallowed the error to
     * surface a structured "Error: ..." string back to the LLM — the swallowed
     * RuntimeException had already marked the {@code REQUIRES_NEW} tx
     * rollback-only, so the surrounding commit attempt failed.
     *
     * <p>Per AGENTS.md red line #8 ("禁自愈 / Retry / Fallback / catch(Exception)"),
     * the canonical resolution is to drop the enclosing transaction rather than
     * swallow inside one — auxiliary operations stay {@code NOT_SUPPORTED} and
     * commit independently, while a real DB error from CommandExecutor surfaces
     * via that nested service's own transaction (which rolls back as expected).
     *
     * <p>Stateless contract: callable from concurrent threads. Verified by
     * code inspection — no static mutable state, no instance-level mutation;
     * all per-call state lives on the stack or in tenant-scoped DB rows.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String executeToolCall(Long tenantId, String runPid, String taskPid, String agentCode,
                                   String toolName, Map<String, Object> input,
                                   List<AgentToolDefinition> tools, TraceContext traceCtx) {
        StepContext.setRunPid(runPid);
        try {
            return executeToolCallInternal(tenantId, runPid, taskPid, agentCode, toolName, input, tools, traceCtx);
        } finally {
            StepContext.clearRunPid();
        }
    }

    private String executeToolCallInternal(Long tenantId, String runPid, String taskPid, String agentCode,
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

        RuntimeAuthorizationService.IncrementalAuthorization authorization =
                runtimeAuthorizationService.authorizeIncremental(buildToolCallIntent(
                        tenantId, runPid, toolName, toolDef, input));
        if (!authorization.granted()) {
            String reason = authorization.rejectedReason() == null
                    ? "denied_by_runtime_authorization"
                    : authorization.rejectedReason();
            log.info("Runtime authorization deny: tenant={} run={} tool={} reason={} by={}",
                    tenantId, runPid, toolName, reason, authorization.rejectedBy());
            return "Error: Runtime authorization denied for tool '" + toolName + "': " + reason;
        }
        if (authorization.requireApproval()) {
            String approvalPid = authorization.approvalRequestId() == null
                    ? ""
                    : authorization.approvalRequestId();
            return toJsonResult(Map.of(
                    "success", false,
                    "approvalRequired", true,
                    "approvalPid", approvalPid,
                    "message", "Runtime authorization requires human approval."));
        }

        // BIF-risk-aware approval: force approval when current-turn BIF says risk ≥ L3
        // AND this is a write tool (cmd_*), regardless of per-tool flag. Closes the
        // D1 Grounding → Approval Gate loop (spec §5.1 RiskEvaluator quality gate).
        boolean auraBotSkill = isAuraBotSkill(toolDef);
        boolean requiresApproval = toolDef.isRequiresApproval();
        if (!requiresApproval) {
            com.auraboot.framework.agent.dto.BusinessIntentFrame bif = BifContext.getCurrentBif();
            if (bif != null && isHighRisk(bif.getRiskLevel()) && isWriteTool(toolName, toolDef)) {
                requiresApproval = true;
                log.info("BIF risk={} escalates tool {} to approval-required (was false)",
                        bif.getRiskLevel(), toolName);
            }
        }
        if (requiresApproval && !auraBotSkill) {
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

        if (toolDef.isRequiresConfirmation() && !auraBotSkill) {
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
            } else if ("AURABOT_SKILL".equals(toolType)) {
                result = executeAuraBotSkill(toolDef, input);
            } else if (isProviderBackedTool(toolDef)) {
                result = executeProviderTool(toolDef, input, tenantId);
            } else if ("api_call".equals(toolType)) {
                result = executeApiCall(toolDef.getSourceCode(), input);
            } else if ("llm_native".equals(toolType)) {
                try { aiTraceService.endSpan(spanCtx, Map.of("status", "delegated"), "success"); }
                catch (Exception traceEx) { log.debug("Failed to end span: {}", traceEx.getMessage()); }
                recordToolExecution(toolType, true, "delegated");
                return objectMapper.writeValueAsString(Map.of(
                        "status", "delegated",
                        "message", "This tool is handled natively by the LLM provider."));
            } else {
                try { aiTraceService.endSpan(spanCtx, "Unsupported tool type: " + toolType, "error"); }
                catch (Exception traceEx) { log.debug("Failed to end span: {}", traceEx.getMessage()); }
                recordUnsupportedToolType(toolType);
                recordToolExecution(toolType, false, "unsupported_type");
                log.error("agent-runtime-unsupported-tool-type tenantId={} runId={} toolName={} toolType={} sourceCode={}",
                        tenantId, runPid, toolName, toolType, toolDef.getSourceCode());
                return toJsonResult(Map.of(
                        "success", false,
                        "errorCode", "unsupported_tool_type",
                        "error", "Unsupported tool type: " + toolType,
                        "toolName", toolName,
                        "toolType", toolType == null ? "unknown" : toolType));
            }

            long latencyMs = System.currentTimeMillis() - startMs;
            boolean success = isToolResultSuccess(result);
            updateToolStats(tenantId, toolName, success, latencyMs, success ? null : result);
            recordToolExecution(toolType, success, "executed");

            // Emit ResultContract for structured frontend rendering (PR-11). No-op if
            // ResponseSinkContext has no sink bound (non-chat callers: tests, ad-hoc skill runs).
            if ("dsl_query".equals(toolDef.getToolType())) {
                resultContractEmitter.emitQueryResult(toolName, toolDef, result, latencyMs, success);
            } else if ("dsl_command".equals(toolDef.getToolType())) {
                resultContractEmitter.emitCommandResult(toolName, toolDef, result, latencyMs, success,
                        success ? null : result);
            } else if (isProviderBackedTool(toolDef) || auraBotSkill) {
                resultContractEmitter.emitProviderResult(toolName, toolDef, result, latencyMs, success);
                Map<String, Object> parsedResult = parseToolResultMap(result);
                if (success || !auraBotSkill) {
                    actionRecorder.recordProviderAction(tenantId, runPid, toolRef(toolName, toolDef),
                            toolDef, input != null ? input : Map.of(), parsedResult,
                            success ? null : result, deriveEffects(toolName, toolDef));
                }
            }

            try { aiTraceService.endSpan(spanCtx, result, success ? "success" : "error"); }
            catch (Exception traceEx) { log.debug("Failed to end span for tool {}: {}", toolName, traceEx.getMessage()); }

            return result;
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startMs;
            updateToolStats(tenantId, toolName, false, latencyMs, e.getMessage());
            recordToolExecution(toolDef != null ? toolDef.getToolType() : null, false, "exception");
            log.error("Tool execution failed: tool={}, error={}", toolName, e.getMessage());

            if (toolDef != null) {
                String type = toolDef.getToolType();
                if ("dsl_query".equals(type)) {
                    resultContractEmitter.emitQueryResult(toolName, toolDef, "Error: " + e.getMessage(), latencyMs, false);
                } else if ("dsl_command".equals(type)) {
                    resultContractEmitter.emitCommandResult(toolName, toolDef, null, latencyMs, false, e.getMessage());
                } else if (isProviderBackedTool(toolDef) || isAuraBotSkill(toolDef)) {
                    resultContractEmitter.emitProviderResult(toolName, toolDef, null, latencyMs, false);
                    if (!isAuraBotSkill(toolDef)) {
                        actionRecorder.recordProviderAction(tenantId, runPid, toolRef(toolName, toolDef),
                                toolDef, input != null ? input : Map.of(), Map.of(),
                                e.getMessage(), deriveEffects(toolName, toolDef));
                    }
                }
            }

            try { aiTraceService.endSpan(spanCtx, e.getMessage(), "error"); }
            catch (Exception traceEx) { log.debug("Failed to end span for tool {}: {}", toolName, traceEx.getMessage()); }

            return "Error: " + e.getMessage();
        }
    }

    // ========== AuraBot Skill Tools ==========

    private String executeAuraBotSkill(AgentToolDefinition toolDef, Map<String, Object> input)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        if (skillToolExecutor == null) {
            return toJsonResult(Map.of(
                    "success", false,
                    "error", "AuraBot skill executor is not available in the current runtime."));
        }
        String skillName = resolveAuraBotSkillName(toolDef);
        SkillRequest request = SkillRequest.builder()
                .skillName(skillName)
                .params(objectMapper.valueToTree(input != null ? input : Map.of()))
                .build();
        SkillToolExecutor.DispatchOutcome outcome = skillToolExecutor.dispatch(skillName, request);

        Map<String, Object> response = new LinkedHashMap<>();
        if (outcome.kind() == SkillToolExecutor.OutcomeKind.EXECUTED) {
            SkillResult skillResult = outcome.result();
            response.put("success", true);
            response.put("data", skillResult == null ? null : skillResult.getPayload());
            if (skillResult != null && skillResult.getStatus() != null) {
                response.put("status", skillResult.getStatus().name());
            }
            return objectMapper.writeValueAsString(response);
        }

        SkillResult preview = outcome.preview();
        response.put("success", false);
        response.put("approvalRequired", true);
        response.put("skillName", skillName);
        response.put("riskLevel", outcome.riskLevel());
        response.put("preview", preview == null ? null
                : (preview.getPreview() != null ? preview.getPreview() : preview.getPayload()));
        response.put("previewToken", outcome.previewToken());
        response.put("error", "AuraBot skill requires confirmation before execution.");
        return objectMapper.writeValueAsString(response);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String confirmAuraBotSkill(Long tenantId, String runPid, String taskPid, String agentCode,
                                      String toolName, Map<String, Object> input,
                                      List<AgentToolDefinition> tools, String previewToken,
                                      TraceContext traceCtx) {
        StepContext.setRunPid(runPid);
        try {
            return confirmAuraBotSkillInternal(tenantId, runPid, taskPid, agentCode,
                    toolName, input, tools, previewToken, traceCtx);
        } finally {
            StepContext.clearRunPid();
        }
    }

    private String confirmAuraBotSkillInternal(Long tenantId, String runPid, String taskPid, String agentCode,
                                               String toolName, Map<String, Object> input,
                                               List<AgentToolDefinition> tools, String previewToken,
                                               TraceContext traceCtx) {
        AgentToolDefinition toolDef = findToolDef(tools, toolName);
        if (toolDef == null) {
            incrementMisuseCount(tenantId, toolName);
            return "Error: Unknown tool '" + toolName + "'. Available tools: " +
                    (tools == null ? "" : tools.stream()
                            .map(AgentToolDefinition::getName)
                            .limit(10)
                            .collect(Collectors.joining(", ")));
        }
        if (!"AURABOT_SKILL".equals(toolDef.getToolType())) {
            return "Error: Tool '" + toolName + "' is not an AuraBot skill.";
        }

        ToolAclChecker.Decision acl = toolAclChecker.check(
                tenantId, null, null, "interactive", toolName);
        if (!acl.isAllowed()) {
            String reason = acl.getReason() == null ? "denied_by_tool_acl" : acl.getReason();
            return "Error: Tool '" + toolName + "' is not permitted for this agent profile / channel (ACL: " + reason + ")";
        }

        RuntimeAuthorizationService.IncrementalAuthorization authorization =
                runtimeAuthorizationService.authorizeIncremental(buildToolCallIntent(
                        tenantId, runPid, toolName, toolDef, input));
        if (!authorization.granted()) {
            String reason = authorization.rejectedReason() == null
                    ? "denied_by_runtime_authorization"
                    : authorization.rejectedReason();
            log.info("Runtime authorization deny: tenant={} run={} tool={} reason={} by={}",
                    tenantId, runPid, toolName, reason, authorization.rejectedBy());
            return "Error: Runtime authorization denied for tool '" + toolName + "': " + reason;
        }
        if (authorization.requireApproval()) {
            String approvalPid = authorization.approvalRequestId() == null
                    ? ""
                    : authorization.approvalRequestId();
            return toJsonResult(Map.of(
                    "success", false,
                    "approvalRequired", true,
                    "approvalPid", approvalPid,
                    "message", "Runtime authorization requires human approval."));
        }
        if (skillToolExecutor == null) {
            return toJsonResult(Map.of(
                    "success", false,
                    "error", "AuraBot skill executor is not available in the current runtime."));
        }
        if (previewToken == null || previewToken.isBlank()) {
            return toJsonResult(Map.of(
                    "success", false,
                    "error", "Preview token is required for AuraBot skill confirmation."));
        }

        long startMs = System.currentTimeMillis();
        SpanContext spanCtx = null;
        try {
            spanCtx = aiTraceService.startSpan(traceCtx, null, "tool_confirm", toolName, input);
        } catch (Exception e) {
            log.debug("Failed to start confirm span for tool {}: {}", toolName, e.getMessage());
        }

        try {
            String skillName = resolveAuraBotSkillName(toolDef);
            SkillRequest request = SkillRequest.builder()
                    .skillName(skillName)
                    .params(objectMapper.valueToTree(input != null ? input : Map.of()))
                    .build();
            SkillToolExecutor.DispatchOutcome outcome =
                    skillToolExecutor.confirm(skillName, request, previewToken);

            Map<String, Object> response = new LinkedHashMap<>();
            if (outcome.kind() == SkillToolExecutor.OutcomeKind.EXECUTED) {
                SkillResult skillResult = outcome.result();
                response.put("success", true);
                response.put("data", skillResult == null ? null : skillResult.getPayload());
                if (skillResult != null && skillResult.getStatus() != null) {
                    response.put("status", skillResult.getStatus().name());
                }
                long latencyMs = System.currentTimeMillis() - startMs;
                updateToolStats(tenantId, toolName, true, latencyMs, null);
                recordToolExecution(toolDef.getToolType(), true, "confirm");
                String resultJson = objectMapper.writeValueAsString(response);
                resultContractEmitter.emitProviderResult(toolName, toolDef, resultJson, latencyMs, true);
                actionRecorder.recordProviderAction(tenantId, runPid, toolRef(toolName, toolDef),
                        toolDef, input != null ? input : Map.of(), response,
                        null, deriveEffects(toolName, toolDef));
                try { aiTraceService.endSpan(spanCtx, response, "success"); }
                catch (Exception traceEx) { log.debug("Failed to end confirm span: {}", traceEx.getMessage()); }
                return resultJson;
            }

            response.put("success", false);
            response.put("error", "AuraBot skill confirmation did not execute.");
            response.put("riskLevel", outcome.riskLevel());
            long latencyMs = System.currentTimeMillis() - startMs;
            updateToolStats(tenantId, toolName, false, latencyMs, String.valueOf(response.get("error")));
            recordToolExecution(toolDef.getToolType(), false, "confirm");
            resultContractEmitter.emitProviderResult(
                    toolName, toolDef, objectMapper.writeValueAsString(response), latencyMs, false);
            try { aiTraceService.endSpan(spanCtx, response, "error"); }
            catch (Exception traceEx) { log.debug("Failed to end confirm span: {}", traceEx.getMessage()); }
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startMs;
            updateToolStats(tenantId, toolName, false, latencyMs, e.getMessage());
            recordToolExecution(toolDef.getToolType(), false, "confirm_exception");
            try { aiTraceService.endSpan(spanCtx, e.getMessage(), "error"); }
            catch (Exception traceEx) { log.debug("Failed to end confirm span: {}", traceEx.getMessage()); }
            return toJsonResult(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Skill confirm failed"));
        }
    }

    private String resolveAuraBotSkillName(AgentToolDefinition toolDef) {
        String sourceCode = toolDef.getSourceCode();
        if (sourceCode != null && !sourceCode.isBlank()) {
            String restored = restoreLlmSafeAuraBotSkillName(toolDef.getName(), sourceCode);
            if (restored != null) {
                return restored;
            }
            return sourceCode;
        }
        String name = toolDef.getName();
        if (name != null && name.startsWith("aurabot:")) {
            return name.substring("aurabot:".length());
        }
        String restored = restoreLlmSafeAuraBotSkillName(name, null);
        if (restored != null) {
            return restored;
        }
        return name;
    }

    private String restoreLlmSafeAuraBotSkillName(String toolName, String sourceCode) {
        if (sourceCode != null && sourceCode.contains(":")) {
            return sourceCode;
        }
        if (toolName == null || !toolName.startsWith("aurabot_")) {
            return null;
        }
        String safeSkillName = toolName.substring("aurabot_".length());
        int namespaceEnd = safeSkillName.indexOf('_');
        if (namespaceEnd <= 0 || namespaceEnd >= safeSkillName.length() - 1) {
            return sourceCode;
        }
        return safeSkillName.substring(0, namespaceEnd)
                + ":"
                + safeSkillName.substring(namespaceEnd + 1);
    }

    private AgentToolDefinition findToolDef(List<AgentToolDefinition> tools, String name) {
        if (tools == null) return null;
        for (AgentToolDefinition tool : tools) {
            if (tool.getName() != null && tool.getName().equals(name)) {
                return tool;
            }
        }
        return null;
    }

    private boolean isAuraBotSkill(AgentToolDefinition toolDef) {
        return toolDef != null && "AURABOT_SKILL".equals(toolDef.getToolType());
    }

    // ========== Provider-backed Tools ==========

    private boolean isProviderBackedTool(AgentToolDefinition toolDef) {
        String name = toolDef.getName();
        String sourceCode = toolDef.getSourceCode();
        if (hasProviderPrefix(name) || hasProviderPrefix(sourceCode)) {
            return true;
        }
        String toolType = toolDef.getToolType();
        return "platform".equals(toolType)
                || "custom".equals(toolType)
                || "mcp".equals(toolType)
                || "built_in".equals(toolType);
    }

    private boolean hasProviderPrefix(String toolCode) {
        return toolCode != null && (toolCode.startsWith("platform.")
                || toolCode.startsWith("custom:")
                || toolCode.startsWith("mcp:"));
    }

    private String executeProviderTool(AgentToolDefinition toolDef, Map<String, Object> input, Long tenantId)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        String toolCode = toolDef.getSourceCode() != null && !toolDef.getSourceCode().isBlank()
                ? toolDef.getSourceCode()
                : toolDef.getName();
        ProviderExecutionResult providerResult = toolProviderRegistry.execute(
                tenantId, toolCode, input != null ? input : Map.of());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", providerResult.isSuccess());
        if (providerResult.getData() != null) {
            response.putAll(providerResult.getData());
        }
        if (providerResult.getErrorMessage() != null) {
            response.put("error", providerResult.getErrorMessage());
        }
        response.put("durationMs", providerResult.getDurationMs());
        return objectMapper.writeValueAsString(response);
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

    private RuntimeAuthorizationService.ToolCallIntent buildToolCallIntent(
            Long tenantId, String runPid, String toolName,
            AgentToolDefinition toolDef, Map<String, Object> input) {
        com.auraboot.framework.agent.dto.BusinessIntentFrame bif = BifContext.getCurrentBif();
        String skillCode = null;
        if (toolDef != null && "AURABOT_SKILL".equals(toolDef.getToolType())) {
            skillCode = resolveAuraBotSkillName(toolDef);
        }
        if (skillCode == null && bif != null
                && bif.getCandidateSkills() != null && !bif.getCandidateSkills().isEmpty()) {
            skillCode = bif.getCandidateSkills().get(0);
        }
        return new RuntimeAuthorizationService.ToolCallIntent(
                tenantId != null ? tenantId : 0L,
                runPid,
                StepContext.getStepIndex(),
                StepContext.getParallelIndex(),
                toolRef(toolName, toolDef),
                skillCode,
                null,
                deriveEffects(toolName, toolDef),
                deriveBlastRadius(toolName, toolDef),
                hashArgs(input),
                input != null ? input : Map.of(),
                null);
    }

    private String toolRef(String toolName, AgentToolDefinition toolDef) {
        if (toolDef != null && toolDef.getSourceCode() != null && !toolDef.getSourceCode().isBlank()
                && !"AURABOT_SKILL".equals(toolDef.getToolType())) {
            return toolDef.getSourceCode();
        }
        return toolName;
    }

    private Set<EffectClass> deriveEffects(String toolName, AgentToolDefinition toolDef) {
        String type = toolDef != null ? toolDef.getToolType() : null;
        String source = toolDef != null && toolDef.getSourceCode() != null ? toolDef.getSourceCode() : toolName;
        String normalizedRisk = normalizeRiskLevel(toolDef != null ? toolDef.getRiskLevel() : null, "L1");

        if ("dsl_query".equals(type)) {
            return Set.of(EffectClass.READ_PLATFORM_DATA);
        }
        if ("llm_native".equals(type)) {
            return Set.of(EffectClass.READ_CONTEXT);
        }
        if ("AURABOT_SKILL".equals(type)) {
            String skillName = resolveAuraBotSkillName(toolDef);
            if ("echo".equals(skillName)) {
                return Set.of(EffectClass.READ_CONTEXT);
            }
            if (skillName != null && skillName.endsWith(":query")) {
                return Set.of(EffectClass.READ_PLATFORM_DATA);
            }
            if ("model:create".equals(skillName) || "field:add".equals(skillName)) {
                return Set.of(EffectClass.WRITE_PLATFORM_STATE);
            }
            return Set.of(isHighRisk(normalizedRisk)
                    ? EffectClass.WRITE_PLATFORM_STATE
                    : EffectClass.READ_CONTEXT);
        }
        if ("dsl_command".equals(type)) {
            return Set.of(EffectClass.WRITE_PLATFORM_STATE);
        }
        if ("platform".equals(type)) {
            if ("platform.list_models".equals(source)
                    || "platform.execute_sql".equals(source)
                    || "platform.model_suggest".equals(source)) {
                return Set.of(EffectClass.READ_PLATFORM_DATA);
            }
            return Set.of(EffectClass.WRITE_PLATFORM_STATE);
        }
        if ("custom".equals(type) || "mcp".equals(type) || "api_call".equals(type)
                || "built_in".equals(type) || hasProviderPrefix(source)) {
            if (isWriteTool(toolName, toolDef) || isHighRisk(normalizedRisk)) {
                return Set.of(EffectClass.EXTERNAL_NETWORK, EffectClass.WRITE_PLATFORM_STATE);
            }
            return Set.of(EffectClass.EXTERNAL_NETWORK);
        }
        return isWriteTool(toolName, toolDef)
                ? Set.of(EffectClass.WRITE_PLATFORM_STATE)
                : Set.of(EffectClass.READ_CONTEXT);
    }

    private BlastRadius deriveBlastRadius(String toolName, AgentToolDefinition toolDef) {
        Set<EffectClass> effects = deriveEffects(toolName, toolDef);
        String risk = normalizeRiskLevel(toolDef != null ? toolDef.getRiskLevel() : null, "L1");
        if (effects.contains(EffectClass.TERMINAL_EXEC)
                || effects.contains(EffectClass.SECRET_ACCESS)
                || effects.contains(EffectClass.FILE_WRITE)
                || "L4".equals(risk)) {
            return BlastRadius.IRREVERSIBLE;
        }
        if (effects.contains(EffectClass.WRITE_PLATFORM_STATE)
                || effects.contains(EffectClass.EXTERNAL_NETWORK)) {
            return BlastRadius.SHARED_STATE;
        }
        return BlastRadius.REVERSIBLE;
    }

    private String hashArgs(Map<String, Object> input) {
        try {
            String json = objectMapper.writeValueAsString(input != null ? input : Map.of());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
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
            case "LOW" -> "L0";
            case "MEDIUM" -> "L2";
            case "HIGH" -> "L3";
            case "CRITICAL" -> "L4";
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

    private void recordToolExecution(String toolType, boolean success, String stage) {
        if (observabilityService != null) {
            observabilityService.recordToolExecution(toolType, success, stage);
        }
    }

    private void recordUnsupportedToolType(String toolType) {
        if (observabilityService != null) {
            observabilityService.recordUnsupportedToolType(toolType);
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

    private Map<String, Object> parseToolResultMap(String result) {
        if (result == null || result.startsWith("Error")) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(result, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        } catch (Exception ignored) {
            // Non-JSON tool result has no structured action payload.
        }
        return Map.of();
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

    // ========== Package-local DSL helpers for legacy kernel tests only ==========

    Map<String, Object> executeDslCommand(Long tenantId, String runId, String commandCode, Map<String, Object> input) {
        try {
            String result = executeDslCommandWithAction(commandCode, input, tenantId, runId, null);
            return objectMapper.readValue(result, MAP_TYPE);
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    Map<String, Object> executeDslQuery(Long tenantId, String runId, String queryCode, Map<String, Object> input) {
        try {
            String result = executeDslQueryWithAction(queryCode, input, tenantId, runId, null);
            return objectMapper.readValue(result, MAP_TYPE);
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

}
