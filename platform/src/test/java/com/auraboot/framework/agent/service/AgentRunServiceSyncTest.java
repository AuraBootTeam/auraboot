package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.dto.AgentPlanStep;
import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.agent.trace.TraceContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase C.3a unit tests for {@link AgentRunService#executeTaskSync}. Asserts
 * the {@link RunOutcome} contract — every terminal path of the ACP run loop
 * must surface a typed outcome so {@code ConversationTurnService.runTurn} can
 * map it to {@link com.auraboot.framework.conversation.TurnOutcome} without
 * polling {@code ab_agent_run.status} (Q-C3.2=β "sync core + async at
 * adapter").
 *
 * <p>Deep-path success / failure scenarios that traverse the full plan loop
 * are covered by {@code CustomerServiceAgentIntegrationTest} (real DB +
 * mocked LLM) — this class focuses on the outcome wiring at the
 * {@code AgentRunService} boundary, mocking every collaborator so each
 * outcome variant is verified deterministically.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentRunService.executeTaskSync — RunOutcome contract")
class AgentRunServiceSyncTest {

    @Mock private AgentProperties agentProperties;
    @Mock private ToolProviderRegistry toolProviderRegistry;
    @Mock private AgentObservationService observationService;
    @Mock private AgentApprovalGateService approvalGate;
    @Mock private AiTraceService aiTraceService;
    @Mock private ToolLoopService toolLoopService;
    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private CommandExecutor commandExecutor;
    @Mock private NamedQueryService namedQueryService;
    @Mock private LlmProviderFactory providerFactory;
    @Mock private AgentMemoryService memoryService;
    @Mock private ContextWindowManager contextWindowManager;
    @Mock private PlanService planService;
    @Mock private StepLoopService stepLoopService;
    @Mock private RunLifecycleService runLifecycleService;
    @Mock private GroundingService groundingService;
    @Mock private AgentSkillService skillService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private LlmProvider provider;

    private AgentRunService service;

    private static final Long TENANT_ID = 7L;
    private static final String TASK_PID = "task-001";
    private static final String AGENT_CODE = "test-agent";

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new AgentRunService(
                agentProperties,
                toolProviderRegistry,
                observationService,
                approvalGate,
                aiTraceService,
                toolLoopService,
                dynamicDataMapper,
                commandExecutor,
                namedQueryService,
                objectMapper,
                providerFactory,
                memoryService,
                contextWindowManager,
                planService,
                stepLoopService,
                runLifecycleService,
                groundingService,
                skillService,
                eventPublisher
        );
        // executeTaskSync explicitly does NOT manage MetaContext — caller's
        // job. Bind a system tenant for tests so the deeper code paths that
        // read MetaContext.getCurrentUserId() / .exists() do not throw.
        MetaContext.setSystemTenantContext(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("agent runtime disabled -> RunOutcome.Skipped (no run row created)")
    void disabledRuntime_returnsSkipped() {
        when(agentProperties.isEnabled()).thenReturn(false);

        RunOutcome outcome = service.executeTaskSync(TENANT_ID, TASK_PID, AGENT_CODE, null);

        assertThat(outcome).isInstanceOf(RunOutcome.Skipped.class);
        assertThat(((RunOutcome.Skipped) outcome).reason()).contains("Agent runtime disabled");
        // No run record should have been written when the gate fires before runPid creation
        verify(runLifecycleService, never()).createRunRecord(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(stepLoopService, providerFactory, dynamicDataMapper);
    }

    @Test
    @DisplayName("concurrency cap reached -> RunOutcome.Failed with cap message")
    void concurrencyCap_returnsFailed() {
        when(agentProperties.isEnabled()).thenReturn(true);
        when(aiTraceService.createTrace(any(), anyString(), anyString(), any(), anyMap()))
                .thenReturn(TraceContext.builder().build());
        // loadAgentDefinition returns one row with a max_concurrent_runs cap
        Map<String, Object> agentDef = baseAgentDef();
        agentDef.put("max_concurrent_runs", 1);
        when(dynamicDataMapper.selectByQuery(argThat(sql -> sql != null && sql.contains("ab_agent_definition")),
                anyMap()))
                .thenReturn(List.of(agentDef));
        when(providerFactory.getDefaultModel(anyString())).thenReturn("claude-test");
        when(runLifecycleService.countActiveRuns(eq(TENANT_ID), eq(AGENT_CODE), anyString()))
                .thenReturn(99); // way over cap

        RunOutcome outcome = service.executeTaskSync(TENANT_ID, TASK_PID, AGENT_CODE, null);

        assertThat(outcome).isInstanceOf(RunOutcome.Failed.class);
        RunOutcome.Failed failed = (RunOutcome.Failed) outcome;
        assertThat(failed.runPid()).isNotBlank();
        assertThat(failed.errorMessage()).contains("Concurrency limit").contains("99/1");
        // Run row was created and then flipped to queued — verify the queued update
        verify(runLifecycleService, times(1)).createRunRecord(eq(TENANT_ID), anyString(),
                eq(TASK_PID), eq(AGENT_CODE), anyString(), any());
        verify(dynamicDataMapper, times(1)).update(eq("ab_agent_run"),
                argThat(updates -> "queued".equals(((Map<?, ?>) updates).get("run_status"))),
                anyMap());
        // Plan loop must not have started
        verifyNoInteractions(stepLoopService);
    }

    @Test
    @DisplayName("no LLM provider configured -> RunOutcome.Failed via failRun")
    void noProviderConfigured_returnsFailed() {
        when(agentProperties.isEnabled()).thenReturn(true);
        when(aiTraceService.createTrace(any(), anyString(), anyString(), any(), anyMap()))
                .thenReturn(TraceContext.builder().build());
        when(dynamicDataMapper.selectByQuery(argThat(sql -> sql != null && sql.contains("ab_agent_definition")),
                anyMap()))
                .thenReturn(List.of(baseAgentDef()));
        when(providerFactory.getDefaultModel(anyString())).thenReturn("claude-test");
        when(runLifecycleService.countActiveRuns(any(), any(), any())).thenReturn(0);
        // Every provider in the chain returns null — none configured
        when(providerFactory.resolveConfig(any(), anyString())).thenReturn(null);
        when(providerFactory.listConfiguredProviders(any())).thenReturn(List.of());

        RunOutcome outcome = service.executeTaskSync(TENANT_ID, TASK_PID, AGENT_CODE, null);

        assertThat(outcome).isInstanceOf(RunOutcome.Failed.class);
        RunOutcome.Failed failed = (RunOutcome.Failed) outcome;
        assertThat(failed.errorMessage()).contains("No LLM provider configured");
        verify(runLifecycleService, times(1)).failRun(eq(TENANT_ID), anyString(), eq(TASK_PID),
                any(), argThat(msg -> msg != null && msg.contains("No LLM provider configured")));
        verifyNoInteractions(stepLoopService);
    }

    @Test
    @DisplayName("plan loop succeeds -> RunOutcome.Success carries finalResponse + telemetry")
    void planLoopSucceeds_returnsSuccess() throws Exception {
        primeHappyPath();
        AgentRunService.AgentLoopResult ok = new AgentRunService.AgentLoopResult();
        ok.success = true;
        ok.lastResponse = "All steps completed.";
        ok.totalInputTokens = 123;
        ok.totalOutputTokens = 45;
        ok.totalCost = 0.0123d;
        when(stepLoopService.executePlanSteps(any(), anyInt(), any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ok);
        // completeRunRecord — caller of completeRun expects this to return true on success
        when(runLifecycleService.completeRunRecord(any(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(true);

        RunOutcome outcome = service.executeTaskSync(TENANT_ID, TASK_PID, AGENT_CODE, null);

        assertThat(outcome).isInstanceOf(RunOutcome.Success.class);
        RunOutcome.Success success = (RunOutcome.Success) outcome;
        assertThat(success.finalResponse()).isEqualTo("All steps completed.");
        assertThat(success.inputTokens()).isEqualTo(123);
        assertThat(success.outputTokens()).isEqualTo(45);
        assertThat(success.totalCost()).isEqualTo(0.0123d);
        assertThat(success.runPid()).isNotBlank();
    }

    @Test
    @DisplayName("plan loop throws AgentApprovalPendingException -> RunOutcome.PendingApproval")
    void planLoopPending_returnsPendingApproval() throws Exception {
        primeHappyPath();
        when(stepLoopService.executePlanSteps(any(), anyInt(), any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new AgentApprovalPendingException("Step 2 awaits approval"));

        RunOutcome outcome = service.executeTaskSync(TENANT_ID, TASK_PID, AGENT_CODE, null);

        assertThat(outcome).isInstanceOf(RunOutcome.PendingApproval.class);
        RunOutcome.PendingApproval pending = (RunOutcome.PendingApproval) outcome;
        assertThat(pending.message()).isEqualTo("Step 2 awaits approval");
        assertThat(pending.runPid()).isNotBlank();
        // Run row flipped to pending status
        verify(dynamicDataMapper, times(1)).update(eq("ab_agent_run"),
                argThat(updates -> "pending".equals(((Map<?, ?>) updates).get("run_status"))),
                anyMap());
        // Failed path should NOT have fired
        verify(runLifecycleService, never()).failRun(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("plan loop throws generic exception -> RunOutcome.Failed via failRun + SessionEnded")
    void planLoopThrows_returnsFailed() throws Exception {
        primeHappyPath();
        when(stepLoopService.executePlanSteps(any(), anyInt(), any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("LLM provider exploded"));

        RunOutcome outcome = service.executeTaskSync(TENANT_ID, TASK_PID, AGENT_CODE, null);

        assertThat(outcome).isInstanceOf(RunOutcome.Failed.class);
        RunOutcome.Failed failed = (RunOutcome.Failed) outcome;
        assertThat(failed.errorMessage()).isEqualTo("LLM provider exploded");
        assertThat(failed.runPid()).isNotBlank();
        verify(runLifecycleService, times(1)).failRun(eq(TENANT_ID), anyString(), eq(TASK_PID),
                any(), eq("LLM provider exploded"));
        // SessionEndedEvent guard must be claimed for FAILED terminal state so
        // L1 memory promoter still runs.
        verify(runLifecycleService, times(1)).markSessionEndedPublished(anyString());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Prime mocks so executeTaskSync runs through the entire setup phase up to
     * the {@code stepLoopService.executePlanSteps} call. Tests then customise
     * the executePlanSteps stub to drive their target outcome.
     */
    private void primeHappyPath() {
        when(agentProperties.isEnabled()).thenReturn(true);
        when(aiTraceService.createTrace(any(), anyString(), anyString(), any(), anyMap()))
                .thenReturn(TraceContext.builder().build());
        when(dynamicDataMapper.selectByQuery(argThat(sql -> sql != null && sql.contains("ab_agent_definition")),
                anyMap()))
                .thenReturn(List.of(baseAgentDef()));
        when(dynamicDataMapper.selectByQuery(argThat(sql -> sql != null && sql.contains("ab_agent_task")),
                anyMap()))
                .thenReturn(List.of(baseTask()));
        when(dynamicDataMapper.selectByQuery(argThat(sql -> sql != null && sql.contains("ab_agent_memory")),
                anyMap()))
                .thenReturn(List.of());
        when(providerFactory.getDefaultModel(anyString())).thenReturn("claude-test");
        when(runLifecycleService.countActiveRuns(any(), any(), any())).thenReturn(0);
        // First provider in chain has a usable api key
        LlmProviderFactory.ProviderConfig cfg = LlmProviderFactory.ProviderConfig.builder()
                .providerCode("anthropic")
                .apiKey("sk-test")
                .baseUrl("https://api.example.com")
                .defaultModel("claude-test")
                .maxTokens(4000)
                .build();
        when(providerFactory.resolveConfig(any(), anyString())).thenReturn(cfg);
        when(providerFactory.getProvider(anyString())).thenReturn(provider);
        when(providerFactory.listConfiguredProviders(any())).thenReturn(List.of());
        // Grounding D1 returns a usable BIF with no quality gate fired
        BusinessIntentFrame bif = BusinessIntentFrame.builder()
                .intent("query")
                .object("widget")
                .candidateSkills(List.of())
                .build();
        when(groundingService.ground(any(), anyString(), any())).thenReturn(bif);
        when(groundingService.checkQualityGate(any())).thenReturn(null);
        when(toolProviderRegistry.discoverAll(any())).thenReturn(List.of());
        // Plan: a single trivial step (no approval needed)
        AgentPlanStep step = new AgentPlanStep(0, "do-it");
        List<AgentPlanStep> plan = new ArrayList<>(List.of(step));
        when(planService.generatePlan(any(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(plan);
    }

    private Map<String, Object> baseAgentDef() {
        java.util.Map<String, Object> def = new java.util.HashMap<>();
        def.put("agent_code", AGENT_CODE);
        def.put("name", "Test Agent");
        def.put("system_prompt", "You are a test agent.");
        def.put("model", "claude-test");
        def.put("status", "active");
        return def;
    }

    private Map<String, Object> baseTask() {
        java.util.Map<String, Object> task = new java.util.HashMap<>();
        task.put("pid", TASK_PID);
        task.put("title", "Test task");
        task.put("description", "Test description");
        task.put("input_data", "{}");
        return task;
    }
}
