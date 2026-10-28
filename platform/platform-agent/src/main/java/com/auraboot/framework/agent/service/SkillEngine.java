package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.SkillInput;
import com.auraboot.framework.agent.dto.SkillResult;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.trace.TraceContext;
import com.auraboot.framework.agent.util.JsonbColumns;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.JsonSchemaException;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * ACP Kernel: Skill execution engine — the core of Phase 3.
 *
 * Supports four execution modes:
 * - template:       Single tool call via ToolLoopService
 * - sequential:     Ordered multi-tool execution with $ref input mappings
 * - orchestration:  LLM-driven tool selection (P0 stub → falls back to sequential)
 * - dsl_dispatch:   Dynamic DSL resolution through the governed ToolLoopService
 *
 * Four-contract execution flow:
 *   validateInput → execute by mode → handleFailure → buildResult
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillEngine {

    private final AgentSkillService agentSkillService;
    private final ToolLoopService toolLoopService;
    private final StepLoopService stepLoopService;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Execute a skill by code with the given input.
     *
     * @param tenantId   current tenant
     * @param runPid     agent run PID (for tracing/action recording)
     * @param skillCode  skill code from ab_agent_skill
     * @param input      skill-level semantic input
     * @param traceCtx   trace context for observability
     * @param provider   LLM provider (required for orchestration mode, null for template/sequential)
     * @param config     LLM provider config (required for orchestration mode, null for template/sequential)
     * @return SkillResult with status, data, timing, and metadata
     */
    public SkillResult execute(Long tenantId, String runPid, String skillCode,
                               SkillInput input, TraceContext traceCtx,
                               LlmProvider provider, LlmProviderFactory.ProviderConfig config) {
        long startMs = System.currentTimeMillis();

        // 1. Load skill definition
        Map<String, Object> skill = agentSkillService.loadSkill(tenantId, skillCode);
        if (skill == null) {
            return SkillResult.builder()
                    .skillCode(skillCode)
                    .status(SkillResult.Status.FAILED)
                    .errorMessage("Skill not found: " + skillCode)
                    .durationMs(System.currentTimeMillis() - startMs)
                    .build();
        }

        // 2. Validate input against skill_input_schema
        String validationError = validateInput(input, skill.get("skill_input_schema"));
        if (validationError != null) {
            return SkillResult.builder()
                    .skillCode(skillCode)
                    .status(SkillResult.Status.FAILED)
                    .errorMessage("Input validation failed: " + validationError)
                    .durationMs(System.currentTimeMillis() - startMs)
                    .build();
        }

        // 3. Resolve execution mode and tools
        String executionMode = resolveString(skill.get("execution_mode"), "template");
        String failureMode = resolveString(skill.get("failure_mode"), "fail_fast");
        int maxRetry = resolveInt(skill.get("max_retry"), 0);
        List<String> toolCodes = parseToolCodes(skill.get("skill_tools"));

        if (!isSupportedExecutionMode(executionMode)) {
            return SkillResult.builder()
                    .skillCode(skillCode)
                    .status(SkillResult.Status.FAILED)
                    .errorMessage("Unsupported skill execution mode: " + executionMode)
                    .durationMs(System.currentTimeMillis() - startMs)
                    .build();
        }

        List<AgentToolDefinition> tools = agentSkillService.resolveSkillTools(tenantId, skillCode);

        if (tools.isEmpty() && !"dsl_dispatch".equals(executionMode)) {
            return SkillResult.builder()
                    .skillCode(skillCode)
                    .status(SkillResult.Status.FAILED)
                    .errorMessage("No active tools found for skill: " + skillCode)
                    .durationMs(System.currentTimeMillis() - startMs)
                    .build();
        }

        // 4. Execute by mode with failure handling
        SkillResult result;
        try {
            result = executeWithRetry(tenantId, runPid, skillCode, input, skill,
                    executionMode, failureMode, maxRetry, toolCodes, tools, traceCtx,
                    provider, config);
        } catch (Exception e) {
            log.error("Skill execution failed: code={}, mode={}, error={}", skillCode, executionMode, e.getMessage());
            result = SkillResult.builder()
                    .skillCode(skillCode)
                    .status(SkillResult.Status.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
        }

        // 5. Finalize result with skill metadata
        long durationMs = System.currentTimeMillis() - startMs;
        result.setDurationMs(durationMs);
        result.setSkillCode(skillCode);
        if (result.getOutputType() == null) {
            result.setOutputType(resolveString(skill.get("output_type"), "text"));
        }
        if (result.getRenderHint() == null) {
            result.setRenderHint(resolveString(skill.get("render_hint"), null));
        }

        log.info("Skill executed: code={}, mode={}, status={}, duration={}ms",
                skillCode, executionMode, result.getStatus(), durationMs);
        return result;
    }

    // =========================================================================
    // Execution Modes
    // =========================================================================

    private SkillResult executeWithRetry(Long tenantId, String runPid, String skillCode,
                                         SkillInput input, Map<String, Object> skill,
                                         String executionMode, String failureMode, int maxRetry,
                                         List<String> toolCodes, List<AgentToolDefinition> tools,
                                         TraceContext traceCtx,
                                         LlmProvider provider, LlmProviderFactory.ProviderConfig config) {
        int attempts = 0;
        SkillResult lastResult = null;

        while (attempts <= maxRetry) {
            try {
                lastResult = switch (executionMode) {
                    case "template" -> executeTemplate(tenantId, runPid, toolCodes, tools, input, traceCtx);
                    case "sequential" -> executeSequential(tenantId, runPid, skill, toolCodes, tools, input, traceCtx);
                    case "orchestration" -> executeOrchestration(tenantId, runPid, skill, toolCodes, tools, input, traceCtx, provider, config);
                    case "dsl_dispatch" -> executeDslDispatch(tenantId, runPid, skill, input, traceCtx);
                    default -> throw new IllegalArgumentException("Unsupported skill execution mode: " + executionMode);
                };

                if (containsExecutionStop(lastResult.getData())
                        || (lastResult.getStatus() == SkillResult.Status.FAILED && lastResult.getActionCount() > 0)) return lastResult;
                // If success or partial_success, return immediately
                if (lastResult.getStatus() != SkillResult.Status.FAILED) {
                    return lastResult;
                }

                // Failed — check failure mode
                if ("fail_fast".equals(failureMode)) {
                    return lastResult;
                }
                if ("best_effort".equals(failureMode)) {
                    // best_effort already returns PARTIAL_SUCCESS from sequential mode
                    return lastResult;
                }
                // retry_then_fail — loop continues
                attempts++;
                if (attempts <= maxRetry) {
                    log.info("Retrying skill {}: attempt {}/{}", skillCode, attempts, maxRetry);
                }

            } catch (Exception e) {
                if ("fail_fast".equals(failureMode)) {
                    throw new RuntimeException("Skill execution failed (fail_fast): " + e.getMessage(), e);
                }
                if ("best_effort".equals(failureMode)) {
                    return SkillResult.builder()
                            .status(SkillResult.Status.PARTIAL_SUCCESS)
                            .errorMessage(e.getMessage())
                            .build();
                }
                // retry_then_fail
                attempts++;
                if (attempts > maxRetry) {
                    throw new RuntimeException("Skill execution failed after " + attempts + " attempts: " + e.getMessage(), e);
                }
                log.info("Retrying skill {} after error: attempt {}/{}, error={}", skillCode, attempts, maxRetry, e.getMessage());
            }
        }

        // Exhausted retries
        return lastResult != null ? lastResult : SkillResult.builder()
                .status(SkillResult.Status.FAILED)
                .errorMessage("Exhausted all retries")
                .build();
    }

    private boolean isSupportedExecutionMode(String executionMode) {
        return "template".equals(executionMode)
                || "sequential".equals(executionMode)
                || "orchestration".equals(executionMode)
                || "dsl_dispatch".equals(executionMode);
    }

    /**
     * Template mode: single tool call — take first tool from skill_tools.
     */
    private SkillResult executeTemplate(Long tenantId, String runPid,
                                        List<String> toolCodes, List<AgentToolDefinition> tools,
                                        SkillInput input, TraceContext traceCtx) {
        String toolCode = toolCodes.get(0);
        Map<String, Object> params = input.getParameters() != null ? input.getParameters() : Map.of();

        String resultJson = toolLoopService.executeToolCall(
                tenantId, runPid, null, null, toolCode, params, tools, traceCtx);

        return parseToolResult(resultJson, 1);
    }

    /**
     * Sequential mode: execute each tool in skill_tools order.
     * Supports $ref input mappings from step_input_mappings to chain outputs.
     */
    @SuppressWarnings("unchecked")
    private SkillResult executeSequential(Long tenantId, String runPid,
                                          Map<String, Object> skill,
                                          List<String> toolCodes, List<AgentToolDefinition> tools,
                                          SkillInput input, TraceContext traceCtx) {
        String failureMode = resolveString(skill.get("failure_mode"), "fail_fast");
        Map<String, Map<String, Object>> stepMappings = parseStepInputMappings(skill.get("step_input_mappings"));

        List<Map<String, Object>> stepOutputs = new ArrayList<>();
        List<String> actionPids = new ArrayList<>();
        int successCount = 0;
        String lastError = null;

        for (int i = 0; i < toolCodes.size(); i++) {
            String toolCode = toolCodes.get(i);

            // Resolve input for this step: original input + $ref from previous outputs
            Map<String, Object> stepInput;
            if (i == 0) {
                stepInput = input.getParameters() != null ? new HashMap<>(input.getParameters()) : new HashMap<>();
            } else {
                Map<String, Object> mapping = stepMappings.get(String.valueOf(i));
                if (mapping == null) mapping = stepMappings.get(toolCode);
                stepInput = resolveReferences(mapping, input.getParameters(), stepOutputs);
            }

            try {
                String resultJson = toolLoopService.executeToolCall(
                        tenantId, runPid, null, null, toolCode, stepInput, tools, traceCtx);

                Map<String, Object> parsed = parseJsonSafe(resultJson);
                parsed.put("_stepIndex", i);
                parsed.put("_toolCode", toolCode);
                stepOutputs.add(parsed);

                boolean pending = Boolean.TRUE.equals(parsed.get("approvalRequired"))
                        || Boolean.TRUE.equals(parsed.get("confirmationRequired"));
                boolean isError = resultJson.startsWith("Error") || Boolean.FALSE.equals(parsed.get("success")) || pending;
                if (!isError) {
                    successCount++;
                } else {
                    lastError = resultJson;
                    if (pending || "fail_fast".equals(failureMode)) {
                        return SkillResult.builder()
                                .status(SkillResult.Status.FAILED)
                                .errorMessage("Step " + i + " (" + toolCode + ") failed: " + resultJson)
                                .toolCallCount(i + 1)
                                .actionCount(successCount)
                                .data(Map.of("steps", stepOutputs))
                                .build();
                    }
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                stepOutputs.add(Map.of("_stepIndex", i, "_toolCode", toolCode, "error", e.getMessage()));

                if ("fail_fast".equals(failureMode)) {
                    return SkillResult.builder()
                            .status(SkillResult.Status.FAILED)
                            .errorMessage("Step " + i + " (" + toolCode + ") failed: " + e.getMessage())
                            .toolCallCount(i + 1)
                            .actionCount(successCount)
                            .data(Map.of("steps", stepOutputs))
                            .build();
                }
            }
        }

        // Build combined result
        SkillResult.Status status;
        if (successCount == toolCodes.size()) {
            status = SkillResult.Status.SUCCESS;
        } else if (successCount > 0) {
            status = SkillResult.Status.PARTIAL_SUCCESS;
        } else {
            status = SkillResult.Status.FAILED;
        }

        return SkillResult.builder()
                .status(status)
                .toolCallCount(toolCodes.size())
                .actionCount(successCount)
                .data(Map.of("steps", stepOutputs))
                .errorMessage(lastError)
                .textSummary(successCount + "/" + toolCodes.size() + " steps completed")
                .build();
    }

    /**
     * Orchestration mode: delegate to LLM to decide which tools to call.
     *
     * Uses StepLoopService.executeAgentLoop() with the skill's constrained tool set
     * (only tools listed in skill_tools), max_steps, and prompt_template as system prompt.
     * Falls back to sequential if provider/config not available.
     */
    private SkillResult executeOrchestration(Long tenantId, String runPid,
                                             Map<String, Object> skill,
                                             List<String> toolCodes, List<AgentToolDefinition> tools,
                                             SkillInput input, TraceContext traceCtx,
                                             LlmProvider provider, LlmProviderFactory.ProviderConfig config) {
        String skillCode = resolveString(skill.get("skill_code"), "unknown");

        // Guard: orchestration requires LLM provider
        if (provider == null || config == null) {
            log.warn("Orchestration mode for skill '{}' requires LlmProvider and ProviderConfig. " +
                    "Falling back to sequential execution.", skillCode);
            return executeSequential(tenantId, runPid, skill, toolCodes, tools, input, traceCtx);
        }

        // 1. Build system prompt from skill's prompt_template
        String promptTemplate = resolveString(skill.get("prompt_template"), null);
        String systemPrompt = buildOrchestrationSystemPrompt(promptTemplate, toolCodes, input);

        // 2. Build user message from skill input
        String userMessage = buildOrchestrationUserMessage(input);

        // 3. Resolve max_steps and timeout from skill definition
        int maxSteps = resolveInt(skill.get("max_steps"), StepLoopService.MAX_TOOL_LOOPS);

        // 4. Build a minimal agentDef map for StepLoopService (it reads model + guardrails)
        Map<String, Object> agentDef = new HashMap<>();
        agentDef.put("guardrails", skill.get("guardrails"));
        // Let StepLoopService use the provider's default model
        agentDef.put("model", null);

        try {
            // F.2 wiring: pass the skill row so StepLoopService can merge
            // ab_agent_skill.execution_config (e.g. seeded report_analysis
            // thinking_enabled=true) onto the agent-level execution_config.
            // Skill keys win on conflict; null skillDef preserves agent-only
            // semantics.
            AgentRunService.AgentLoopResult loopResult = stepLoopService.executeAgentLoop(
                    tenantId, runPid, null, null,
                    systemPrompt, userMessage,
                    tools, agentDef, skill,
                    provider, config,
                    traceCtx, maxSteps);

            return SkillResult.builder()
                    .status(loopResult.success ? SkillResult.Status.SUCCESS : SkillResult.Status.FAILED)
                    .textSummary(loopResult.lastResponse)
                    .data(Map.of(
                            "response", loopResult.lastResponse != null ? loopResult.lastResponse : "",
                            "inputTokens", loopResult.totalInputTokens,
                            "outputTokens", loopResult.totalOutputTokens,
                            "cost", loopResult.totalCost,
                            "retryable", loopResult.success))
                    .toolCallCount(maxSteps) // approximate; actual count is tracked in run record
                    .build();

        } catch (AgentApprovalPendingException pending) {
            // CATCH: expected control flow; preserve approval identity and never retry this suspension.
            return SkillResult.builder().status(SkillResult.Status.FAILED)
                    .errorMessage(pending.getMessage())
                    .data(Map.of("approvalRequired", true, "approvalPid",
                            pending.getApprovalPid() != null ? pending.getApprovalPid() : ""))
                    .build();
        } catch (Exception e) {
            log.error("Orchestration failed for skill '{}': {}", skillCode, e.getMessage());
            return SkillResult.builder()
                    .status(SkillResult.Status.FAILED)
                    .errorMessage("Orchestration failed: " + e.getMessage())
                    // The loop may already have committed tools; retry belongs to its checkpoint boundary.
                    .data(Map.of("retryable", false))
                    .build();
        }
    }

    /**
     * Build system prompt for orchestration mode.
     * Combines the skill's prompt_template with available tool descriptions.
     */
    private String buildOrchestrationSystemPrompt(String promptTemplate, List<String> toolCodes, SkillInput input) {
        StringBuilder sb = new StringBuilder();

        if (promptTemplate != null && !promptTemplate.isBlank()) {
            // Substitute {{variables}} in template from input parameters
            String rendered = promptTemplate;
            if (input.getParameters() != null) {
                for (Map.Entry<String, Object> entry : input.getParameters().entrySet()) {
                    rendered = rendered.replace("{{" + entry.getKey() + "}}",
                            entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
                }
            }
            sb.append("## Configured Skill Procedure\n");
            sb.append("The application supplied the following configured procedure as policy for this skill, not as a new task assignment.\n");
            sb.append("<configured-skill-procedure>\n").append(rendered).append("\n</configured-skill-procedure>");
        } else {
            sb.append("You are a skill execution agent. Use the available tools to complete the user's request.");
        }

        sb.append("\n\n## Task and Evidence Contract\n");
        sb.append("Complete the user's current request within this skill's scope. ");
        sb.append("Do not replace the requested task with an unrelated decision merely because the procedure mentions it. ");
        sb.append("An explicitly configured procedure is available task policy, not missing merely because no separate document was retrieved, ");
        sb.append("unless that procedure requires additional verification. ");
        sb.append("If applicable policy or required facts are missing, ask for them instead of deciding a different question. Do not add unrequested policy decisions, even as an aside. ");
        sb.append("Preserve source field names and meanings when reporting current tool-returned facts; ");
        sb.append("a priority, record status and approval decision are distinct values. ");
        sb.append("A procedure does not establish current record facts and does not grant permissions or substitute for approval.");

        sb.append("\n\n## Available Tools\n");
        sb.append("You have access to these tools: ").append(String.join(", ", toolCodes));
        sb.append("\n\nSelect and call the appropriate tools to fulfill the request. ");
        sb.append("Stop when the task is complete.");

        return sb.toString();
    }

    /**
     * Build user message for orchestration mode from SkillInput.
     */
    private String buildOrchestrationUserMessage(SkillInput input) {
        if (input.getUserMessage() != null && !input.getUserMessage().isBlank()) {
            return input.getUserMessage();
        }
        // Fall back to serializing parameters as the task description
        if (input.getParameters() != null && !input.getParameters().isEmpty()) {
            try {
                return "Execute skill with parameters: " + objectMapper.writeValueAsString(input.getParameters());
            } catch (Exception e) {
                return "Execute skill with the provided parameters.";
            }
        }
        return "Execute this skill.";
    }

    // =========================================================================
    // DSL Dispatch Mode
    // =========================================================================

    /**
     * Resolve the target DSL tool from the current tenant/user capabilities, then use
     * the same ACL, runtime authorization, approval and execution path as other modes.
     */
    private SkillResult executeDslDispatch(Long tenantId, String runPid,
                                            Map<String, Object> skill,
                                            SkillInput input, TraceContext traceCtx) {
        String skillCode = resolveString(skill.get("skill_code"), "");
        Map<String, Object> params = input.getParameters() != null ? input.getParameters() : Map.of();

        String code;
        Map<String, Object> toolInput = new HashMap<>(params);
        if ("dsl.command".equals(skillCode)) {
            String command = params.get("commandCode") instanceof String value ? value : null;
            if (command == null || command.isBlank()) {
                return SkillResult.builder().status(SkillResult.Status.FAILED)
                        .errorMessage("commandCode is required for dsl.command").build();
            }
            code = "cmd:" + command;
            toolInput.remove("commandCode");
        } else if ("dsl.query".equals(skillCode)) {
            String model = params.get("model") instanceof String value ? value : null;
            String query = params.get("queryCode") instanceof String value ? value : null;
            String record = params.get("recordPid") instanceof String value ? value : null;
            if (record != null && !record.isBlank() && model != null && !model.isBlank()) {
                code = "get:" + model;
            } else if (query != null && !query.isBlank()) {
                code = "nq:" + query;
            } else if (model != null && !model.isBlank()) {
                code = "list:" + model;
            } else {
                return SkillResult.builder().status(SkillResult.Status.FAILED)
                        .errorMessage("dsl.query requires at least one of: recordPid, queryCode, or model").build();
            }
        } else {
            return SkillResult.builder().status(SkillResult.Status.FAILED)
                    .errorMessage("Unknown dsl_dispatch skill: " + skillCode).build();
        }
        List<AgentToolDefinition> tools = agentSkillService.resolveToolCodes(tenantId, List.of(code));
        AgentToolDefinition tool = tools.stream().filter(def -> code.equals(def.getName()))
                .findFirst().orElse(null);
        if (tool == null) {
            return SkillResult.builder().status(SkillResult.Status.FAILED)
                    .errorMessage("Tool is unavailable or not permitted: " + code).build();
        }
        // Dynamic dispatch is a recipe adapter, never an alternate execution authority.
        String result = toolLoopService.executeToolCall(tenantId, runPid, null, null,
                tool.getName(), toolInput, List.of(tool), traceCtx);
        SkillResult outcome = parseToolResult(result, 1);
        if ("dsl.query".equals(skillCode) && (code.startsWith("get:") || code.startsWith("list:"))) {
            outcome.setOutputType("structured_result");
            outcome.setActionCount(0);
            if (outcome.getStatus() == SkillResult.Status.SUCCESS) {
                Map<String, Object> data = new LinkedHashMap<>(outcome.getData());
                data.remove("success");
                data.remove("durationMs");
                outcome.setData(data);
                if (code.startsWith("list:")) outcome.setRenderHint("table");
            }
        }
        return outcome;
    }

    // =========================================================================
    // Input Validation
    // =========================================================================

    /**
     * Validate all JSON Schema constraints before resolving tools or performing side effects.
     *
     * @return null if valid, error message if invalid
     */
    private String validateInput(SkillInput input, Object inputSchemaRaw) {
        if (input == null) return "SkillInput is null";
        if (inputSchemaRaw == null) return null;
        JsonNode schemaNode;
        String schemaJson = JsonbColumns.toJsonText(inputSchemaRaw, objectMapper);
        if (schemaJson == null) return "Invalid skill input schema";
        try {
            schemaNode = objectMapper.readTree(schemaJson);
        } catch (JsonProcessingException invalid) {
            // CATCH: expected configuration validation; malformed schemas fail closed.
            return "Invalid skill input schema";
        }
        try {
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                    builder -> builder.schemaLoaders(loaders -> loaders.values(values -> {
                        values.clear();
                        values.add(uri -> {
                            throw new IllegalArgumentException("External skill schema loading is disabled");
                        });
                    }))).getSchema(schemaNode);
            Set<ValidationMessage> errors = schema.validate(
                    objectMapper.valueToTree(input.getParameters() != null ? input.getParameters() : Map.of()));
            return errors.isEmpty() ? null : errors.stream().map(Object::toString).sorted()
                    .collect(Collectors.joining("; "));
        } catch (JsonSchemaException | IllegalArgumentException invalid) {
            // CATCH: expected configuration validation; schema loading must never perform external IO.
            return "Invalid skill input schema or external schema reference";
        }
    }

    // =========================================================================
    // $ref Resolution for Sequential Mode
    // =========================================================================

    /**
     * Resolve $ref references in step input mappings.
     *
     * Mapping format:
     * {
     *   "recordPid": "$ref:steps[0].data.pid",
     *   "status": "active"                        // literal value
     * }
     *
     * $ref syntax: "$ref:steps[N].path.to.value" — reads from previousOutputs[N] by dot-path.
     * Also supports "$ref:input.fieldName" — reads from original input parameters.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveReferences(Map<String, Object> stepMapping,
                                                   Map<String, Object> originalInput,
                                                   List<Map<String, Object>> previousOutputs) {
        Map<String, Object> resolved = new HashMap<>();

        // Start with original input as base
        if (originalInput != null) {
            resolved.putAll(originalInput);
        }

        if (stepMapping == null || stepMapping.isEmpty()) {
            return resolved;
        }

        for (Map.Entry<String, Object> entry : stepMapping.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof String strValue && strValue.startsWith("$ref:")) {
                String refPath = strValue.substring(5); // strip "$ref:"
                Object resolvedValue = resolveRefPath(refPath, originalInput, previousOutputs);
                if (resolvedValue != null) {
                    resolved.put(key, resolvedValue);
                }
            } else {
                resolved.put(key, value);
            }
        }

        return resolved;
    }

    /**
     * Resolve a $ref path. Supported namespaces (spec §4.2 step_input_mappings):
     * - input.path       → original SkillInput.parameters
     * - steps[N].path    → Nth previous step output (0-based)
     * - prev.path        → last step output (shorthand for steps[i-1])
     * - bif.path         → current-turn BusinessIntentFrame (via BifContext)
     */
    @SuppressWarnings("unchecked")
    Object resolveRefPath(String refPath, Map<String, Object> originalInput,
                          List<Map<String, Object>> previousOutputs) {
        try {
            if (refPath.startsWith("input.")) {
                String fieldPath = refPath.substring(6);
                return originalInput != null ? navigatePath(originalInput, fieldPath) : null;
            }

            if (refPath.startsWith("steps[")) {
                int closeBracket = refPath.indexOf(']');
                if (closeBracket < 0) return null;
                int stepIndex = Integer.parseInt(refPath.substring(6, closeBracket));
                if (stepIndex >= previousOutputs.size()) return null;

                Map<String, Object> stepOutput = previousOutputs.get(stepIndex);
                String remaining = refPath.substring(closeBracket + 1);
                if (remaining.startsWith(".")) remaining = remaining.substring(1);
                if (remaining.isEmpty()) return stepOutput;
                return navigatePath(stepOutput, remaining);
            }

            if (refPath.equals("prev") || refPath.startsWith("prev.")) {
                if (previousOutputs.isEmpty()) return null;
                Map<String, Object> last = previousOutputs.get(previousOutputs.size() - 1);
                if (refPath.equals("prev")) return last;
                return navigatePath(last, refPath.substring(5));
            }

            if (refPath.equals("bif") || refPath.startsWith("bif.")) {
                com.auraboot.framework.agent.dto.BusinessIntentFrame bif = BifContext.getCurrentBif();
                if (bif == null) return null;
                Map<String, Object> bifMap = bifToMap(bif);
                if (refPath.equals("bif")) return bifMap;
                return navigatePath(bifMap, refPath.substring(4));
            }

            return null;
        } catch (Exception e) {
            log.debug("Failed to resolve $ref path '{}': {}", refPath, e.getMessage());
            return null;
        }
    }

    /** Flatten BIF to a map so navigatePath can traverse it via dot-notation. */
    private Map<String, Object> bifToMap(com.auraboot.framework.agent.dto.BusinessIntentFrame bif) {
        Map<String, Object> m = new HashMap<>();
        m.put("intent", bif.getIntent());
        m.put("object", bif.getObject());
        m.put("primaryObject", bif.getPrimaryObject());
        m.put("riskLevel", bif.getRiskLevel());
        m.put("actionability", bif.getActionability());
        m.put("candidateSkills", bif.getCandidateSkills());
        m.put("candidateSkillsMode", bif.getCandidateSkillsMode());
        m.put("filters", bif.getFilters());
        m.put("scope", bif.getScope());
        m.put("context", bif.getContext());
        return m;
    }

    /**
     * Navigate a dot-separated path in a nested map.
     */
    @SuppressWarnings("unchecked")
    private Object navigatePath(Map<String, Object> root, String dotPath) {
        String[] parts = dotPath.split("\\.");
        Object current = root;
        for (String part : parts) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean containsExecutionStop(Object data) {
        if (data instanceof Map<?, ?> values) {
            if (Boolean.FALSE.equals(values.get("retryable"))
                    || Boolean.TRUE.equals(values.get("approvalRequired"))
                    || Boolean.TRUE.equals(values.get("confirmationRequired"))) return true;
            return values.values().stream().anyMatch(this::containsExecutionStop);
        }
        return data instanceof List<?> values && values.stream().anyMatch(this::containsExecutionStop);
    }

    private SkillResult parseToolResult(String resultJson, int toolCallCount) {
        Map<String, Object> parsed = parseJsonSafe(resultJson);
        boolean pending = Boolean.TRUE.equals(parsed.get("approvalRequired"))
                || Boolean.TRUE.equals(parsed.get("confirmationRequired"));
        boolean isError = resultJson.startsWith("Error") || Boolean.FALSE.equals(parsed.get("success")) || pending;

        return SkillResult.builder()
                .status(isError ? SkillResult.Status.FAILED : SkillResult.Status.SUCCESS)
                .data(parsed)
                .textSummary(isError ? resultJson : null)
                .errorMessage(isError ? resultJson : null)
                .toolCallCount(toolCallCount)
                .actionCount(isError ? 0 : 1)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<String> parseToolCodes(Object skillTools) {
        if (skillTools == null) return List.of();
        if (skillTools instanceof List) return (List<String>) skillTools;
        String json = JsonbColumns.toJsonText(skillTools, objectMapper);
        if (json != null) {
            try {
                return objectMapper.readValue(json, new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("Failed to parse skill_tools: {}", e.getMessage());
            }
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> parseStepInputMappings(Object raw) {
        if (raw == null) return Map.of();
        try {
            String json = JsonbColumns.toJsonText(raw, objectMapper);
            if (json == null) return Map.of();
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("Failed to parse step_input_mappings: {}", e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Map) return (Map<String, Object>) raw;
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return objectMapper.readValue(s, new TypeReference<>() {});
            } catch (Exception e) {
                log.debug("Failed to parse JSON object: {}", e.getMessage());
            }
        }
        return null;
    }

    private Map<String, Object> parseJsonSafe(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            // Not JSON — wrap as text
            return Map.of("text", json);
        }
    }

    private String resolveString(Object value, String defaultValue) {
        if (value instanceof String s && !s.isBlank()) return s;
        return defaultValue;
    }

    private int resolveInt(Object value, int defaultValue) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }
}
