package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.dto.AgentPlanStep;
import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.eval.RunOutcomeEvaluator;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.runtime.AgentExecutionState;
import com.auraboot.framework.agent.runtime.AgentRuntimeStateFactory;
import com.auraboot.framework.agent.runtime.LlmRuntimeResolver;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final DeclaredAgentToolResolver declaredAgentToolResolver;
    private final com.auraboot.framework.agent.runtime.policy.AgentToolScopePolicy agentToolScopePolicy;
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
    private final AgentRuntimeStateFactory runtimeStateFactory;

    /**
     * User Soul Profile grounding reader (plan §5.5 / PR-77).
     * Optional so existing tests + legacy contexts without the bean don't break.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserSoulProfileReader userSoulProfileReader;

    /**
     * CAP-03 run-completion outcome/goal verdict gate. Default on — the verdict is
     * observation-only and cheap. Operators can disable it per-deployment. The field
     * initializer keeps it on for non-Spring unit construction (where {@code @Value}
     * is never injected).
     */
    @Value("${aura.agent.run-outcome.enabled:true}")
    private boolean runOutcomeEvalEnabled = true;

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
     * Async fire-and-forget entry for callers that have already pre-seeded the
     * {@code ab_agent_run} row (e.g. {@link SubAgentRunner#spawn} which inserts
     * the row in-band so the parent ↔ child link is observable before the LLM
     * loop starts). Mirrors {@link #executeTask} but skips the
     * {@code createRunRecord} step inside {@link #executeTaskSync} — the
     * existing row identified by {@code existingRunPid} is mutated in place
     * (status/cost/timing updates).
     *
     * <p>Used by {@link SubAgentRunner} to wire P0-6 spawn → real LLM execution
     * without creating two run rows for the same logical execution.
     *
     * @param existingRunPid non-null pid of the {@code ab_agent_run} row that
     *                       was pre-seeded by the caller; must already exist
     *                       in {@code run_status='running'} state.
     */
    @Async
    public void executeTaskForExistingRun(Long tenantId, String taskPid, String agentCode,
                                          String existingRunPid) {
        MetaContext.setSystemTenantContext(tenantId);
        try {
            executeTaskSync(tenantId, taskPid, agentCode, null, existingRunPid);
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
        return executeTaskSync(tenantId, taskPid, agentCode, resumeFromRunPid, null);
    }

    /**
     * Internal overload supporting an {@code existingRunPid} provided by a
     * caller that has already inserted the {@code ab_agent_run} row (e.g.
     * {@link SubAgentRunner}). When non-null, the run record creation step
     * is skipped and the existing row is mutated in place. All other behavior
     * is identical to the public 4-arg overload.
     */
    public RunOutcome executeTaskSync(Long tenantId, String taskPid, String agentCode,
                                      String resumeFromRunPid, String existingRunPid) {
        if (!agentProperties.isEnabled()) {
            log.warn("Agent runtime is disabled, skipping task: {}", taskPid);
            return new RunOutcome.Skipped("Agent runtime disabled");
        }

        String runPid = existingRunPid != null ? existingRunPid : UniqueIdGenerator.generate();
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
            if (agentDef == null) {
                String agentMissingMsg = "Agent not found: " + agentCode;
                if (existingRunPid == null) {
                    runLifecycleService.createRunRecord(tenantId, runPid, taskPid, agentCode, null, startedAt);
                }
                runLifecycleService.failRun(tenantId, runPid, taskPid, startedAt, agentMissingMsg);
                return new RunOutcome.Failed(runPid, agentMissingMsg);
            }
            String providerCode = LlmRuntimeResolver.resolveAgentProviderCode(objectMapper, providerFactory, agentDef);
            if (providerCode == null || providerCode.isBlank()) {
                // F2: chat-parity auto-resolution — guardrails naming no provider
                // must not fail the run when the tenant HAS a configured provider
                // (the chat engine auto-resolves the same way). The fail-fast
                // below still guards the nothing-configured case.
                providerCode = providerFactory.resolveDefaultProviderCode(tenantId);
                if (providerCode != null && !providerCode.isBlank()) {
                    log.info("Agent '{}' guardrails name no provider — auto-resolved '{}' (F2 chat-parity)",
                            agentCode, providerCode);
                }
            }
            String preferredProviderCode = providerCode;
            String model = LlmRuntimeResolver.resolveAgentModel(providerFactory, agentDef, providerCode);

            // Create run record first (even if API key missing, we want the record).
            // When the caller pre-seeded the row (existingRunPid != null, e.g.
            // SubAgentRunner.spawn), skip the INSERT and mutate the existing row
            // so we don't double-write a parallel un-linked run record for the
            // same logical execution.
            if (existingRunPid == null) {
                runLifecycleService.createRunRecord(tenantId, runPid, taskPid, agentCode, model, startedAt);
            } else {
                // Refresh model + updated_at so the pre-seeded row reflects the
                // executor's resolved configuration. run_status stays 'running'.
                Map<String, Object> runUpdate = new LinkedHashMap<>();
                if (model != null) {
                    runUpdate.put("run_model", model);
                }
                runUpdate.put("updated_at", LocalDateTime.now());
                dynamicDataMapper.update("ab_agent_run", runUpdate, Map.of("pid", runPid));
            }

            // If resuming, link to original run
            if (resumeFromRunPid != null) {
                dynamicDataMapper.update("ab_agent_run",
                        Map.of("resumed_from", resumeFromRunPid, "updated_at", LocalDateTime.now()),
                        Map.of("pid", runPid));
            }

            if (providerCode == null || providerCode.isBlank()) {
                String noProviderMsg = "No LLM provider configured for agent: " + agentCode +
                        ". Define guardrails.provider, guardrails.preferredProvider, or a known model.";
                runLifecycleService.failRun(tenantId, runPid, taskPid, startedAt, noProviderMsg);
                return new RunOutcome.Failed(runPid, noProviderMsg);
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

            // Resolve provider with the agent's explicit provider chain.
            LlmProvider provider = null;
            LlmProviderFactory.ProviderConfig config = null;
            String resolvedProviderCode = providerCode;

            List<String> providerChain = LlmRuntimeResolver.resolveAgentProviderChain(
                    objectMapper, agentDef, providerCode);
            for (String pc : providerChain) {
                LlmProviderFactory.ProviderConfig candidateConfig = providerFactory.resolveConfig(tenantId, pc);
                if (candidateConfig != null && candidateConfig.getApiKey() != null && !candidateConfig.getApiKey().isBlank()) {
                    String effectiveProviderCode = LlmProviderFactory.effectiveProviderCode(pc, candidateConfig);
                    LlmProvider candidateProvider = providerFactory.getProvider(effectiveProviderCode);
                    if (candidateProvider == null) {
                        String noProviderMsg = "LLM provider not available: " + effectiveProviderCode;
                        runLifecycleService.failRun(tenantId, runPid, taskPid, startedAt, noProviderMsg);
                        return new RunOutcome.Failed(runPid, noProviderMsg);
                    }
                    provider = candidateProvider;
                    config = candidateConfig;
                    resolvedProviderCode = effectiveProviderCode;
                    if (!effectiveProviderCode.equals(providerCode)) {
                        log.info("Preferred provider '{}' resolved via '{}' to '{}'",
                                providerCode, pc, effectiveProviderCode);
                        model = LlmRuntimeResolver.resolveAgentModel(
                                providerFactory, agentDef, effectiveProviderCode, true);
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
            String toolDiscoveryMode;
            if (qualityIssue != null) {
                log.info("D1 quality gate triggered: {}, discovering all tools", qualityIssue);
                tools = toAgentToolDefinitions(toolProviderRegistry.discoverAll(discoveryCtx));
                toolDiscoveryMode = "quality_gate_expanded";
            } else {
                // Use BIF candidateSkills to narrow tool selection
                List<String> candidateSkills = bif != null ? bif.getCandidateSkills() : null;
                if (candidateSkills != null && !candidateSkills.isEmpty()) {
                    // Load tools from candidate skills
                    List<AgentToolDefinition> skillTools = new ArrayList<>();
                    for (String skillCode : candidateSkills) {
                        skillTools.addAll(skillService.resolveSkillTools(tenantId, skillCode));
                    }
                    // If skill tools found, use them; otherwise discover all
                    if (skillTools.isEmpty()) {
                        tools = toAgentToolDefinitions(toolProviderRegistry.discoverAll(discoveryCtx));
                        toolDiscoveryMode = "registry_all";
                    } else {
                        tools = skillTools;
                        toolDiscoveryMode = "candidate_skills";
                    }
                } else {
                    tools = toAgentToolDefinitions(toolProviderRegistry.discoverAll(discoveryCtx));
                    toolDiscoveryMode = "registry_all";
                }
            }

            // The bif-derived modelHint only surfaces tools for the model the task text grounds to.
            // Additively ensure the agent's EXPLICITLY DECLARED tools are always available — declared
            // commands/queries on other models (e.g. cmd:crm:create_activity on crm_activity_common
            // while the task is about crm_complaint) would otherwise be undiscovered and rejected by
            // the plan validator as hallucinated. Only adds declared tools that are missing.
            List<String> declaredCodes = DeclaredAgentToolResolver.parseDeclaredCodes(agentDef, objectMapper);
            if (!declaredCodes.isEmpty()) {
                Long discoveryUserId = MetaContext.exists() ? MetaContext.getCurrentUserId() : null;
                List<AgentToolDefinition> declaredTools = toAgentToolDefinitions(
                        declaredAgentToolResolver.resolveDeclaredTools(
                                tenantId, discoveryUserId, agentCode, declaredCodes));
                Set<String> presentNames = tools.stream()
                        .map(AgentToolDefinition::getName)
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
                List<AgentToolDefinition> merged = new ArrayList<>(tools);
                int added = 0;
                for (AgentToolDefinition dt : declaredTools) {
                    if (dt.getName() != null && presentNames.add(dt.getName())) {
                        merged.add(dt);
                        added++;
                    }
                }
                if (added > 0) {
                    tools = merged;
                    log.info("Agent {} declared-tool merge added {} tool(s) missing from bif discovery",
                            agentCode, added);
                }
            }

            // B4: the allowed_models / allowed_operations guardrails restrict what the
            // assembled tool list may contain. The tool list is the enforcement
            // boundary on this engine — ToolLoopService rejects any call not in it —
            // so filtering here is what makes the wizard checkboxes actually bind.
            // Restriction wins over declared tools by design.
            tools = agentToolScopePolicy.filterAgentTools(
                    agentToolScopePolicy.scopeOf(agentDef), tools, agentCode);

            log.info("Agent {} tools: selected={}, d1={}, provider={}, model={}",
                    agentCode, tools.size(),
                    qualityIssue == null ? "active" : "fallback", resolvedProviderCode, model);

            // Update run record with resolved model and provider (may differ from initial due to fallback)
            int timeoutSeconds = agentDef.get("execution_timeout_seconds") != null
                    ? ((Number) agentDef.get("execution_timeout_seconds")).intValue()
                    : DEFAULT_TIMEOUT_SECONDS;
            LocalDateTime timeoutAt = startedAt.plusSeconds(timeoutSeconds);
            Map<String, Object> runtimeMetadata = buildRunRuntimeMetadata(
                    tenantId, runPid, taskPid, agentCode, preferredProviderCode, resolvedProviderCode,
                    providerChain, model, systemPrompt, userMessage, config, tools, toolDiscoveryMode,
                    qualityIssue, bif);
            Map<String, Object> runSetupUpdate = new LinkedHashMap<>();
            runSetupUpdate.put("timeout_at", timeoutAt);
            runSetupUpdate.put("run_model", model);
            runSetupUpdate.put("metadata", objectMapper.writeValueAsString(runtimeMetadata));
            runSetupUpdate.put("updated_at", LocalDateTime.now());
            dynamicDataMapper.update("ab_agent_run", runSetupUpdate, Map.of("pid", runPid));

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
                // H.3 wiring: pre-load every skill row referenced by the plan
                // so StepLoopService can overlay ab_agent_skill.execution_config
                // onto the agent baseline for thinking-config resolution. We
                // intentionally load once here (not per-step) to keep the hot
                // loop free of DB round-trips. Missing skills are tolerated —
                // the lookup helper treats them as "no skill overlay".
                Map<String, Map<String, Object>> skillByCode = loadSkillsForPlan(tenantId, plan);
                result = stepLoopService.executePlanSteps(plan, startStep, tenantId, runPid, taskPid, agentCode,
                        systemPrompt, userMessage, tools, agentDef, skillByCode, provider, config, traceCtx,
                        skipApprovalForResumedStep);

                // Persist final plan state
                planService.persistPlan(runPid, plan, plan.size());

                completeRun(tenantId, runPid, taskPid, agentCode, plan, startedAt, result, model);

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
            log.info("Run {} paused for approval (approvalPid={}): {}",
                    runPid, e.getApprovalPid(), e.getMessage());
            Map<String, Object> runUpdate = new HashMap<>();
            runUpdate.put("run_status", "pending");
            runUpdate.put("error_message", e.getMessage());
            runUpdate.put("updated_at", LocalDateTime.now());
            dynamicDataMapper.update("ab_agent_run", runUpdate, Map.of("pid", runPid));
            // End trace as pending
            try { aiTraceService.endTrace(traceCtx, null, "pending"); }
            catch (Exception traceEx) { log.debug("Failed to end trace for run {}: {}", runPid, traceEx.getMessage()); }
            // Task stays IN_PROGRESS — will be resumed after approval
            return new RunOutcome.PendingApproval(runPid, e.getApprovalPid(), e.getMessage());
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


    private Map<String, Object> buildRunRuntimeMetadata(Long tenantId, String runPid, String taskPid,
                                                         String agentCode, String preferredProviderCode,
                                                         String resolvedProviderCode, List<String> providerChain,
                                                         String model, String systemPrompt, String userMessage,
                                                         LlmProviderFactory.ProviderConfig config,
                                                         List<AgentToolDefinition> tools,
                                                         String toolDiscoveryMode, String qualityIssue,
                                                         com.auraboot.framework.agent.dto.BusinessIntentFrame bif) {
        int maxTokens = config != null && config.getMaxTokens() > 0 ? config.getMaxTokens() : 4096;
        AgentExecutionState runtimeState = runtimeStateFactory.acpRunState(
                tenantId,
                MetaContext.exists() ? MetaContext.getCurrentUserId() : null,
                runPid,
                taskPid,
                agentCode,
                resolvedProviderCode,
                model,
                systemPrompt,
                userMessage,
                maxTokens,
                tools,
                Map.of("toolDiscoveryMode", toolDiscoveryMode));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runtimeState", runtimeState.toSnapshotMap());
        metadata.put("fallbackAudit", buildFallbackAudit(
                preferredProviderCode, resolvedProviderCode, providerChain,
                toolDiscoveryMode, qualityIssue, bif, tools != null ? tools.size() : 0));
        return metadata;
    }

    private Map<String, Object> buildFallbackAudit(String preferredProviderCode, String resolvedProviderCode,
                                                   List<String> providerChain, String toolDiscoveryMode,
                                                   String qualityIssue,
                                                   com.auraboot.framework.agent.dto.BusinessIntentFrame bif,
                                                   int selectedTools) {
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("preferred", preferredProviderCode);
        provider.put("resolved", resolvedProviderCode);
        provider.put("chain", providerChain == null ? List.of() : List.copyOf(providerChain));
        provider.put("fallbackUsed", preferredProviderCode != null && resolvedProviderCode != null
                && !preferredProviderCode.equals(resolvedProviderCode));

        Map<String, Object> toolDiscovery = new LinkedHashMap<>();
        toolDiscovery.put("mode", toolDiscoveryMode);
        toolDiscovery.put("selectedTools", selectedTools);
        if (qualityIssue != null && !qualityIssue.isBlank()) {
            toolDiscovery.put("qualityIssue", qualityIssue);
        }
        if (bif != null) {
            putIfNotBlank(toolDiscovery, "bifObject", bif.getObject());
            putIfNotBlank(toolDiscovery, "bifIntent", bif.getIntent());
            putIfNotBlank(toolDiscovery, "bifRiskLevel", bif.getRiskLevel());
        }

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("provider", provider);
        audit.put("toolDiscovery", toolDiscovery);
        return audit;
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }


    /**
     * H.3 wiring: pre-load all skill rows referenced by the current plan, so
     * {@link StepLoopService#executePlanSteps} can overlay
     * {@code ab_agent_skill.execution_config} onto the agent baseline when
     * resolving per-step thinking config. Returns an empty map (never null)
     * — empty also signals "no skill overlay" in the lookup helper.
     *
     * <p>Resolution mirrors {@code lookupSkillForStep}: prefer
     * {@link AgentPlanStep#getSkillCode()} then fall back to
     * {@link AgentPlanStep#getToolCode()}.
     */
    private Map<String, Map<String, Object>> loadSkillsForPlan(Long tenantId, List<AgentPlanStep> plan) {
        if (plan == null || plan.isEmpty()) return Map.of();
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (AgentPlanStep step : plan) {
            String code = step.getSkillCode();
            if (code == null || code.isBlank()) code = step.getToolCode();
            if (code == null || code.isBlank()) continue;
            if (out.containsKey(code)) continue;
            try {
                Map<String, Object> skill = skillService.loadSkill(tenantId, code);
                if (skill != null) out.put(code, skill);
            } catch (Exception e) {
                // Tolerate lookup failure — falls back to agent-only thinking
                // for that step. Loud enough to debug, quiet enough to not
                // break a run that does not actually depend on the skill row.
                log.debug("loadSkillsForPlan: skill {} not loadable: {}", code, e.getMessage());
            }
        }
        return out;
    }

    // =========================================================================
    // Run/Task lifecycle (delegated to RunLifecycleService)
    // =========================================================================

    /**
     * Complete a run: update records + dispatch child tasks + update mission + save memory.
     * Record updates are delegated to RunLifecycleService; dispatch stays here to avoid circular dependency.
     */
    private void completeRun(Long tenantId, String runPid, String taskPid, String agentCode,
                              List<AgentPlanStep> plan, LocalDateTime startedAt,
                              AgentLoopResult result, String model) {
        boolean success = runLifecycleService.completeRunRecord(tenantId, runPid, taskPid, startedAt, result, model);

        // CAP-03: after the run is marked terminal, derive and record the
        // outcome/goal verdict. Best-effort + observation-only — never changes
        // the run and never disturbs completion (mirrors the CAP-02 promotion).
        publishRunOutcome(tenantId, runPid, agentCode, plan, result);

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

            // Save-back: store a summary memory from this successful run. Re-read
            // the agent code from the task row (its assignee_id) — the memory /
            // session-ended path has historically keyed off the persisted task
            // owner rather than the caller-supplied agentCode.
            Map<String, Object> task = loadTask(tenantId, taskPid);
            String taskAgentCode = task != null ? (String) task.get("assignee_id") : null;
            String taskTitle = task != null ? (String) task.get("title") : null;
            if (taskAgentCode != null) {
                Map<String, Object> agentDef = loadAgentDefinition(tenantId, taskAgentCode);
                String providerCode = LlmRuntimeResolver.resolveAgentProviderCode(
                        objectMapper, providerFactory, agentDef);
                String memModel = LlmRuntimeResolver.resolveAgentModel(providerFactory, agentDef, providerCode);
                runLifecycleService.saveRunMemory(tenantId, runPid, taskPid, result,
                        taskAgentCode, taskTitle, providerCode, memModel);

                publishSessionEndedIfApplicable(tenantId, runPid, taskAgentCode,
                        SessionEndedEvent.TerminalOutcome.SUCCEEDED);
            }
        }
    }

    /**
     * CAP-03: emit a best-effort run-completion outcome/goal verdict as an
     * OBSERVATION on {@code ab_agent_observation}. The step loop only judges
     * "keep going?"; nothing otherwise records whether a terminated run actually
     * ACHIEVED its goal. {@link RunOutcomeEvaluator} derives the verdict
     * ({@code achieved}/{@code partial}/{@code abandoned}) purely from the final
     * plan + terminal-success flag; we publish it here.
     *
     * <p><strong>Observation-only + best-effort.</strong> This never mutates the
     * run and never changes control flow. A failure to evaluate or publish is
     * swallowed with a warn — run completion must not be disturbed (mirrors the
     * CAP-02 best-effort candidate promotion). Gated by
     * {@code aura.agent.run-outcome.enabled} (default true).
     *
     * <p>Degrades gracefully when the observation channel is absent — the
     * try/catch covers a null/throwing {@code observationService} the same way it
     * covers any other publish failure.
     */
    private void publishRunOutcome(Long tenantId, String runPid, String agentCode,
                                   List<AgentPlanStep> plan, AgentLoopResult result) {
        if (!runOutcomeEvalEnabled || observationService == null) {
            return;
        }
        try {
            RunOutcomeEvaluator.Outcome outcome = RunOutcomeEvaluator.evaluate(plan, result.success);
            String runStatus = result.success ? "success" : "failed";
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("verdict", outcome.verdict().code());
            detail.put("completedSteps", outcome.completedSteps());
            detail.put("totalSteps", outcome.totalSteps());
            detail.put("failedSteps", outcome.failedSteps());
            detail.put("skippedSteps", outcome.skippedSteps());
            detail.put("runStatus", runStatus);
            // recordPid = runPid so the observation is traceable back to the run
            // row; modelCode slot carries the verdict as a coarse classifier
            // (mirrors ScheduledOnlineEvalJob.emitDegraded's judgeMode usage).
            observationService.publish(tenantId, "agent_run.outcome", agentCode,
                    outcome.verdict().code(), runPid, detail);
        } catch (Exception e) {
            log.warn("CAP-03 run-outcome evaluation failed for run {} (observation-only, ignored): {}",
                    runPid, e.getMessage());
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
    // Tool conversion
    // =========================================================================

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
                        .modelCode(td.getModelCode())
                        .operationKind(td.getOperationKind())
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
