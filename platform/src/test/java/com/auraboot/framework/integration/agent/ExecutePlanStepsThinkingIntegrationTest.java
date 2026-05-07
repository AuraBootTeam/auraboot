package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.dto.AgentPlanStep;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.service.StepLoopService;
import com.auraboot.framework.agent.trace.TraceContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H.3 wiring — {@code ab_agent_skill.execution_config} merges into thinking
 * config resolution on the multi-step plan execution path
 * ({@link StepLoopService#executePlanSteps}). The other execution path
 * (single-loop {@code executeAgentLoop}) is covered by the F.2 v2 test
 * {@code StepLoopServiceThinkingIntegrationTest}; this class proves the
 * same overlay reaches every per-step LLM dispatch <em>and</em> the
 * adaptive replan dispatch.
 *
 * <p>Real PG/Redis stack via {@link BaseIntegrationTest}; the
 * {@code ab_agent_definition} row is inserted via {@link JdbcTemplate} so
 * production JSONB round-trip is exercised. The {@link LlmProvider} is a
 * Mockito mock — we only assert the captured {@link LlmChatRequest}s,
 * not real Anthropic traffic. Skill rows are hand-built {@link Map}s
 * (matching the {@code DynamicDataMapper}-loaded shape) — keeps the test
 * independent of the {@code ab_agent_skill.execution_config} column
 * migration cadence.
 */
@DisplayName("H.3: StepLoopService.executePlanSteps Extended Thinking propagation")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExecutePlanStepsThinkingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private StepLoopService stepLoopService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Map<String, Object> insertAgentDef(String suffix, String executionConfigJson) {
        String pid = UniqueIdGenerator.generate();
        String agentCode = "test_h3_" + suffix + "_" + System.nanoTime();
        Long tenantId = getTestTenant().getId();

        jdbcTemplate.update(
                "INSERT INTO ab_agent_definition (pid, tenant_id, agent_code, name, description, "
                        + "agent_type, model, status, visibility, execution_config, deleted_flag, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, FALSE, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                pid, tenantId, agentCode, "H3 Plan-Steps Thinking " + suffix,
                "Integration test agent for executePlanSteps thinking propagation",
                "reactive", "claude-sonnet-4-6", "active", "tenant",
                executionConfigJson != null ? executionConfigJson : "{}");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM ab_agent_definition WHERE pid = ?", pid);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private LlmProviderFactory.ProviderConfig anthropicConfig() {
        return LlmProviderFactory.ProviderConfig.builder()
                .providerCode("anthropic")
                .apiKey("sk-test")
                .baseUrl("https://api.anthropic.com")
                .defaultModel("claude-sonnet-4-6")
                .maxTokens(4096)
                .build();
    }

    /** Stub LLM that always returns end_turn — terminates each step's inner loop after 1 chat call. */
    private LlmProvider mockProviderEndTurn() throws Exception {
        LlmProvider provider = mock(LlmProvider.class);
        LlmChatResponse done = LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text").text("step ok").build()))
                .inputTokens(10).outputTokens(5)
                .build();
        when(provider.chat(any(LlmChatRequest.class), anyString(), anyString())).thenReturn(done);
        return provider;
    }

    /**
     * Build a minimal 2-step plan whose steps reference the same skill code.
     * 2 steps avoids the single-step "Execute task directly" shortcut which
     * delegates to executeAgentLoop (F.2 v2 path) — we want the actual
     * multi-step loop in executePlanSteps.
     */
    private List<AgentPlanStep> twoStepPlan(String skillCode) {
        List<AgentPlanStep> plan = new ArrayList<>();
        AgentPlanStep s0 = new AgentPlanStep(0, "Step 0: gather data");
        s0.setSkillCode(skillCode);
        plan.add(s0);
        AgentPlanStep s1 = new AgentPlanStep(1, "Step 1: summarize");
        s1.setSkillCode(skillCode);
        plan.add(s1);
        return plan;
    }

    private Map<String, Map<String, Object>> skillMap(String skillCode, String executionConfigJson) {
        Map<String, Object> skillRow = new HashMap<>();
        skillRow.put("skill_code", skillCode);
        skillRow.put("execution_config", executionConfigJson);
        return Map.of(skillCode, skillRow);
    }

    // ========================================================================
    // Case A — agent empty, skill enables thinking → propagates to LLM request
    // ========================================================================

    @Test
    @DisplayName("planSteps_skillEnablesThinking_agentEmpty_propagatesPerStep")
    void planSteps_skillEnablesThinking_agentEmpty_propagatesPerStep() throws Exception {
        Map<String, Object> agentDef = insertAgentDef("caseA", "{}");
        String skillCode = "report_analysis_h3_a_" + System.nanoTime();
        Map<String, Map<String, Object>> skillByCode = skillMap(skillCode,
                "{\"thinking_enabled\":true,\"thinking_budget_tokens\":8000}");

        LlmProvider provider = mockProviderEndTurn();
        List<AgentPlanStep> plan = twoStepPlan(skillCode);

        stepLoopService.executePlanSteps(plan, 0,
                getTestTenant().getId(), "run-h3-a-" + System.nanoTime(),
                "task-h3-a-" + System.nanoTime(),
                (String) agentDef.get("agent_code"),
                "system prompt", "user message",
                java.util.Collections.<com.auraboot.framework.agent.dto.AgentToolDefinition>emptyList(), agentDef, skillByCode,
                provider, anthropicConfig(),
                TraceContext.builder().traceId("trace-h3-a").build(),
                false);

        ArgumentCaptor<LlmChatRequest> reqCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, atLeast(2)).chat(reqCaptor.capture(), anyString(), anyString());

        // Every captured request (one per step) must carry the skill thinking knob
        List<LlmChatRequest> all = reqCaptor.getAllValues();
        assertThat(all).hasSizeGreaterThanOrEqualTo(2);
        for (int i = 0; i < all.size(); i++) {
            LlmChatRequest.ThinkingConfig tc = all.get(i).getThinking();
            assertThat(tc)
                    .as("Plan-step LLM dispatch #%d must carry skill-level thinking config", i)
                    .isNotNull();
            assertThat(tc.isEnabled()).isTrue();
            assertThat(tc.getBudgetTokens()).isEqualTo(8000);
        }
    }

    // ========================================================================
    // Case B — replan path inherits the same skill thinking config
    // ========================================================================

    @Test
    @DisplayName("planSteps_replanPath_inheritsSkillThinking")
    void planSteps_replanPath_inheritsSkillThinking() throws Exception {
        Map<String, Object> agentDef = insertAgentDef("caseB", "{}");
        String skillCode = "report_analysis_h3_b_" + System.nanoTime();
        Map<String, Map<String, Object>> skillByCode = skillMap(skillCode,
                "{\"thinking_enabled\":true,\"thinking_budget_tokens\":8000}");

        LlmProvider provider = mock(LlmProvider.class);

        // Replan response — empty array forces attemptReplan to return false,
        // which surfaces the original step-0 RuntimeException up to the
        // caller. We still get a captured request for the replan call, which
        // is what we want to assert on.
        LlmChatResponse replanResponse = LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text").text("[]").build()))
                .inputTokens(5).outputTokens(2)
                .build();

        // First chat() call (step 0) throws → triggers replan path; second
        // call (replan) returns the JSON array above. Mockito's thenThrow +
        // thenReturn chains by invocation order regardless of args.
        when(provider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenThrow(new RuntimeException("simulated step-0 failure"))
                .thenReturn(replanResponse);

        List<AgentPlanStep> plan = twoStepPlan(skillCode);

        // Empty replan array → replanned=false → original exception is rethrown.
        // We assert the assertion AFTER catching, so the test still inspects
        // the captured replan request below.
        try {
            stepLoopService.executePlanSteps(plan, 0,
                    getTestTenant().getId(), "run-h3-b-" + System.nanoTime(),
                    "task-h3-b-" + System.nanoTime(),
                    (String) agentDef.get("agent_code"),
                    "system prompt", "user message",
                    java.util.Collections.<com.auraboot.framework.agent.dto.AgentToolDefinition>emptyList(), agentDef, skillByCode,
                    provider, anthropicConfig(),
                    TraceContext.builder().traceId("trace-h3-b").build(),
                    false);
            // Step 0 failed and replan returned [] → executePlanSteps must
            // rethrow; if it returns we are missing the replan call entirely.
            org.junit.jupiter.api.Assertions.fail(
                    "Expected RuntimeException after step-0 failure + empty replan");
        } catch (RuntimeException expected) {
            assertThat(expected.getMessage()).isEqualTo("simulated step-0 failure");
        }

        ArgumentCaptor<LlmChatRequest> reqCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, atLeast(2)).chat(reqCaptor.capture(), anyString(), anyString());
        List<LlmChatRequest> all = reqCaptor.getAllValues();

        // Both requests (step-0 attempt + replan) must carry skill thinking
        for (int i = 0; i < all.size(); i++) {
            LlmChatRequest.ThinkingConfig tc = all.get(i).getThinking();
            assertThat(tc)
                    .as("Replan path request #%d must inherit failed step's skill thinking config", i)
                    .isNotNull();
            assertThat(tc.isEnabled()).isTrue();
            assertThat(tc.getBudgetTokens()).isEqualTo(8000);
        }
    }

    // ========================================================================
    // Case C — null skillByCode + agent empty → no thinking attached (back-compat)
    // ========================================================================

    @Test
    @DisplayName("planSteps_nullSkillMap_agentEmpty_noThinking")
    void planSteps_nullSkillMap_agentEmpty_noThinking() throws Exception {
        Map<String, Object> agentDef = insertAgentDef("caseC", "{}");

        LlmProvider provider = mockProviderEndTurn();
        List<AgentPlanStep> plan = twoStepPlan("unused_skill_code");

        stepLoopService.executePlanSteps(plan, 0,
                getTestTenant().getId(), "run-h3-c-" + System.nanoTime(),
                "task-h3-c-" + System.nanoTime(),
                (String) agentDef.get("agent_code"),
                "system prompt", "user message",
                java.util.Collections.<com.auraboot.framework.agent.dto.AgentToolDefinition>emptyList(), agentDef, null,
                provider, anthropicConfig(),
                TraceContext.builder().traceId("trace-h3-c").build(),
                false);

        ArgumentCaptor<LlmChatRequest> reqCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, atLeast(2)).chat(reqCaptor.capture(), anyString(), anyString());
        for (LlmChatRequest req : reqCaptor.getAllValues()) {
            assertThat(req.getThinking())
                    .as("null skillByCode + empty agent execution_config must NOT attach thinking")
                    .isNull();
        }
    }

    // ========================================================================
    // Case D — both sides set, skill wins (matches F.2 v2 merge precedence)
    // ========================================================================

    @Test
    @DisplayName("planSteps_skillBudgetOverridesAgentBudget_skillWins_perStep")
    void planSteps_skillBudgetOverridesAgentBudget_skillWins_perStep() throws Exception {
        // Agent says enabled with budget=4000
        Map<String, Object> agentDef = insertAgentDef("caseD",
                "{\"thinking_enabled\":true,\"thinking_budget_tokens\":4000}");
        String skillCode = "report_analysis_h3_d_" + System.nanoTime();
        // Skill says enabled with budget=8000 → must win
        Map<String, Map<String, Object>> skillByCode = skillMap(skillCode,
                "{\"thinking_enabled\":true,\"thinking_budget_tokens\":8000}");

        LlmProvider provider = mockProviderEndTurn();
        List<AgentPlanStep> plan = twoStepPlan(skillCode);

        stepLoopService.executePlanSteps(plan, 0,
                getTestTenant().getId(), "run-h3-d-" + System.nanoTime(),
                "task-h3-d-" + System.nanoTime(),
                (String) agentDef.get("agent_code"),
                "system prompt", "user message",
                java.util.Collections.<com.auraboot.framework.agent.dto.AgentToolDefinition>emptyList(), agentDef, skillByCode,
                provider, anthropicConfig(),
                TraceContext.builder().traceId("trace-h3-d").build(),
                false);

        ArgumentCaptor<LlmChatRequest> reqCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, atLeast(2)).chat(reqCaptor.capture(), anyString(), anyString());
        for (LlmChatRequest req : reqCaptor.getAllValues()) {
            assertThat(req.getThinking()).isNotNull();
            assertThat(req.getThinking().isEnabled()).isTrue();
            assertThat(req.getThinking().getBudgetTokens())
                    .as("skill budget=8000 must override agent budget=4000 (matches F.2 v2 precedence)")
                    .isEqualTo(8000);
        }
    }
}
