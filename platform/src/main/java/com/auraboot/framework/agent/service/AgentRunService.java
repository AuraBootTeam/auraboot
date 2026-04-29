package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.dto.AgentPlanStep;
import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.memory.SessionEndedEvent;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.agent.trace.SpanContext;
import com.auraboot.framework.agent.trace.TraceContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.meta.dto.NamedQueryTestRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunService {

    private final AgentProperties agentProperties;
    private final ToolProviderRegistry toolProviderRegistry;
    private final AgentObservationService observationService;
    private final AgentApprovalGateService approvalGate;
    private final AiTraceService aiTraceService;
    private final ToolLoopService toolLoopService;
    private final DynamicDataMapper dynamicDataMapper;
    private final CommandExecutor commandExecutor;
    private final NamedQueryService namedQueryService;
    private final ObjectMapper objectMapper;
    private final LlmProviderFactory providerFactory;
    private final AgentMemoryService memoryService;
    private final ContextWindowManager contextWindowManager;
    private final PlanService planService;
    private final StepLoopService stepLoopService;
    private final RunLifecycleService runLifecycleService;
    private final GroundingService groundingService;
    private final AgentSkillService skillService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * User Soul Profile grounding reader (plan §5.5 / PR-77).
     * Optional so existing tests + legacy contexts without the bean don't break.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserSoulProfileReader userSoulProfileReader;

    private static final int MAX_HALLUCINATION_COUNT = 3;
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    // Thread-local trace context for tool span tracking within a run
    private static final ThreadLocal<TraceContext> currentTraceCtx = new ThreadLocal<>();

    /**
     * Async fire-and-forget entry point for IM event listeners, the
     * {@code AgentScheduleService} cron driver, and parent-task dispatch
     * (see {@link #dispatchChildTasks}). Establishes a system tenant
     * context (no user) and delegates to {@link #executeTaskSync}; the
     * sync return value is intentionally discarded — callers that need
     * the outcome should call {@code executeTaskSync} directly (Q-C3.2=β
     * "sync core + async at adapter").
     */
    @Async
    public void executeTask(Long tenantId, String taskPid, String agentCode) {
        MetaContext.setSystemTenantContext(tenantId);
        try {
            executeTaskSync(tenantId, taskPid, agentCode, null);
        } finally {
            MetaContext.clear();
        }
    }

    @Async
    public void executeTaskWithResume(Long tenantId, String taskPid, String agentCode, String resumeFromRunPid) {
        MetaContext.setSystemTenantContext(tenantId);
        try {
            executeTaskSync(tenantId, taskPid, agentCode, resumeFromRunPid);
        } finally {
            MetaContext.clear();
        }
    }

    /**
     * Synchronous entry point for the ACP run loop. Returns a {@link RunOutcome}
     * describing the terminal state so a caller in a sync context (e.g.
     * {@code ConversationTurnService.runTurn} dispatching to ACP under Q-C3.1=A
     * per-turn task model) can map it to its own outcome type without polling
     * the database.
     *
     * <p>Phase C.3a contract: this method does NOT touch {@link MetaContext} —
     * the caller is responsible for binding tenant / user context appropriately
     * (HTTP requests get it from the auth interceptor; the {@code @Async}
     * wrappers above bind a system context for non-user-driven callers).
     *
     * <p>All exceptions are caught and converted into {@link RunOutcome.Failed}
     * or {@link RunOutcome.PendingApproval}; this method never throws. Side
     * effects ({@code ab_agent_run} / {@code ab_agent_action} writes,
     * observation events, trace, memory writeback, child-task dispatch) are
     * preserved exactly as before — only the return type changes.
     *
     * @param tenantId         non-null tenant
     * @param taskPid          non-null {@code ab_agent_task.pid}
     * @param agentCode        non-null {@code ab_agent_definition.agent_code}
     * @param resumeFromRunPid optional previous {@code ab_agent_run.pid} when
     *                         resuming after an approval gate; {@code null}
     *                         for a fresh run
     */
    public RunOutcome executeTaskSync(Long tenantId, String taskPid, String agentCode,
                                      String resumeFromRunPid) {
        if (!agentProperties.isEnabled()) {
            log.warn("Agent runtime is disabled, skipping task: {}", taskPid);
            return new RunOutcome.Skipped("Agent runtime disabled");
        }

        String runPid = UniqueIdGenerator.generate();
        LocalDateTime startedAt = LocalDateTime.now();

        // Create trace for observability (non-blocking — failures logged and ignored)
        TraceContext traceCtx = null;
        try {
            traceCtx = aiTraceService.createTrace(tenantId, runPid,
                    "agent:" + agentCode + " task:" + taskPid,
                    MetaContext.getCurrentUserId(),
                    Map.of("agentCode", agentCode, "taskPid", taskPid));
        } catch (Exception e) {
            log.debug("Failed to create trace for run {}: {}", runPid, e.getMessage());
        }
        currentTraceCtx.set(traceCtx);

        try {
            Map<String, Object> agentDef = loadAgentDefinition(tenantId, agentCode);
            String providerCode = resolveProviderCode(agentDef);
            String model = resolveModel(agentDef, providerCode);

            // Create run record first (even if API key missing, we want the record)
            runLifecycleService.createRunRecord(tenantId, runPid, taskPid, agentCode, model, startedAt);

            // If resuming, link to original run
            if (resumeFromRunPid != null) {
                dynamicDataMapper.update("ab_agent_run",
                        Map.of("resumed_from", resumeFromRunPid, "updated_at", LocalDateTime.now()),
                        Map.of("pid", runPid));
            }

            // Concurrency control: check active runs for this agent
            int maxConcurrent = agentDef != null && agentDef.get("max_concurrent_runs") != null
                    ? ((Number) agentDef.get("max_concurrent_runs")).intValue()
                    : RunLifecycleService.DEFAULT_MAX_CONCURRENT_RUNS;
            int activeRuns = runLifecycleService.countActiveRuns(tenantId, agentCode, runPid);
            if (activeRuns >= maxConcurrent) {
                String concurrencyMsg = "Concurrency limit: " + activeRuns + "/" + maxConcurrent + " runs active";
                log.info("Agent '{}' concurrency limit reached ({}/{}), queuing run {}",
                        agentCode, activeRuns, maxConcurrent, runPid);
                dynamicDataMapper.update("ab_agent_run",
                        Map.of("run_status", "queued", "error_message", concurrencyMsg,
                                "updated_at", LocalDateTime.now()),
                        Map.of("pid", runPid));
                return new RunOutcome.Failed(runPid, concurrencyMsg);
            }

            // Resolve provider with fallback chain
            LlmProvider provider = null;
            LlmProviderFactory.ProviderConfig config = null;
            String resolvedProviderCode = providerCode;

            List<String> providerChain = buildProviderChain(agentDef, providerCode);
            for (String pc : providerChain) {
                LlmProviderFactory.ProviderConfig candidateConfig = providerFactory.resolveConfig(tenantId, pc);
                if (candidateConfig != null && candidateConfig.getApiKey() != null && !candidateConfig.getApiKey().isBlank()) {
                    provider = providerFactory.getProvider(pc);
                    config = candidateConfig;
                    resolvedProviderCode = pc;
                    if (!pc.equals(providerCode)) {
                        log.info("Preferred provider '{}' not configured, falling back to '{}'", providerCode, pc);
                        model = resolveModel(agentDef, pc, true);  // force fallback: use new provider's default model
                    }
                    break;
                }
            }

            if (config == null) {
                String noProviderMsg = "No LLM provider configured. Tried: " + providerChain +
                        ". Add API key via Settings \u2192 Cloud Config \u2192 LLM.";
                runLifecycleService.failRun(tenantId, runPid, taskPid, startedAt, noProviderMsg);
                return new RunOutcome.Failed(runPid, noProviderMsg);
            }

            if (agentDef == null) {
                String agentMissingMsg = "Agent not found: " + agentCode;
                runLifecycleService.failRun(tenantId, runPid, taskPid, startedAt, agentMissingMsg);
                return new RunOutcome.Failed(runPid, agentMissingMsg);
            }

            Map<String, Object> task = loadTask(tenantId, taskPid);
            if (task == null) {
                String taskMissingMsg = "Task not found: " + taskPid;
                runLifecycleService.failRun(tenantId, runPid, taskPid, startedAt, taskMissingMsg);
                return new RunOutcome.Failed(runPid, taskMissingMsg);
            }

            String systemPrompt = buildSystemPrompt(agentDef, task, tenantId, agentCode);
            String userMessage = buildUserMessage(task);

            // Load tools via ToolProviderRegistry
            Integer maxTools = agentDef.get("max_tools") != null
                    ? ((Number) agentDef.get("max_tools")).intValue() : null;
            int toolLimit = maxTools != null && maxTools > 0 ? maxTools : 20;

            // D1 Grounding: compile user message → BusinessIntentFrame
            com.auraboot.framework.agent.dto.BusinessIntentFrame bif = groundingService.ground(
                    tenantId, userMessage,
                    GroundingService.GroundingContext.builder().build());

            // Build discovery context from BIF
            ToolDiscoveryContext discoveryCtx = ToolDiscoveryContext.builder()
                    .tenantId(tenantId)
                    .userId(MetaContext.exists() ? MetaContext.getCurrentUserId() : null)
                    .agentCode(agentCode)
                    .modelHint(bif != null ? bif.getObject() : null)
                    .intentHint(bif != null ? bif.getIntent() : null)
                    .maxResults(toolLimit)
                    .build();

            // Quality gate: if D1 quality insufficient, discover all tools
            String qualityIssue = groundingService.checkQualityGate(bif);
            List<AgentToolDefinition> tools;
            if (qualityIssue != null) {
                log.info("D1 quality gate triggered: {}, discovering all tools", qualityIssue);
                tools = toAgentToolDefinitions(toolProviderRegistry.discoverAll(discoveryCtx));
            } else {
                // Use BIF candidateSkills to narrow tool selection
                List<String> candidateSkills = bif.getCandidateSkills();
                if (candidateSkills != null && !candidateSkills.isEmpty()) {
                    // Load tools from candidate skills
                    List<AgentToolDefinition> skillTools = new ArrayList<>();
                    for (String skillCode : candidateSkills) {
                        skillTools.addAll(skillService.resolveSkillTools(tenantId, skillCode));
                    }
                    // If skill tools found, use them; otherwise discover all
                    tools = skillTools.isEmpty()
                            ? toAgentToolDefinitions(toolProviderRegistry.discoverAll(discoveryCtx))
                            : skillTools;
                } else {
                    tools = toAgentToolDefinitions(toolProviderRegistry.discoverAll(discoveryCtx));
                }
            }
            log.info("Agent {} tools: selected={}, d1={}, provider={}, model={}",
                    agentCode, tools.size(),
                    qualityIssue == null ? "active" : "fallback", resolvedProviderCode, model);

            // Update run record with resolved model and provider (may differ from initial due to fallback)
            int timeoutSeconds = agentDef.get("execution_timeout_seconds") != null
                    ? ((Number) agentDef.get("execution_timeout_seconds")).intValue()
                    : DEFAULT_TIMEOUT_SECONDS;
            LocalDateTime timeoutAt = startedAt.plusSeconds(timeoutSeconds);
            dynamicDataMapper.update("ab_agent_run",
                    Map.of("timeout_at", timeoutAt, "run_model", model, "updated_at", LocalDateTime.now()),
                    Map.of("pid", runPid));

            // Generate or load plan
            List<AgentPlanStep> plan;
            int startStep = 0;

            if (resumeFromRunPid != null) {
                plan = planService.loadPlanFromRun(resumeFromRunPid);
                startStep = planService.findFirstPendingStep(plan);
                boolean resumedAwaitingApproval = startStep < plan.size()
                        && plan.get(startStep).getStatus() == AgentPlanStep.StepStatus.AWAITING_APPROVAL;
                log.info("Resuming from step {} of {} (previous run: {})", startStep, plan.size(), resumeFromRunPid);
                if (resumedAwaitingApproval) {
                    log.info("Skipping approval gate for resumed step {} on run {}", startStep, runPid);
                }
            } else {
                plan = planService.generatePlan(provider, config, model, systemPrompt, userMessage, tools);
                log.info("Generated execution plan with {} steps", plan.size());
                // Validate plan: check tool codes, enforce risk-based approval
                planService.validatePlan(plan, tools, runPid);
            }

            // Persist initial plan
            planService.persistPlan(runPid, plan, startStep);

            // Start heartbeat to keep run alive (updated_at refreshed every 30s)
            runLifecycleService.startHeartbeat(runPid);
            AgentLoopResult result;
            try {
                // Execute plan steps (replaces executeAgentLoop for multi-step plans)
                boolean skipApprovalForResumedStep = resumeFromRunPid != null
                        && startStep < plan.size()
                        && plan.get(startStep).getStatus() == AgentPlanStep.StepStatus.AWAITING_APPROVAL;
                result = stepLoopService.executePlanSteps(plan, startStep, tenantId, runPid, taskPid, agentCode,
                        systemPrompt, userMessage, tools, agentDef, provider, config, traceCtx,
                        skipApprovalForResumedStep);

                // Persist final plan state
                planService.persistPlan(runPid, plan, plan.size());

                completeRun(tenantId, runPid, taskPid, startedAt, result, model);

                observationService.publish(tenantId, "run_completed", agentCode, "agent_run", runPid,
                        Map.of("task_id", taskPid, "status", result.success ? "success" : "failed",
                               "provider", resolvedProviderCode, "model", model,
                               "input_tokens", result.totalInputTokens, "output_tokens", result.totalOutputTokens,
                               "total_cost", result.totalCost));

                // End trace on success
                try { aiTraceService.endTrace(traceCtx, result.lastResponse, result.success ? "success" : "failed"); }
                catch (Exception traceEx) { log.debug("Failed to end trace for run {}: {}", runPid, traceEx.getMessage()); }
            } finally {
                runLifecycleService.stopHeartbeat(runPid);
            }

            // Map AgentLoopResult to RunOutcome — success carries the LLM final
            // response + token / cost telemetry so the chokepoint can attach
            // them to its outbound metric / memory rows; non-success here means
            // the plan loop reported a soft failure (no exception, but a step
            // could not complete) which is still a Failed outcome at the
            // chokepoint level.
            if (result.success) {
                return new RunOutcome.Success(runPid, result.lastResponse,
                        result.totalInputTokens, result.totalOutputTokens, result.totalCost);
            }
            return new RunOutcome.Failed(runPid, "Plan execution did not reach success terminal state");

        } catch (AgentApprovalPendingException e) {
            log.info("Run {} paused for approval: {}", runPid, e.getMessage());
            Map<String, Object> runUpdate = new HashMap<>();
            runUpdate.put("run_status", "pending");
            runUpdate.put("error_message", e.getMessage());
            runUpdate.put("updated_at", LocalDateTime.now());
            dynamicDataMapper.update("ab_agent_run", runUpdate, Map.of("pid", runPid));
            // End trace as pending
            try { aiTraceService.endTrace(traceCtx, null, "pending"); }
            catch (Exception traceEx) { log.debug("Failed to end trace for run {}: {}", runPid, traceEx.getMessage()); }
            // Task stays IN_PROGRESS — will be resumed after approval
            return new RunOutcome.PendingApproval(runPid, e.getMessage());
        } catch (Exception e) {
            log.error("Agent execution failed: task={}, agent={}, error={}", taskPid, agentCode, e.getMessage(), e);
            runLifecycleService.failRun(tenantId, runPid, taskPid, startedAt, e.getMessage());
            observationService.publish(tenantId, "run_failed", agentCode, "agent_run", runPid,
                    Map.of("task_id", taskPid, "error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            // End trace with error
            try { aiTraceService.endTraceWithError(traceCtx, e.getMessage()); }
            catch (Exception traceEx) { log.debug("Failed to end trace for run {}: {}", runPid, traceEx.getMessage()); }
            // Fire SessionEndedEvent on FAILED terminal state so any L1
            // memories written before the failure still get promotion
            // evaluation (otherwise they wait for the orphan cron and
            // pollute OrphanBacklogGrowing alerts).
            publishSessionEndedIfApplicable(tenantId, runPid, agentCode,
                    SessionEndedEvent.TerminalOutcome.FAILED);
            return new RunOutcome.Failed(runPid,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            currentTraceCtx.remove();
        }
    }


    private boolean attemptReplan(List<AgentPlanStep> plan, int failedStepIndex, String errorMessage,
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

            String jsonStr = planService.extractJsonArray(content);
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
    // Provider & Model resolution
    // =========================================================================

    private String resolveProviderCode(Map<String, Object> agentDef) {
        if (agentDef == null) return "anthropic";

        // 1. Explicit provider in guardrails
        String guardrailsJson = (String) agentDef.get("guardrails");
        if (guardrailsJson != null && !guardrailsJson.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> guardrails = objectMapper.readValue(guardrailsJson, Map.class);
                String provider = (String) guardrails.get("provider");
                if (provider != null && !provider.isBlank()) return provider;
            } catch (Exception ignored) {}
        }

        // 2. Dynamic resolution from model name via CloudConfig + heuristics
        String model = (String) agentDef.get("model");
        if (model != null && !model.isBlank()) {
            String matched = providerFactory.resolveProviderByModel(model);
            if (matched != null) return matched;
        }

        return "anthropic"; // default
    }

    private String resolveModel(Map<String, Object> agentDef, String providerCode) {
        return resolveModel(agentDef, providerCode, false);
    }

    /**
     * Resolve model name for a provider.
     * When forceFallback is true (provider changed during fallback), ignore agent def model
     * because the model name (e.g. "claude-sonnet-4-6") may not be valid for the new provider.
     */
    private String resolveModel(Map<String, Object> agentDef, String providerCode, boolean forceFallback) {
        if (!forceFallback && agentDef != null) {
            String model = (String) agentDef.get("model");
            if (model != null && !model.isBlank()) return model;
        }
        return providerFactory.getDefaultModel(providerCode);
    }

    @SuppressWarnings("unchecked")
    private List<String> buildProviderChain(Map<String, Object> agentDef, String preferredProvider) {
        List<String> chain = new ArrayList<>();
        chain.add(preferredProvider);

        if (agentDef != null) {
            String guardrailsJson = (String) agentDef.get("guardrails");
            if (guardrailsJson != null && !guardrailsJson.isBlank()) {
                try {
                    Map<String, Object> guardrails = objectMapper.readValue(guardrailsJson, Map.class);
                    Object fallbacks = guardrails.get("fallbackProviders");
                    if (fallbacks instanceof List<?> list) {
                        for (Object item : list) {
                            String fb = String.valueOf(item);
                            if (!chain.contains(fb)) chain.add(fb);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // Add all configured (enabled) providers as fallbacks
        try {
            Long tenantId = MetaContext.getCurrentTenantId();
            List<LlmProviderFactory.ProviderInfo> configured = providerFactory.listConfiguredProviders(tenantId);
            for (LlmProviderFactory.ProviderInfo info : configured) {
                if (!chain.contains(info.getProviderCode())) {
                    chain.add(info.getProviderCode());
                }
            }
        } catch (Exception e) {
            log.debug("Failed to list configured providers for fallback chain: {}", e.getMessage());
        }

        // Always add system default as last resort
        if (!chain.contains("anthropic")) chain.add("anthropic");

        return chain;
    }

    // =========================================================================
    // Run/Task lifecycle (delegated to RunLifecycleService)
    // =========================================================================

    /**
     * Complete a run: update records + dispatch child tasks + update mission + save memory.
     * Record updates are delegated to RunLifecycleService; dispatch stays here to avoid circular dependency.
     */
    private void completeRun(Long tenantId, String runPid, String taskPid, LocalDateTime startedAt,
                              AgentLoopResult result, String model) {
        boolean success = runLifecycleService.completeRunRecord(tenantId, runPid, taskPid, startedAt, result, model);

        // Dispatch child tasks when parent completes successfully
        if (success) {
            dispatchChildTasks(tenantId, taskPid);

            // For sequential execution: check if this task's parent has more children to dispatch
            Map<String, Object> completedTask = loadTask(tenantId, taskPid);
            if (completedTask != null) {
                String parentId = (String) completedTask.get("parent_id");
                if (parentId != null && !parentId.isBlank()) {
                    dispatchChildTasks(tenantId, parentId);
                }

                // Update mission progress
                String missionId = (String) completedTask.get("mission_id");
                if (missionId != null && !missionId.isBlank()) {
                    runLifecycleService.updateMissionProgress(tenantId, missionId);
                }
            }

            // Save-back: store a summary memory from this successful run
            Map<String, Object> task = loadTask(tenantId, taskPid);
            String agentCode = task != null ? (String) task.get("assignee_id") : null;
            String taskTitle = task != null ? (String) task.get("title") : null;
            if (agentCode != null) {
                Map<String, Object> agentDef = loadAgentDefinition(tenantId, agentCode);
                String providerCode = resolveProviderCode(agentDef);
                String memModel = resolveModel(agentDef, providerCode);
                runLifecycleService.saveRunMemory(tenantId, runPid, taskPid, result,
                        agentCode, taskTitle, providerCode, memModel);

                publishSessionEndedIfApplicable(tenantId, runPid, agentCode,
                        SessionEndedEvent.TerminalOutcome.SUCCEEDED);
            }
        }
    }

    /**
     * Shared terminal-state helper — publishes exactly one
     * {@link SessionEndedEvent} per run regardless of which terminal path
     * ({@code completeRun} / cancel / fail) reaches it first.
     *
     * <p>Design: {@code docs/plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md §4.1 / §6}.
     * Cancelled and failed runs fire the event too so any L1
     * {@code category='session'} rows they wrote before termination still
     * reach the promoter — otherwise they would wait for the orphan cron and
     * pollute {@code OrphanBacklogGrowing} alert noise.
     *
     * <p>Idempotency: uses {@code ab_agent_run.session_ended_published_at} as
     * an atomic guard. The first caller flips NULL -> NOW() and publishes;
     * any second caller (racy terminal-state handoff) sees non-null and
     * returns without publishing. No fallback / retry — a DB error surfaces
     * to the caller.
     *
     * <p>Called from both transactional success path and non-transactional
     * exception catch; keep this method free of {@code @Transactional} so it
     * does not interfere with either surrounding semantics.
     *
     * @param tenantId  non-null tenant
     * @param runPid    non-null run pid
     * @param agentCode non-null agent code (used by event listeners for metrics)
     * @param outcome   terminal outcome label for metrics
     */
    void publishSessionEndedIfApplicable(Long tenantId, String runPid, String agentCode,
                                         SessionEndedEvent.TerminalOutcome outcome) {
        if (tenantId == null || runPid == null || agentCode == null || outcome == null) {
            // Strict — callers must pass valid values. Missing agentCode on
            // fail paths means the task row was never loaded, in which case
            // there are no L1 memories tied to this runId anyway.
            log.debug("publishSessionEndedIfApplicable: skipping, null field "
                            + "(tenantId={} runPid={} agentCode={} outcome={})",
                    tenantId, runPid, agentCode, outcome);
            return;
        }
        boolean claimed = runLifecycleService.markSessionEndedPublished(runPid);
        if (!claimed) {
            log.debug("SessionEndedEvent already published for run {}, skipping ({})",
                    runPid, outcome);
            return;
        }
        String userId = resolveCurrentUserId();
        eventPublisher.publishEvent(new SessionEndedEvent(
                tenantId, runPid, agentCode, userId, outcome));
    }

    /**
     * Best-effort current-user resolver for the SessionEndedEvent payload.
     * Returns null when no user context is bound (system/cron run) — the
     * promoter handles null userId without fallback.
     */
    private String resolveCurrentUserId() {
        Long uid = MetaContext.getCurrentUserId();
        return uid == null ? null : uid.toString();
    }

    /**
     * Dispatch child tasks after parent completes. Kept in AgentRunService because it calls
     * executeTask() which would create a circular dependency if placed in RunLifecycleService.
     */
    @SuppressWarnings("unchecked")
    private void dispatchChildTasks(Long tenantId, String parentTaskPid) {
        // Load parent task to check execution_mode
        Map<String, Object> parentTask = loadTask(tenantId, parentTaskPid);
        String executionMode = "parallel"; // default
        if (parentTask != null && parentTask.get("agent_config") != null) {
            try {
                String configJson = String.valueOf(parentTask.get("agent_config"));
                Map<String, Object> config = objectMapper.readValue(configJson, Map.class);
                if ("sequential".equals(config.get("executionMode"))) {
                    executionMode = "sequential";
                }
            } catch (Exception ignored) {}
        }

        String sql = "SELECT pid, assignee_id FROM ab_agent_task " +
                "WHERE tenant_id = #{params.tenantId} " +
                "AND parent_id = #{params.parentPid} " +
                "AND task_status = 'todo' " +
                "AND assignee_type = 'agent' " +
                "AND deleted_flag = FALSE " +
                "ORDER BY task_priority ASC, created_at ASC";
        List<Map<String, Object>> children = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "parentPid", parentTaskPid));

        if (children.isEmpty()) {
            return;
        }

        if ("sequential".equals(executionMode)) {
            // Only dispatch the first child; the rest will be dispatched when it completes
            Map<String, Object> first = children.get(0);
            String childPid = (String) first.get("pid");
            String childAgentCode = (String) first.get("assignee_id");
            log.info("Sequential dispatch: first child task {} for parent {}", childPid, parentTaskPid);
            try {
                executeTask(tenantId, childPid, childAgentCode);
            } catch (Exception e) {
                log.error("Failed to dispatch child task {}: {}", childPid, e.getMessage());
            }
            observationService.publish(tenantId, "child_task_dispatched", childAgentCode, "agent_task", parentTaskPid,
                    Map.of("child_pid", childPid, "mode", "sequential", "remaining", children.size() - 1));
        } else {
            // Parallel: dispatch all children
            log.info("Parallel dispatch: {} child tasks for parent {}", children.size(), parentTaskPid);
            for (Map<String, Object> child : children) {
                String childPid = (String) child.get("pid");
                String childAgentCode = (String) child.get("assignee_id");
                try {
                    executeTask(tenantId, childPid, childAgentCode);
                } catch (Exception e) {
                    log.error("Failed to dispatch child task {}: {}", childPid, e.getMessage());
                }
            }
            observationService.publish(tenantId, "child_tasks_dispatched", null, "agent_task", parentTaskPid,
                    Map.of("child_count", children.size(), "mode", "parallel",
                           "child_pids", children.stream().map(c -> (String) c.get("pid")).toList()));
        }
    }

    // =========================================================================
    // Data loading helpers
    // =========================================================================

    private Map<String, Object> loadAgentDefinition(Long tenantId, String agentCode) {
        String sql = "SELECT * FROM ab_agent_definition WHERE tenant_id = #{params.tenantId} " +
                "AND agent_code = #{params.agentCode} AND status = 'active' AND deleted_flag = FALSE";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "agentCode", agentCode));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> loadTask(Long tenantId, String taskPid) {
        String sql = "SELECT * FROM ab_agent_task WHERE tenant_id = #{params.tenantId} " +
                "AND pid = #{params.taskPid} AND deleted_flag = FALSE";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "taskPid", taskPid));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // =========================================================================
    // Prompt building
    // =========================================================================

    private String buildSystemPrompt(Map<String, Object> agentDef, Map<String, Object> task,
                                      Long tenantId, String agentCode) {
        StringBuilder sb = new StringBuilder();

        // Base system prompt
        String basePrompt = getStringField(agentDef, "system_prompt");
        if (basePrompt != null) {
            sb.append(basePrompt).append("\n\n");
        } else {
            sb.append("You are an AI agent named ").append(agentDef.get("name")).append(". ");
            if (agentDef.get("description") != null) sb.append(agentDef.get("description"));
            sb.append("\nComplete the assigned task. Use the available tools when needed. Be concise and action-oriented.\n\n");
        }

        // Soul Profile section — prefer structured soul_profile JSONB over legacy flat columns
        Object rawSoulProfile = agentDef.get("soul_profile");
        String soulProfileStr = rawSoulProfile instanceof String rawStr ? rawStr : null;
        Map<String, Object> soulProfile = SoulProfileParser.parse(soulProfileStr);

        if (!soulProfile.isEmpty()) {
            String soulSection = SoulProfileParser.toPromptSection(soulProfile);
            if (!soulSection.isBlank()) {
                sb.append(soulSection).append("\n");
            }
        } else {
            // Fallback to legacy flat columns when soul_profile is absent
            String personality = getStringField(agentDef, "personality");
            String expertise = getStringField(agentDef, "expertise");
            String commStyle = getStringField(agentDef, "communication_style");
            String boundaries = getStringField(agentDef, "boundaries");
            String goals = getStringField(agentDef, "soul_goals");

            boolean hasSoul = personality != null || expertise != null || commStyle != null
                    || boundaries != null || goals != null;

            if (hasSoul) {
                sb.append("## Your Identity\n");
                if (personality != null) sb.append("- Personality: ").append(personality).append("\n");
                if (expertise != null) sb.append("- Expertise: ").append(expertise).append("\n");
                if (commStyle != null) sb.append("- Communication style: ").append(commStyle.toLowerCase()).append("\n");
                if (goals != null) sb.append("- Goals: ").append(goals).append("\n");
                if (boundaries != null) {
                    sb.append("\n## Boundaries (MUST respect)\n").append(boundaries).append("\n");
                }
                sb.append("\n");
            }
        }

        // Memory section
        String memorySection = loadMemorySection(tenantId, agentCode);
        if (memorySection != null && !memorySection.isBlank()) {
            sb.append(memorySection).append("\n");
        }

        return sb.toString();
    }

    private String getStringField(Map<String, Object> record, String field) {
        Object val = record.get(field);
        return val instanceof String s && !s.isBlank() ? s : null;
    }

    private String loadMemorySection(Long tenantId, String agentCode) {
        try {
            // Resolve configurable limits: agent_config overrides > AgentProperties defaults
            int maxChars = agentProperties.getMemoryMaxChars();
            int maxItems = agentProperties.getMemoryMaxItems();
            String categoryFilter = null;

            // Check agent-level overrides from agent_config
            Map<String, Object> agentDef = loadAgentDefinition(tenantId, agentCode);
            if (agentDef != null && agentDef.get("guardrails") != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> guardrails = agentDef.get("guardrails") instanceof String
                            ? objectMapper.readValue((String) agentDef.get("guardrails"), Map.class)
                            : (Map<String, Object>) agentDef.get("guardrails");
                    if (guardrails.containsKey("memoryMaxChars")) {
                        maxChars = ((Number) guardrails.get("memoryMaxChars")).intValue();
                    }
                    if (guardrails.containsKey("memoryMaxItems")) {
                        maxItems = ((Number) guardrails.get("memoryMaxItems")).intValue();
                    }
                    if (guardrails.containsKey("memoryCategory")) {
                        categoryFilter = String.valueOf(guardrails.get("memoryCategory"));
                    }
                } catch (Exception ignored) {}
            }

            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("SELECT pid, memory_title, memory_content, memory_type, category, shadow_mode ");
            sqlBuilder.append("FROM ab_agent_memory ");
            sqlBuilder.append("WHERE tenant_id = #{params.tenantId} ");
            sqlBuilder.append("AND (memory_agent_id = #{params.agentCode} OR memory_agent_id IS NULL) ");
            sqlBuilder.append("AND deleted_flag = FALSE ");

            Map<String, Object> params = new HashMap<>();
            params.put("tenantId", tenantId);
            params.put("agentCode", agentCode);

            if (categoryFilter != null && !categoryFilter.isBlank()) {
                sqlBuilder.append("AND category = #{params.category} ");
                params.put("category", categoryFilter);
            }

            sqlBuilder.append("ORDER BY importance DESC, updated_at DESC ");
            sqlBuilder.append("LIMIT ").append(maxItems);

            List<Map<String, Object>> memories = dynamicDataMapper.selectByQuery(sqlBuilder.toString(), params);

            // Track memory access for decay system
            memoryService.trackAccess(tenantId, agentCode);

            // PR-77 Phase 3: prepend User Soul Profile section (plan §5.5) BEFORE
            // the agent-memory block when an ACTIVE profile exists for the current
            // user. System/cron runs without a user id still get the memory section
            // unchanged (Reader returns Optional.empty when userId is null).
            String profilePreamble = "";
            if (userSoulProfileReader != null) {
                Long currentUserIdForProfile = MetaContext.getCurrentUserId();
                String userIdForProfile = currentUserIdForProfile == null
                        ? null : currentUserIdForProfile.toString();
                Optional<UserSoulProfileReader.ProfileSection> section =
                        userSoulProfileReader.loadForGrounding(tenantId, userIdForProfile);
                if (section.isPresent()) {
                    profilePreamble = section.get().renderedPromptText() + "\n\n";
                }
            }

            if (memories == null || memories.isEmpty()) {
                return profilePreamble.isEmpty() ? null : profilePreamble;
            }

            StringBuilder sb = new StringBuilder();
            sb.append(profilePreamble);
            sb.append("## Agent Memory\n");
            sb.append("The following are your accumulated memories and lessons. Use them to inform your work:\n");

            // PR-66: capture the current user id (may be null for system/cron
            // calls) so each materialised memory can be logged per-user for
            // the implicit_co_sign extractor.
            Long currentUserId = MetaContext.getCurrentUserId();
            String userIdStr = currentUserId == null ? null : currentUserId.toString();

            int totalLen = sb.length();
            for (Map<String, Object> mem : memories) {
                String pid = mem.get("pid") != null ? String.valueOf(mem.get("pid")) : null;
                String memType = mem.get("memory_type") != null ? String.valueOf(mem.get("memory_type")) : "note";
                String category = mem.get("category") != null ? String.valueOf(mem.get("category")) : "";
                String title = mem.get("memory_title") != null ? String.valueOf(mem.get("memory_title")) : "";
                String content = mem.get("memory_content") != null ? String.valueOf(mem.get("memory_content")) : "";

                // PR-72 C2: route through the shared helper so agent-run prompt
                // assembly applies the same shadow-mode annotation that the
                // interactive chat path uses. Without this, shadow memories
                // look fully-endorsed to the LLM during cron agent runs.
                Object shadowObj = mem.get("shadow_mode");
                boolean isShadow = shadowObj instanceof Boolean shadowBool ? shadowBool
                        : Boolean.parseBoolean(String.valueOf(shadowObj));
                content = ActiveMemoryService.applyShadowMarker(content, isShadow);

                StringBuilder entry = new StringBuilder();
                entry.append("\n### [").append(memType).append("]");
                if (!category.isBlank()) {
                    entry.append(" Category: ").append(category);
                }
                if (!title.isBlank()) {
                    entry.append(" \u2014 ").append(title);
                }
                entry.append("\n").append(content).append("\n");

                if (totalLen + entry.length() > maxChars) {
                    break;
                }
                sb.append(entry);
                totalLen += entry.length();

                // PR-66: per-user access log (upsert per day); no-op if userId null.
                if (pid != null && userIdStr != null) {
                    memoryService.recordMemoryAccess(pid, userIdStr);
                }
            }

            return sb.toString();
        } catch (DataAccessException e) {
            // allowed-catch: prompt-assembly must not crash on memory-read failure;
            // see PR-72 C4. Log at ERROR with full stack so the failure is visible
            // in ops dashboards even though the agent run continues without memory.
            log.error("Failed to load agent memories for tenant={} agent={}: {}",
                    tenantId, agentCode, e.getMessage(), e);
            return null;
        }
    }

    private String buildUserMessage(Map<String, Object> task) {
        StringBuilder msg = new StringBuilder();
        msg.append("## Task: ").append(task.get("title")).append("\n\n");
        if (task.get("description") != null) {
            msg.append(task.get("description")).append("\n\n");
        }
        if (task.get("input_data") != null) {
            msg.append("### Input Data\n").append(task.get("input_data")).append("\n\n");
        }
        msg.append("Please complete this task using the available tools. Provide a summary when done.");
        return msg.toString();
    }

    // =========================================================================
    // Model Scope enforcement
    // =========================================================================

    /**
     * Filter tools to only those whose source model is in the agent's allowed_models list.
     * If allowed_models is null/empty, all tools are allowed (backward compatible).
     */
    @SuppressWarnings("unchecked")
    private List<AgentToolDefinition> enforceModelScope(List<AgentToolDefinition> tools,
                                                         Map<String, Object> agentDef) {
        Object allowedRaw = agentDef.get("allowed_models");
        if (allowedRaw == null) return tools;

        List<String> allowedModels;
        if (allowedRaw instanceof String s && !s.isBlank()) {
            try {
                allowedModels = objectMapper.readValue(s, List.class);
            } catch (Exception e) {
                return tools;
            }
        } else if (allowedRaw instanceof List<?> list) {
            allowedModels = (List<String>) list;
        } else {
            return tools;
        }

        if (allowedModels.isEmpty()) return tools;

        Set<String> allowedSet = new HashSet<>(allowedModels);
        List<AgentToolDefinition> filtered = tools.stream()
                .filter(t -> {
                    String sourceCode = t.getSourceCode();
                    if (sourceCode == null) return true; // API tools without source model pass through
                    // Extract model code from command code like "crm:create_lead" -> model prefix "crm"
                    // or from NQ code like "crm_lead_pipeline" -> check if any allowed model is a prefix
                    String modelPrefix = sourceCode.contains(":") ? sourceCode.split(":")[0] : null;
                    return allowedSet.stream().anyMatch(m ->
                            sourceCode.startsWith(m) || (modelPrefix != null && m.startsWith(modelPrefix)));
                })
                .toList();

        if (filtered.size() < tools.size()) {
            log.info("Model scope: {} tools filtered to {} (allowed models: {})",
                    tools.size(), filtered.size(), allowedModels);
        }
        return filtered;
    }

    /**
     * Convert ToolDefinition (from ToolProviderRegistry) to AgentToolDefinition (used downstream).
     */
    private List<AgentToolDefinition> toAgentToolDefinitions(List<ToolDefinition> toolDefs) {
        return toolDefs.stream()
                .map(td -> AgentToolDefinition.builder()
                        .name(td.getToolCode())
                        .description(td.getDescription())
                        .inputSchema(td.getParameterSchema())
                        .toolType(td.getToolType())
                        .sourceCode(td.getSourceCode())
                        .requiresApproval(td.isRequiresApproval())
                        .requiresConfirmation(td.isRequiresConfirmation())
                        .riskLevel(td.getRiskLevel())
                        .confirmationPolicy(td.getConfirmationPolicy())
                        .build())
                .toList();
    }

    // =========================================================================
    // Timeout checking
    // =========================================================================

    private void checkTimeout(String runPid, LocalDateTime startedAt) {
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
    // Tool usage statistics (observability)
    // =========================================================================

    // updateToolStats, incrementMisuseCount, incrementHallucinationCount, getHallucinationCount
    // -> moved to ToolLoopService (ACP Kernel: ToolExecutor)

    static class AgentLoopResult {
        boolean success;
        int totalInputTokens;
        int totalOutputTokens;
        double totalCost;
        String lastResponse;
    }
}
