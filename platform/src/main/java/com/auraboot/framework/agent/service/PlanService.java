package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentPlanStep;
import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlanService {

    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;
    private final AgentApprovalGateService approvalGate;

    /**
     * Generate an execution plan via LLM.
     */
    List<AgentPlanStep> generatePlan(LlmProvider provider, LlmProviderFactory.ProviderConfig config,
                                      String model, String systemPrompt, String userMessage,
                                      List<AgentToolDefinition> tools) {
        String toolNames = tools.stream().map(AgentToolDefinition::getName).collect(Collectors.joining(", "));
        String planningPrompt = systemPrompt + "\n\n## Planning Phase\n"
                + "You are in PLANNING mode. Analyze the task and create a step-by-step execution plan.\n"
                + "Respond with a JSON array of steps. Each step has:\n"
                + "- \"description\": what this step does (concise)\n"
                + "- \"toolCode\": which tool to use (from available tools), or null for reasoning-only steps\n"
                + "- \"requiresApproval\": true only for reasoning-only checkpoints that need human sign-off;\n"
                + "  tool-specific approval is enforced separately at execution time, so ordinary tool steps should use false\n\n"
                + "Available tools: " + toolNames + "\n\n"
                + "Respond ONLY with the JSON array. Example:\n"
                + "[{\"description\":\"Search for data\",\"toolCode\":\"nq_active_projects\",\"requiresApproval\":false}]\n"
                + "If the task is simple enough for one step, return a single-element array.";

        LlmChatRequest planReq = LlmChatRequest.builder()
                .model(model)
                .systemPrompt(planningPrompt)
                .messages(List.of(LlmChatRequest.Message.builder().role("user").content(userMessage).build()))
                .maxTokens(2000)
                .build();

        try {
            LlmChatResponse planResp = provider.chat(planReq, config.getApiKey(), config.getBaseUrl());
            String content = planResp.getContent().stream()
                    .filter(b -> "text".equals(b.getType()))
                    .map(LlmChatResponse.ContentBlock::getText)
                    .collect(Collectors.joining());

            String jsonStr = extractJsonArray(content);
            if (jsonStr != null) {
                List<Map<String, Object>> rawSteps = objectMapper.readValue(jsonStr, new TypeReference<>() {});
                List<AgentPlanStep> steps = new ArrayList<>();
                for (int i = 0; i < rawSteps.size(); i++) {
                    Map<String, Object> raw = rawSteps.get(i);
                    AgentPlanStep step = new AgentPlanStep(i, (String) raw.get("description"));
                    step.setToolCode((String) raw.get("toolCode"));
                    step.setRequiresApproval(Boolean.TRUE.equals(raw.get("requiresApproval")));
                    steps.add(step);
                }
                if (!steps.isEmpty()) return steps;
            }
        } catch (Exception e) {
            log.warn("Plan generation failed, falling back to direct execution: {}", e.getMessage());
        }
        return List.of(new AgentPlanStep(0, "Execute task directly"));
    }

    /**
     * Validate a generated plan before execution.
     * - Check all toolCodes exist in loaded tools
     * - Check for high-risk steps that need plan-level approval
     * - Reject plans with hallucinated tool codes
     */
    void validatePlan(List<AgentPlanStep> plan, List<AgentToolDefinition> tools, String runPid) {
        Set<String> validToolCodes = tools.stream()
                .map(AgentToolDefinition::getName)
                .collect(Collectors.toSet());

        int invalidCount = 0;
        boolean hasHighRisk = false;

        for (AgentPlanStep step : plan) {
            String toolCode = step.getToolCode();
            boolean requestedPlanApproval = step.isRequiresApproval();
            if (toolCode != null && !toolCode.isBlank() && !validToolCodes.contains(toolCode)) {
                log.warn("Plan validation: step {} references non-existent tool '{}', clearing", step.getStepIndex(), toolCode);
                step.setToolCode(null); // Clear hallucinated tool code
                invalidCount++;
                toolCode = null;
            }

            // Approval is enforced at tool execution time. Do not let the plan layer introduce
            // non-deterministic extra approval gates for ordinary steps.
            step.setRequiresApproval(false);

            if (requestedPlanApproval) {
                if (toolCode != null) {
                    log.info("Plan step {} requested approval for tool '{}'; deferring to tool-level approval",
                            step.getStepIndex(), toolCode);
                } else {
                    log.info("Plan step {} requested plan-level approval without a tool; ignoring to keep runtime deterministic",
                            step.getStepIndex());
                }
            }

            if (toolCode != null) {
                final String validatedToolCode = toolCode;
                tools.stream()
                        .filter(t -> t.getName().equals(validatedToolCode))
                        .findFirst()
                        .ifPresent(t -> {
                            if ("high".equals(t.getRiskLevel())) {
                                log.info("Plan step {} uses high-risk tool '{}'; approval will be enforced at tool execution time",
                                        step.getStepIndex(), validatedToolCode);
                            }
                        });
            }
        }

        if (invalidCount > 0) {
            log.info("Plan validation: {} hallucinated tool codes cleared for run {}", invalidCount, runPid);
        }

        // Check if any step has HIGH risk → mark for plan-level warning
        for (AgentPlanStep step : plan) {
            if (step.isRequiresApproval()) {
                hasHighRisk = true;
                break;
            }
        }

        if (hasHighRisk) {
            log.info("Plan contains high-risk steps requiring approval for run {}", runPid);
        }
    }

    /**
     * Save plan to ab_agent_run.execution_plan.
     */
    void persistPlan(String runPid, List<AgentPlanStep> plan, int currentStep) {
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

    /**
     * Load plan from a previous run.
     */
    List<AgentPlanStep> loadPlanFromRun(String runPid) {
        try {
            String sql = "SELECT execution_plan FROM ab_agent_run WHERE pid = #{params.runPid}";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, Map.of("runPid", runPid));
            if (!rows.isEmpty()) {
                Object raw = rows.get(0).get("execution_plan");
                String planJson = raw instanceof String s ? s : (raw != null ? objectMapper.writeValueAsString(raw) : null);
                if (planJson != null) {
                    return objectMapper.readValue(planJson, new TypeReference<>() {});
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load plan from run {}: {}", runPid, e.getMessage());
        }
        return List.of(new AgentPlanStep(0, "Execute task directly"));
    }

    /**
     * Find first pending step for resume.
     */
    int findFirstPendingStep(List<AgentPlanStep> plan) {
        for (int i = 0; i < plan.size(); i++) {
            if (!plan.get(i).isTerminal()) return i;
        }
        return plan.size();
    }

    /**
     * Parse JSON array from LLM output (handles markdown code blocks).
     */
    String extractJsonArray(String text) {
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

    /**
     * Safe JSON parsing — returns null on failure.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> parseJsonSafe(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
