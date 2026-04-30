package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.dto.AgentPlanStep;
import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.agent.trace.TraceContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * ACP Kernel: Step Loop service.
 *
 * Extracted from AgentRunService — handles the LLM chat loop and plan step execution.
 * Pure method extraction, no logic changes.
 */
@Slf4j
@Service
public class StepLoopService {

    private final ToolLoopService toolLoopService;
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;
    private final LlmProviderFactory providerFactory;
    private final AiTraceService aiTraceService;
    private final AgentApprovalGateService approvalGate;
    private final AgentProperties agentProperties;
    private final Executor asyncTaskExecutor;

    static final int MAX_TOOL_LOOPS = 20;

    public StepLoopService(ToolLoopService toolLoopService,
                           DynamicDataMapper dynamicDataMapper,
                           ObjectMapper objectMapper,
                           LlmProviderFactory providerFactory,
                           AiTraceService aiTraceService,
                           AgentApprovalGateService approvalGate,
                           AgentProperties agentProperties,
                           @Qualifier("asyncTaskExecutor") Executor asyncTaskExecutor) {
        this.toolLoopService = toolLoopService;
        this.dynamicDataMapper = dynamicDataMapper;
        this.objectMapper = objectMapper;
        this.providerFactory = providerFactory;
        this.aiTraceService = aiTraceService;
        this.approvalGate = approvalGate;
        this.agentProperties = agentProperties;
        this.asyncTaskExecutor = asyncTaskExecutor;
    }

    // =========================================================================
    // LLM Chat Loop
    // =========================================================================

    @SuppressWarnings("unchecked")
    AgentRunService.AgentLoopResult executeAgentLoop(Long tenantId, String runPid, String taskPid,
                                                      String agentCode, String systemPrompt, String userMessage,
                                                      List<AgentToolDefinition> tools, Map<String, Object> agentDef,
                                                      LlmProvider provider, LlmProviderFactory.ProviderConfig config,
                                                      TraceContext traceCtx) throws Exception {
        return executeAgentLoop(tenantId, runPid, taskPid, agentCode, systemPrompt, userMessage,
                tools, agentDef, provider, config, traceCtx, MAX_TOOL_LOOPS);
    }

    /**
     * Overload with configurable maxLoops — used by SkillEngine orchestration mode
     * to respect the skill's max_steps instead of the global MAX_TOOL_LOOPS.
     */
    @SuppressWarnings("unchecked")
    AgentRunService.AgentLoopResult executeAgentLoop(Long tenantId, String runPid, String taskPid,
                                                      String agentCode, String systemPrompt, String userMessage,
                                                      List<AgentToolDefinition> tools, Map<String, Object> agentDef,
                                                      LlmProvider provider, LlmProviderFactory.ProviderConfig config,
                                                      TraceContext traceCtx, int maxLoops) throws Exception {
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        messages.add(LlmChatRequest.Message.builder().role("user").content(userMessage).build());

        double costLimit = agentProperties.getDefaultCostLimit();
        String guardrailsJson = (String) agentDef.get("guardrails");
        if (guardrailsJson != null && !guardrailsJson.isBlank()) {
            try {
                Map<String, Object> guardrails = objectMapper.readValue(guardrailsJson, Map.class);
                if (guardrails.containsKey("maxCostPerRun")) {
                    costLimit = ((Number) guardrails.get("maxCostPerRun")).doubleValue();
                }
            } catch (Exception ignored) {}
        }

        String model = resolveModel(agentDef, config.getProviderCode());
        int maxTokens = config.getMaxTokens() > 0 ? config.getMaxTokens() : 4096;

        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        // Anthropic prompt-cache accounting: cache writes (1.25x base) and
        // cache reads (0.10x base) feed the cache-aware estimateCost overload
        // so the cost guard reflects real billing on cache-capable providers.
        int totalCacheCreationTokens = 0;
        int totalCacheReadTokens = 0;
        double totalCost = 0;
        String lastTextResponse = "";
        List<Map<String, Object>> toolCallLog = new ArrayList<>();

        // Classic agent loop = single logical step (index 0).
        // Actions produced during this loop belong to execution_plan[0].
        StepContext.setStepIndex(0);
        try {
        for (int loop = 0; loop < maxLoops; loop++) {
            // Timeout check
            checkTimeout(runPid, LocalDateTime.now());

            List<LlmChatRequest.Tool> llmTools = tools.stream()
                    .map(t -> {
                        if ("llm_native".equals(t.getToolType()) && t.getNativeToolConfig() != null) {
                            Map<String, Object> nativeConfig = parseJsonSafe(t.getNativeToolConfig());
                            return LlmChatRequest.Tool.builder()
                                    .name(t.getName())
                                    .description(t.getDescription())
                                    .inputSchema(t.getInputSchema())
                                    .nativeToolConfig(nativeConfig)
                                    .build();
                        }
                        return LlmChatRequest.Tool.builder()
                                .name(t.getName())
                                .description(t.getDescription())
                                .inputSchema(t.getInputSchema())
                                .build();
                    }).toList();

            LlmChatRequest request = LlmChatRequest.builder()
                    .model(model)
                    .maxTokens(maxTokens)
                    .systemPrompt(systemPrompt)
                    .messages(messages)
                    .tools(llmTools.isEmpty() ? null : llmTools)
                    .build();

            LlmChatResponse response = provider.chat(request, config.getApiKey(), config.getBaseUrl());

            totalInputTokens += response.getInputTokens();
            totalOutputTokens += response.getOutputTokens();
            totalCacheCreationTokens += response.getCacheCreationInputTokens();
            totalCacheReadTokens += response.getCacheReadInputTokens();
            totalCost = provider.estimateCost(model, totalInputTokens, totalOutputTokens,
                    totalCacheCreationTokens, totalCacheReadTokens);

            if (totalCost > costLimit) {
                log.warn("Cost limit exceeded: run={}, cost={}, limit={}", runPid, totalCost, costLimit);
                break;
            }

            if ("end_turn".equals(response.getStopReason()) || "max_tokens".equals(response.getStopReason())) {
                for (LlmChatResponse.ContentBlock block : response.getContent()) {
                    if ("text".equals(block.getType())) {
                        lastTextResponse = block.getText();
                    }
                }
                break;
            }

            if ("tool_use".equals(response.getStopReason())) {
                List<Object> assistantContent = new ArrayList<>();
                List<Object> toolResults = new ArrayList<>();

                ToolBlockBatch batch = collectToolBlocks(response, assistantContent);
                lastTextResponse = batch.lastText != null ? batch.lastText : lastTextResponse;

                List<ToolResult> results = processToolUseBlocksParallel(
                        batch.toolBlocks, tools, tenantId, runPid, taskPid, agentCode, traceCtx);

                for (int i = 0; i < results.size(); i++) {
                    ToolResult tr = results.get(i);
                    toolResults.add(Map.of(
                            "type", "tool_result",
                            "tool_use_id", tr.toolUseId(),
                            "content", tr.result()));
                    toolCallLog.add(Map.of(
                            "tool", tr.name(),
                            "input", batch.toolBlocks.get(i).getInput(),
                            "output", tr.result(),
                            "loop", loop));
                }

                messages.add(LlmChatRequest.Message.builder().role("assistant").content(assistantContent).build());
                messages.add(LlmChatRequest.Message.builder().role("user").content(toolResults).build());
            }
        }
        } finally {
            StepContext.clear();
        }

        updateRunToolCalls(runPid, toolCallLog);

        AgentRunService.AgentLoopResult result = new AgentRunService.AgentLoopResult();
        result.success = true;
        result.totalInputTokens = totalInputTokens;
        result.totalOutputTokens = totalOutputTokens;
        result.totalCost = totalCost;
        result.lastResponse = lastTextResponse;
        return result;
    }

    // =========================================================================
    // Plan Step Execution
    // =========================================================================

    @SuppressWarnings("unchecked")
    AgentRunService.AgentLoopResult executePlanSteps(List<AgentPlanStep> plan, int startStep,
                                                      Long tenantId, String runPid, String taskPid, String agentCode,
                                                      String systemPrompt, String userMessage,
                                                      List<AgentToolDefinition> tools, Map<String, Object> agentDef,
                                                      LlmProvider provider, LlmProviderFactory.ProviderConfig config,
                                                      TraceContext traceCtx,
                                                      boolean skipApprovalForResumedStep) throws Exception {
        String model = resolveModel(agentDef, config.getProviderCode());
        int maxTokens = config.getMaxTokens() > 0 ? config.getMaxTokens() : 4096;

        double costLimit = agentProperties.getDefaultCostLimit();
        String guardrailsJson = (String) agentDef.get("guardrails");
        if (guardrailsJson != null && !guardrailsJson.isBlank()) {
            try {
                Map<String, Object> guardrails = objectMapper.readValue(guardrailsJson, Map.class);
                if (guardrails.containsKey("maxCostPerRun")) {
                    costLimit = ((Number) guardrails.get("maxCostPerRun")).doubleValue();
                }
            } catch (Exception ignored) {}
        }

        List<LlmChatRequest.Message> messages = new ArrayList<>();
        messages.add(LlmChatRequest.Message.builder().role("user").content(userMessage).build());

        double totalCost = 0;
        int totalInputTokens = 0, totalOutputTokens = 0;
        // Cache-aware token tallies for cost estimation (see executeAgentLoop).
        int totalCacheCreationTokens = 0, totalCacheReadTokens = 0;
        String lastTextResponse = "";
        List<Map<String, Object>> toolCallLog = new ArrayList<>();

        // If plan is a single "Execute directly" step, use the classic agent loop
        if (plan.size() == 1 && "Execute task directly".equals(plan.get(0).getDescription())) {
            plan.get(0).setStatus(AgentPlanStep.StepStatus.RUNNING);
            AgentRunService.AgentLoopResult result = executeAgentLoop(tenantId, runPid, taskPid, agentCode,
                    systemPrompt, userMessage, tools, agentDef, provider, config, traceCtx);
            plan.get(0).setStatus(result.success ? AgentPlanStep.StepStatus.COMPLETED : AgentPlanStep.StepStatus.FAILED);
            plan.get(0).setResult(result.lastResponse != null ? truncate(result.lastResponse, 200) : null);
            return result;
        }

        for (int i = startStep; i < plan.size(); i++) {
            // Bind the current step index so ActionRecorder stamps every Action
            // produced during this step with the matching execution_plan[i] index.
            StepContext.setStepIndex(i);
            AgentPlanStep step = plan.get(i);
            step.setStatus(AgentPlanStep.StepStatus.RUNNING);
            step.setStartedAt(LocalDateTime.now());
            if (step.getSkillCode() == null && step.getToolCode() != null) {
                step.setSkillCode(step.getToolCode());
            }
            if (step.getInput() == null && step.getToolInput() != null) {
                step.setInput(step.getToolInput());
            }
            long stepStart = System.currentTimeMillis();

            // Check approval gate for this step
            if (step.isRequiresApproval() && !(skipApprovalForResumedStep && i == startStep)) {
                String approvalPid = approvalGate.checkAndRequestApproval(
                        tenantId, runPid, taskPid, step.getToolCode() != null ? step.getToolCode() : "step_" + i,
                        step.getDescription(), Map.of("stepIndex", i), true);
                if (approvalPid != null) {
                    step.setStatus(AgentPlanStep.StepStatus.AWAITING_APPROVAL);
                    persistPlan(runPid, plan, i);
                    // Phase C.3d: pass approvalPid through the exception so the
                    // chokepoint can surface it as the resumption token on the
                    // confirm_required SSE event.
                    throw new AgentApprovalPendingException(approvalPid,
                            "Step " + i + " awaiting approval: " + step.getDescription());
                }
            }

            // Build step context showing progress
            String stepContext = buildStepContext(plan, i);
            String stepPrompt = stepContext + "\n\n## Current Step " + i + "\n" + step.getDescription();
            if (step.getToolCode() != null) {
                stepPrompt += "\nSuggested tool: " + step.getToolCode();
            }
            stepPrompt += "\n\nExecute this step now. Use tools as needed.";

            messages.add(LlmChatRequest.Message.builder().role("user").content(stepPrompt).build());

            try {
                // Inner tool-calling loop for this step (max 5 calls per step)
                for (int toolTurn = 0; toolTurn < 5; toolTurn++) {
                    List<LlmChatRequest.Tool> llmTools = tools.stream()
                            .map(t -> {
                                if ("llm_native".equals(t.getToolType()) && t.getNativeToolConfig() != null) {
                                    Map<String, Object> nativeConfig = parseJsonSafe(t.getNativeToolConfig());
                                    return LlmChatRequest.Tool.builder()
                                            .name(t.getName())
                                            .description(t.getDescription())
                                            .inputSchema(t.getInputSchema())
                                            .nativeToolConfig(nativeConfig)
                                            .build();
                                }
                                return LlmChatRequest.Tool.builder()
                                        .name(t.getName())
                                        .description(t.getDescription())
                                        .inputSchema(t.getInputSchema())
                                        .build();
                            }).toList();

                    LlmChatRequest request = LlmChatRequest.builder()
                            .model(model)
                            .maxTokens(maxTokens)
                            .systemPrompt(systemPrompt)
                            .messages(messages)
                            .tools(llmTools.isEmpty() ? null : llmTools)
                            .build();

                    LlmChatResponse response = provider.chat(request, config.getApiKey(), config.getBaseUrl());
                    totalInputTokens += response.getInputTokens();
                    totalOutputTokens += response.getOutputTokens();
                    totalCacheCreationTokens += response.getCacheCreationInputTokens();
                    totalCacheReadTokens += response.getCacheReadInputTokens();
                    totalCost = provider.estimateCost(model, totalInputTokens, totalOutputTokens,
                            totalCacheCreationTokens, totalCacheReadTokens);

                    if ("tool_use".equals(response.getStopReason())) {
                        List<Object> assistantContent = new ArrayList<>();
                        List<Object> toolResults = new ArrayList<>();

                        ToolBlockBatch batch = collectToolBlocks(response, assistantContent);
                        lastTextResponse = batch.lastText != null ? batch.lastText : lastTextResponse;

                        List<ToolResult> results = processToolUseBlocksParallel(
                                batch.toolBlocks, tools, tenantId, runPid, taskPid, agentCode, traceCtx);

                        for (int k = 0; k < results.size(); k++) {
                            ToolResult tr = results.get(k);
                            toolResults.add(Map.of("type", "tool_result", "tool_use_id", tr.toolUseId(), "content", tr.result()));
                            toolCallLog.add(Map.of("tool", tr.name(),
                                    "input", batch.toolBlocks.get(k).getInput(),
                                    "output", tr.result(),
                                    "step", i));
                        }
                        messages.add(LlmChatRequest.Message.builder().role("assistant").content(assistantContent).build());
                        messages.add(LlmChatRequest.Message.builder().role("user").content(toolResults).build());
                    } else {
                        // Step complete (end_turn or max_tokens)
                        for (LlmChatResponse.ContentBlock block : response.getContent()) {
                            if ("text".equals(block.getType())) lastTextResponse = block.getText();
                        }
                        break;
                    }
                }

                step.setStatus(AgentPlanStep.StepStatus.COMPLETED);
                step.setResult(truncate(lastTextResponse, 200));
                step.setFinishedAt(LocalDateTime.now());
                step.setDurationMs(System.currentTimeMillis() - stepStart);
                Map<String, Object> outSummary = new java.util.LinkedHashMap<>();
                outSummary.put("status", "success");
                outSummary.put("text", truncate(lastTextResponse, 200));
                step.setOutput(outSummary);

                // Cost guard
                if (totalCost > costLimit) {
                    log.warn("Cost limit exceeded at step {}: ${} > ${}", i, totalCost, costLimit);
                    for (int j = i + 1; j < plan.size(); j++) plan.get(j).setStatus(AgentPlanStep.StepStatus.SKIPPED);
                    break;
                }
                persistPlan(runPid, plan, i + 1);

            } catch (AgentApprovalPendingException e) {
                throw e;
            } catch (Exception e) {
                step.setStatus(AgentPlanStep.StepStatus.FAILED);
                step.setError(e.getMessage());
                step.setFinishedAt(LocalDateTime.now());
                step.setDurationMs(System.currentTimeMillis() - stepStart);
                Map<String, Object> failSummary = new java.util.LinkedHashMap<>();
                failSummary.put("status", "failed");
                failSummary.put("error", truncate(e.getMessage(), 200));
                step.setOutput(failSummary);

                // Attempt adaptive re-planning
                boolean replanned = attemptReplan(plan, i, e.getMessage(), provider, config, model, systemPrompt, tools, messages);
                if (!replanned) {
                    persistPlan(runPid, plan, i);
                    throw e;
                }
                log.info("Re-planned after step {} failure, {} steps remaining", i, plan.size() - i - 1);
                persistPlan(runPid, plan, i + 1);
            }
        }
        StepContext.clear();

        updateRunToolCalls(runPid, toolCallLog);

        AgentRunService.AgentLoopResult result = new AgentRunService.AgentLoopResult();
        result.success = plan.stream().noneMatch(s -> s.getStatus() == AgentPlanStep.StepStatus.FAILED);
        result.totalInputTokens = totalInputTokens;
        result.totalOutputTokens = totalOutputTokens;
        result.totalCost = totalCost;
        result.lastResponse = lastTextResponse;
        return result;
    }

    // =========================================================================
    // Replan
    // =========================================================================

    boolean attemptReplan(List<AgentPlanStep> plan, int failedStepIndex, String errorMessage,
                           LlmProvider provider, LlmProviderFactory.ProviderConfig config,
                           String model, String systemPrompt, List<AgentToolDefinition> tools,
                           List<LlmChatRequest.Message> messages) {
        String remaining = plan.subList(failedStepIndex + 1, plan.size()).stream()
                .map(AgentPlanStep::getDescription).collect(Collectors.joining("\n- ", "- ", ""));
        String replanPrompt = "## Re-Planning Required\nStep " + failedStepIndex + " failed: " + errorMessage
                + "\n\nRemaining steps were:\n" + remaining
                + "\n\nProvide a revised JSON array of steps for the remaining work. Empty array [] if cannot continue.";

        try {
            LlmChatRequest req = LlmChatRequest.builder()
                    .model(model).systemPrompt(systemPrompt)
                    .messages(List.of(LlmChatRequest.Message.builder().role("user").content(replanPrompt).build()))
                    .maxTokens(2000).build();

            LlmChatResponse resp = provider.chat(req, config.getApiKey(), config.getBaseUrl());
            String content = resp.getContent().stream()
                    .filter(b -> "text".equals(b.getType()))
                    .map(LlmChatResponse.ContentBlock::getText)
                    .collect(Collectors.joining());

            String jsonStr = extractJsonArray(content);
            if (jsonStr != null) {
                List<Map<String, Object>> rawSteps = objectMapper.readValue(jsonStr, new TypeReference<>() {});
                if (rawSteps.isEmpty()) return false;
                while (plan.size() > failedStepIndex + 1) plan.remove(plan.size() - 1);
                int baseIndex = failedStepIndex + 1;
                for (int j = 0; j < rawSteps.size(); j++) {
                    Map<String, Object> raw = rawSteps.get(j);
                    AgentPlanStep newStep = new AgentPlanStep(baseIndex + j, (String) raw.get("description"));
                    newStep.setToolCode((String) raw.get("toolCode"));
                    newStep.setRequiresApproval(Boolean.TRUE.equals(raw.get("requiresApproval")));
                    plan.add(newStep);
                }
                return true;
            }
        } catch (Exception e) {
            log.warn("Re-planning failed: {}", e.getMessage());
        }
        return false;
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    String buildStepContext(List<AgentPlanStep> plan, int currentIndex) {
        StringBuilder sb = new StringBuilder("## Execution Progress\n");
        for (int i = 0; i < plan.size(); i++) {
            AgentPlanStep s = plan.get(i);
            String mark = i < currentIndex ? "x" : (i == currentIndex ? ">" : " ");
            sb.append(String.format("[%s] Step %d: %s", mark, i, s.getDescription()));
            if (s.getResult() != null) sb.append(" — ").append(s.getResult());
            sb.append("\n");
        }
        return sb.toString();
    }

    String truncate(String text, int maxLen) {
        return text != null && text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    void checkTimeout(String runPid, LocalDateTime startedAt) {
        LocalDateTime now = LocalDateTime.now();
        try {
            String sql = "SELECT timeout_at FROM ab_agent_run WHERE pid = #{params.runPid}";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, Map.of("runPid", runPid));
            if (!rows.isEmpty() && rows.get(0).get("timeout_at") != null) {
                Object timeoutAtObj = rows.get(0).get("timeout_at");
                LocalDateTime timeoutAt;
                if (timeoutAtObj instanceof java.sql.Timestamp ts) {
                    timeoutAt = ts.toLocalDateTime();
                } else if (timeoutAtObj instanceof LocalDateTime ldt) {
                    timeoutAt = ldt;
                } else {
                    return;
                }
                if (now.isAfter(timeoutAt)) {
                    throw new RuntimeException("Run " + runPid + " exceeded timeout. Started: " + startedAt + ", Timeout: " + timeoutAt);
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // Private helpers (duplicated from AgentRunService to keep extraction clean)
    // =========================================================================

    private String resolveModel(Map<String, Object> agentDef, String providerCode) {
        if (agentDef != null) {
            String agentModel = (String) agentDef.get("model");
            if (agentModel != null && !agentModel.isBlank()) {
                // Check if the agent model belongs to the resolved provider
                String inferredProvider = providerFactory.resolveProviderByModel(agentModel);
                if (inferredProvider != null && inferredProvider.equals(providerCode)) {
                    return agentModel;
                }
                // Model doesn't match provider (e.g. agent has claude but using minimax) — use provider default
            }
        }
        return providerFactory.getDefaultModel(providerCode);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonSafe(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

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

    private void persistPlan(String runPid, List<AgentPlanStep> plan, int currentStep) {
        try {
            String planJson = objectMapper.writeValueAsString(plan);
            Map<String, Object> data = new HashMap<>();
            data.put("execution_plan", planJson);
            data.put("current_step", currentStep);
            data.put("updated_at", LocalDateTime.now());
            dynamicDataMapper.updateWithJsonb("ab_agent_run", data, Map.of("pid", runPid),
                    Set.of("execution_plan"));
        } catch (Exception e) {
            log.warn("Failed to persist plan for run {}: {}", runPid, e.getMessage());
        }
    }

    private void updateRunToolCalls(String runPid, List<Map<String, Object>> toolCallLog) {
        try {
            String json = objectMapper.writeValueAsString(toolCallLog);
            dynamicDataMapper.update("ab_agent_run", Map.of("tool_calls", json, "updated_at", LocalDateTime.now()),
                    Map.of("pid", runPid));
        } catch (Exception e) {
            log.error("Failed to update tool_calls: {}", e.getMessage());
        }
    }

    // =========================================================================
    // ACP P0-5 Parallel Tool Calls
    // =========================================================================

    /** Result of one tool execution, in original LLM block order. */
    record ToolResult(String toolUseId, String name, String result) {}

    /** Tool-use blocks extracted from a single LLM response, plus assistant text echo. */
    static final class ToolBlockBatch {
        final List<LlmChatResponse.ContentBlock> toolBlocks = new ArrayList<>();
        String lastText;
    }

    /**
     * Walk the LLM response once: collect tool_use blocks for execution and
     * mirror the entire assistant turn (text + tool_use) into {@code assistantContent}
     * so the conversation history stays exact.
     */
    ToolBlockBatch collectToolBlocks(LlmChatResponse response, List<Object> assistantContent) {
        ToolBlockBatch batch = new ToolBlockBatch();
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType())) {
                assistantContent.add(Map.of("type", "text", "text", block.getText()));
                batch.lastText = block.getText();
            } else if ("tool_use".equals(block.getType())) {
                assistantContent.add(Map.of(
                        "type", "tool_use",
                        "id", block.getId(),
                        "name", block.getName(),
                        "input", block.getInput()));
                batch.toolBlocks.add(block);
            }
        }
        return batch;
    }

    /**
     * Execute a batch of tool_use blocks, parallel where safe.
     *
     * <p>Policy:
     * <ul>
     *   <li>If parallel disabled or batch ≤ 1 → fully serial path (legacy semantics).</li>
     *   <li>If batch size > {@code parallel.maxFanout} → reject the whole batch
     *       and surface an error message back to the LLM for every block. This
     *       feeds back into the next loop iteration so the LLM can retry with
     *       fewer concurrent tool calls. Refusing to silently degrade keeps
     *       executor / DB pool from being overwhelmed by runaway agents.</li>
     *   <li>Otherwise: split into approval-required vs parallel-eligible. Approval
     *       blocks run serially first (an approval pending throws and short-circuits
     *       the run; we cannot let other tools run while a human is being asked
     *       to consent to a different one). Remaining blocks dispatch to
     *       {@code asyncTaskExecutor} and join via {@link CompletableFuture#allOf}.</li>
     * </ul>
     *
     * <p>Results are returned in the SAME order as {@code toolBlocks} — LLM
     * message ordering is contract-sensitive (Anthropic / OpenAI both expect
     * tool_result blocks to align with tool_use ids). CompletableFuture finish
     * order is irrelevant; we collect by index.
     *
     * <p>{@code MetaContext} (tenant/user) is auto-propagated by
     * {@link com.auraboot.framework.event.config.TenantAwareTaskDecorator}. The
     * StepContext parallel coordinates are set inside each lambda explicitly.
     */
    List<ToolResult> processToolUseBlocksParallel(List<LlmChatResponse.ContentBlock> toolBlocks,
                                                   List<AgentToolDefinition> tools,
                                                   Long tenantId, String runPid, String taskPid, String agentCode,
                                                   TraceContext traceCtx) {
        ToolResult[] results = new ToolResult[toolBlocks.size()];

        if (toolBlocks.isEmpty()) {
            return List.of();
        }

        // AgentProperties.parallel is eagerly initialised in the @Data POJO
        // (see AgentProperties.java field initializer = new Parallel()) so the
        // reference is never null in production. No defensive null guard.
        AgentProperties.Parallel cfg = agentProperties.getParallel();
        boolean parallelEnabled = cfg.isEnabled();
        int maxFanout = cfg.getMaxFanout();
        long totalTimeoutMs = cfg.getTotalTimeoutMs();

        // Fanout guard: reject and report back to LLM. The LLM should reduce
        // the batch on retry. We deliberately do NOT degrade to serial because
        // a runaway 50-tool batch served serially is a worse failure mode.
        //
        // M3 fix: emit a structured JSON ToolResult for every tool_use_id so
        // the LLM (and any frontend rendering tool errors) can recognise this
        // as a single batch-level rejection rather than N independent failures.
        if (toolBlocks.size() > maxFanout) {
            String errJson = buildFanoutExceededJson(toolBlocks.size(), maxFanout);
            log.warn("Parallel fanout rejected: run={}, fanout={}, max={}", runPid, toolBlocks.size(), maxFanout);
            for (int i = 0; i < toolBlocks.size(); i++) {
                LlmChatResponse.ContentBlock b = toolBlocks.get(i);
                results[i] = new ToolResult(b.getId(), b.getName(), errJson);
            }
            return Arrays.asList(results);
        }

        // Single block, or parallel disabled → straight serial path. No group id.
        if (!parallelEnabled || toolBlocks.size() == 1) {
            for (int i = 0; i < toolBlocks.size(); i++) {
                LlmChatResponse.ContentBlock b = toolBlocks.get(i);
                String r = toolLoopService.executeToolCall(tenantId, runPid, taskPid, agentCode,
                        b.getName(), b.getInput(), tools, traceCtx);
                results[i] = new ToolResult(b.getId(), b.getName(), r);
            }
            return Arrays.asList(results);
        }

        // Split: approval-required (serial-first) vs eligible-for-parallel.
        // We preserve original index so result placement stays deterministic.
        List<Integer> approvalIdx = new ArrayList<>();
        List<Integer> parallelIdx = new ArrayList<>();
        for (int i = 0; i < toolBlocks.size(); i++) {
            LlmChatResponse.ContentBlock b = toolBlocks.get(i);
            AgentToolDefinition def = findToolDef(tools, b.getName());
            if (def != null && def.isRequiresApproval()) {
                approvalIdx.add(i);
            } else {
                parallelIdx.add(i);
            }
        }

        // 1) Run approval-required tools serially FIRST. If any throws an
        //    AgentApprovalPendingException the run halts here — we must not
        //    let other tools fire while the human is still deciding.
        for (int i : approvalIdx) {
            LlmChatResponse.ContentBlock b = toolBlocks.get(i);
            String r = toolLoopService.executeToolCall(tenantId, runPid, taskPid, agentCode,
                    b.getName(), b.getInput(), tools, traceCtx);
            results[i] = new ToolResult(b.getId(), b.getName(), r);
        }

        // 2) Dispatch the rest concurrently. ULID groupId stamps every Action
        //    so audits can reconstruct "these N tool calls came from the same
        //    LLM batch".
        if (parallelIdx.size() == 1) {
            int i = parallelIdx.get(0);
            LlmChatResponse.ContentBlock b = toolBlocks.get(i);
            String r = toolLoopService.executeToolCall(tenantId, runPid, taskPid, agentCode,
                    b.getName(), b.getInput(), tools, traceCtx);
            results[i] = new ToolResult(b.getId(), b.getName(), r);
        } else if (!parallelIdx.isEmpty()) {
            String groupId = UniqueIdGenerator.generate();
            log.debug("Parallel tool batch: run={}, group={}, fanout={}", runPid, groupId, parallelIdx.size());

            List<CompletableFuture<Void>> futures = new ArrayList<>(parallelIdx.size());
            for (int pos = 0; pos < parallelIdx.size(); pos++) {
                final int slot = parallelIdx.get(pos);
                final int parallelIndex = pos;
                LlmChatResponse.ContentBlock b = toolBlocks.get(slot);
                CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                    // StepContext is intentionally NOT auto-propagated by
                    // TenantAwareTaskDecorator (it is single-purpose).
                    // Set parallel coords here so ActionRecorder stamps them.
                    StepContext.setParallel(groupId, parallelIndex);
                    try {
                        String r = toolLoopService.executeToolCall(tenantId, runPid, taskPid, agentCode,
                                b.getName(), b.getInput(), tools, traceCtx);
                        results[slot] = new ToolResult(b.getId(), b.getName(), r);
                    } catch (Throwable t) {
                        // ToolLoopService.executeToolCall is supposed to swallow
                        // and return a String "Error: ..." but we defend in depth
                        // so one tool's blowup never breaks the batch.
                        log.warn("Parallel tool {} failed: {}", b.getName(), t.getMessage());
                        results[slot] = new ToolResult(b.getId(), b.getName(),
                                "Error: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                    } finally {
                        StepContext.clearParallel();
                    }
                }, asyncTaskExecutor);
                futures.add(f);
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(totalTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                log.warn("Parallel tool batch timed out after {}ms: run={}, group={}", totalTimeoutMs, runPid, groupId);
                // M5 fix: cancel still-running futures so worker threads do not
                // continue chewing connections / per-tool 60s timeouts after we
                // have already given up on the batch. cancel(true) interrupts
                // the worker; ToolLoopService boundary returns "Error: ..." on
                // catch, so we still leave the audit trail consistent.
                for (CompletableFuture<Void> f : futures) {
                    if (!f.isDone()) {
                        f.cancel(true);
                    }
                }
                for (int i : parallelIdx) {
                    if (results[i] == null) {
                        LlmChatResponse.ContentBlock b = toolBlocks.get(i);
                        results[i] = new ToolResult(b.getId(), b.getName(),
                                "Error: tool batch timed out after " + totalTimeoutMs + "ms");
                    }
                }
            } catch (Exception e) {
                log.error("Parallel tool batch failed: run={}, group={}, err={}", runPid, groupId, e.getMessage());
                for (int i : parallelIdx) {
                    if (results[i] == null) {
                        LlmChatResponse.ContentBlock b = toolBlocks.get(i);
                        results[i] = new ToolResult(b.getId(), b.getName(),
                                "Error: parallel batch failure — " + e.getMessage());
                    }
                }
            }
        }

        return Arrays.asList(results);
    }

    private AgentToolDefinition findToolDef(List<AgentToolDefinition> tools, String name) {
        if (tools == null) return null;
        for (AgentToolDefinition t : tools) {
            if (t.getName() != null && t.getName().equals(name)) return t;
        }
        return null;
    }

    /**
     * Build the structured JSON payload returned to the LLM for every tool_use_id
     * when the batch is rejected for exceeding {@code parallel.maxFanout}. Single
     * batch-level error code so the LLM (and frontend renderers) can recognise
     * this as a coordinated rejection rather than N independent tool failures.
     *
     * <p>Hand-rolled JSON keeps this method allocation-cheap and never throws
     * (ObjectMapper would require a try/catch we'd then have to swallow).
     */
    private String buildFanoutExceededJson(int fanout, int max) {
        return "{\"error\":\"batch_fanout_exceeded\","
                + "\"fanout\":" + fanout + ","
                + "\"max\":" + max + ","
                + "\"action\":\"retry_with_fewer_tools\","
                + "\"message\":\"Requested " + fanout + " tools in single turn, max is " + max
                + ". Reduce parallel tool count and retry.\"}";
    }
}
