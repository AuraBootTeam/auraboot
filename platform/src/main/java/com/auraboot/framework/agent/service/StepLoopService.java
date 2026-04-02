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
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ACP Kernel: Step Loop service.
 *
 * Extracted from AgentRunService — handles the LLM chat loop and plan step execution.
 * Pure method extraction, no logic changes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StepLoopService {

    private final ToolLoopService toolLoopService;
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;
    private final LlmProviderFactory providerFactory;
    private final AiTraceService aiTraceService;
    private final AgentApprovalGateService approvalGate;
    private final AgentProperties agentProperties;

    static final int MAX_TOOL_LOOPS = 20;

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
        double totalCost = 0;
        String lastTextResponse = "";
        List<Map<String, Object>> toolCallLog = new ArrayList<>();

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
            totalCost = provider.estimateCost(model, totalInputTokens, totalOutputTokens);

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

                for (LlmChatResponse.ContentBlock block : response.getContent()) {
                    if ("text".equals(block.getType())) {
                        assistantContent.add(Map.of("type", "text", "text", block.getText()));
                        lastTextResponse = block.getText();
                    } else if ("tool_use".equals(block.getType())) {
                        assistantContent.add(Map.of(
                                "type", "tool_use",
                                "id", block.getId(),
                                "name", block.getName(),
                                "input", block.getInput()));

                        String toolResult = toolLoopService.executeToolCall(tenantId, runPid, taskPid, agentCode,
                                block.getName(), block.getInput(), tools, traceCtx);

                        toolResults.add(Map.of(
                                "type", "tool_result",
                                "tool_use_id", block.getId(),
                                "content", toolResult));

                        toolCallLog.add(Map.of(
                                "tool", block.getName(),
                                "input", block.getInput(),
                                "output", toolResult,
                                "loop", loop));
                    }
                }

                messages.add(LlmChatRequest.Message.builder().role("assistant").content(assistantContent).build());
                messages.add(LlmChatRequest.Message.builder().role("user").content(toolResults).build());
            }
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
            AgentPlanStep step = plan.get(i);
            step.setStatus(AgentPlanStep.StepStatus.RUNNING);
            long stepStart = System.currentTimeMillis();

            // Check approval gate for this step
            if (step.isRequiresApproval() && !(skipApprovalForResumedStep && i == startStep)) {
                String approvalPid = approvalGate.checkAndRequestApproval(
                        tenantId, runPid, taskPid, step.getToolCode() != null ? step.getToolCode() : "step_" + i,
                        step.getDescription(), Map.of("stepIndex", i), true);
                if (approvalPid != null) {
                    step.setStatus(AgentPlanStep.StepStatus.AWAITING_APPROVAL);
                    persistPlan(runPid, plan, i);
                    throw new AgentApprovalPendingException("Step " + i + " awaiting approval: " + step.getDescription());
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
                    totalCost = provider.estimateCost(model, totalInputTokens, totalOutputTokens);

                    if ("tool_use".equals(response.getStopReason())) {
                        List<Object> assistantContent = new ArrayList<>();
                        List<Object> toolResults = new ArrayList<>();

                        for (LlmChatResponse.ContentBlock block : response.getContent()) {
                            if ("text".equals(block.getType())) {
                                assistantContent.add(Map.of("type", "text", "text", block.getText()));
                                lastTextResponse = block.getText();
                            } else if ("tool_use".equals(block.getType())) {
                                assistantContent.add(Map.of(
                                        "type", "tool_use", "id", block.getId(),
                                        "name", block.getName(), "input", block.getInput()));
                                String toolResult = toolLoopService.executeToolCall(tenantId, runPid, taskPid, agentCode,
                                        block.getName(), block.getInput(), tools, traceCtx);
                                toolResults.add(Map.of("type", "tool_result", "tool_use_id", block.getId(), "content", toolResult));
                                toolCallLog.add(Map.of("tool", block.getName(), "input", block.getInput(),
                                        "output", toolResult, "step", i));
                            }
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
                step.setDurationMs(System.currentTimeMillis() - stepStart);

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
                step.setDurationMs(System.currentTimeMillis() - stepStart);

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
}
