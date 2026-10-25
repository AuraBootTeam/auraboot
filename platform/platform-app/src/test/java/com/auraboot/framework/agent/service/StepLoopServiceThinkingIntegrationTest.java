package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.trace.TraceContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-2 Extended Thinking — full-stack integration test.
 *
 * <p>Spring is bootstrapped via {@link BaseIntegrationTest} so the real
 * PostgreSQL/Redis stack is in scope; agent definitions are written to
 * {@code ab_agent_definition} via {@link JdbcTemplate} and the autowired
 * {@link StepLoopService} pulls them through {@link DynamicDataMapper}.
 * The downstream {@link LlmProvider} is a Mockito mock — we want to assert
 * exactly what request the agent loop produced for the LLM, not exercise
 * Anthropic itself.
 *
 * <p>This test was previously a pure unit test ({@code mock(LlmProvider.class) +
 * hand-wired StepLoopService}). It was renamed but never actually integrated;
 * the P0-2 Blocker B1 requires real DB-backed proof that the
 * {@code execution_config} JSONB column round-trips into the LlmChatRequest,
 * so we now extend BaseIntegrationTest. Test method naming follows the
 * "behaviourBeingValidated_expectedOutcome" convention.
 */
@DisplayName("P0-2: StepLoopService Extended Thinking propagation")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StepLoopServiceThinkingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private StepLoopService stepLoopService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Insert a fresh ab_agent_definition row keyed by a unique agent_code so
     * each test gets isolated state; the @Transactional(NOT_SUPPORTED) class
     * annotation means rows persist after the method, so the unique code
     * pattern guarantees no collisions across runs.
     */
    private Map<String, Object> insertAgentDef(String suffix, String executionConfigJson) {
        String pid = UniqueIdGenerator.generate();
        String agentCode = "test_thinking_" + suffix + "_" + System.nanoTime();
        Long tenantId = getTestTenant().getId();

        jdbcTemplate.update(
                "INSERT INTO ab_agent_definition (pid, tenant_id, agent_code, name, description, "
                        + "agent_type, model, status, visibility, execution_config, deleted_flag, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, FALSE, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                pid, tenantId, agentCode, "Test Thinking Agent " + suffix,
                "Integration test agent for Extended Thinking propagation",
                "reactive", "claude-sonnet-4-6", "active", "tenant",
                executionConfigJson != null ? executionConfigJson : "{}");

        // Reload via the same path StepLoopService uses so the Map shape
        // matches production (column names lower-cased by JDBC, JSONB returned
        // as a PGobject -> coerced to String by the resolver).
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

    private LlmProvider mockProviderReturningEndTurn() throws Exception {
        LlmProvider provider = mock(LlmProvider.class);
        LlmChatResponse done = LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text").text("ok").build()))
                .inputTokens(10).outputTokens(5)
                .build();
        when(provider.chat(any(LlmChatRequest.class), anyString(), anyString())).thenReturn(done);
        return provider;
    }

    // =========================================================================
    // B1 closure — the row that production would actually find via SELECT *
    // FROM ab_agent_definition must surface execution_config.thinking_enabled
    // into the LlmChatRequest.
    // =========================================================================

    @Test
    @DisplayName("thinking_enabledViaExecutionConfigJsonb_propagatesToProvider")
    void thinking_enabledViaExecutionConfigJsonb_propagatesToProvider() throws Exception {
        Map<String, Object> executionConfig = new HashMap<>();
        executionConfig.put("thinking_enabled", true);
        executionConfig.put("thinking_budget_tokens", 8_000);
        Map<String, Object> agentDef = insertAgentDef("enabled",
                objectMapper.writeValueAsString(executionConfig));

        LlmProvider provider = mockProviderReturningEndTurn();

        stepLoopService.executeAgentLoop(
                getTestTenant().getId(), "run-pid-" + System.nanoTime(),
                "task-pid-" + System.nanoTime(), (String) agentDef.get("agent_code"),
                "system prompt", "user message",
                List.of(), agentDef, provider, anthropicConfig(),
                TraceContext.builder().traceId("trace-test-b1").build());

        ArgumentCaptor<LlmChatRequest> reqCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, times(1)).chat(reqCaptor.capture(), anyString(), anyString());

        LlmChatRequest captured = reqCaptor.getValue();
        assertThat(captured.getThinking())
                .as("ab_agent_definition.execution_config.thinking_enabled must propagate "
                        + "into the LlmChatRequest emitted by StepLoopService")
                .isNotNull();
        assertThat(captured.getThinking().isEnabled()).isTrue();
        assertThat(captured.getThinking().getBudgetTokens()).isEqualTo(8_000);
    }

    // =========================================================================
    // Negative cases — original three pre-rename scenarios, but now backed by
    // real DB rows so Spring + JSONB round-trip are part of the assertion.
    // =========================================================================

    @Test
    @DisplayName("thinking_absentExecutionConfig_omitsThinking")
    void thinking_absentExecutionConfig_omitsThinking() throws Exception {
        // Insert with empty {} execution_config — equivalent to "no opt-in"
        Map<String, Object> agentDef = insertAgentDef("absent", "{}");

        LlmProvider provider = mockProviderReturningEndTurn();

        stepLoopService.executeAgentLoop(
                getTestTenant().getId(), "run-pid-" + System.nanoTime(),
                "task-pid-" + System.nanoTime(), (String) agentDef.get("agent_code"),
                "system", "msg", List.of(), agentDef, provider, anthropicConfig(),
                TraceContext.builder().traceId("trace-test-absent").build());

        ArgumentCaptor<LlmChatRequest> reqCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, times(1)).chat(reqCaptor.capture(), anyString(), anyString());
        assertThat(reqCaptor.getValue().getThinking())
                .as("Empty execution_config (no thinking_enabled key) must NOT add a thinking field")
                .isNull();
    }

    @Test
    @DisplayName("thinking_explicitlyDisabled_omitsThinking")
    void thinking_explicitlyDisabled_omitsThinking() throws Exception {
        Map<String, Object> executionConfig = new HashMap<>();
        executionConfig.put("thinking_enabled", false);
        executionConfig.put("thinking_budget_tokens", 8_000);
        Map<String, Object> agentDef = insertAgentDef("disabled",
                objectMapper.writeValueAsString(executionConfig));

        LlmProvider provider = mockProviderReturningEndTurn();

        stepLoopService.executeAgentLoop(
                getTestTenant().getId(), "run-pid-" + System.nanoTime(),
                "task-pid-" + System.nanoTime(), (String) agentDef.get("agent_code"),
                "system", "msg", List.of(), agentDef, provider, anthropicConfig(),
                TraceContext.builder().traceId("trace-test-disabled").build());

        ArgumentCaptor<LlmChatRequest> reqCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, times(1)).chat(reqCaptor.capture(), anyString(), anyString());
        assertThat(reqCaptor.getValue().getThinking())
                .as("thinking_enabled=false must NOT add a thinking field")
                .isNull();
    }

    // =========================================================================
    // F.2 wiring: per-skill execution_config merge over per-agent
    // (skill keys win, null skill = back-compat agent-only behavior).
    // These tests exercise resolveThinkingConfig(agentDef, skillDef) directly
    // because LlmProvider invocation is already covered above; the merge
    // logic itself is the unit of behaviour we need to lock in.
    // =========================================================================

    /**
     * F.2 case 1: skill carries thinking_enabled=true while the agent has no
     * opt-in. The merged config must enable thinking — this is the primary
     * production path for the seeded {@code report_analysis} skill.
     */
    @Test
    @DisplayName("merge_skillEnablesThinking_agentEmpty_thinkingEnabled")
    void merge_skillEnablesThinking_agentEmpty_thinkingEnabled() {
        Map<String, Object> agentDef = new HashMap<>();
        agentDef.put("execution_config", "{}");
        Map<String, Object> skillDef = new HashMap<>();
        skillDef.put("execution_config",
                "{\"thinking_enabled\":true,\"thinking_budget_tokens\":8000}");

        LlmChatRequest.ThinkingConfig resolved =
                stepLoopService.resolveThinkingConfig(agentDef, skillDef);

        assertThat(resolved)
                .as("skill-level thinking_enabled must surface even when agent execution_config is empty")
                .isNotNull();
        assertThat(resolved.isEnabled()).isTrue();
        assertThat(resolved.getBudgetTokens()).isEqualTo(8000);
    }

    /**
     * F.2 case 2: agent carries the legacy thinking_enabled while skill is
     * empty. Behavior must match today's agent-only path so existing P0-2
     * deployments do not regress.
     */
    @Test
    @DisplayName("merge_agentEnablesThinking_skillEmpty_thinkingEnabled")
    void merge_agentEnablesThinking_skillEmpty_thinkingEnabled() {
        Map<String, Object> agentDef = new HashMap<>();
        agentDef.put("execution_config",
                "{\"thinking_enabled\":true,\"thinking_budget_tokens\":12000}");
        Map<String, Object> skillDef = new HashMap<>();
        skillDef.put("execution_config", "{}");

        LlmChatRequest.ThinkingConfig resolved =
                stepLoopService.resolveThinkingConfig(agentDef, skillDef);

        assertThat(resolved)
                .as("agent-level thinking_enabled must propagate when skill carries no opt-in (back-compat)")
                .isNotNull();
        assertThat(resolved.isEnabled()).isTrue();
        assertThat(resolved.getBudgetTokens()).isEqualTo(12000);
    }

    /**
     * F.2 case 3: both sides carry thinking_budget_tokens — skill must win.
     * Asserts the documented merge precedence (skill overlays agent).
     */
    @Test
    @DisplayName("merge_skillBudgetOverridesAgentBudget_skillWins")
    void merge_skillBudgetOverridesAgentBudget_skillWins() {
        Map<String, Object> agentDef = new HashMap<>();
        agentDef.put("execution_config",
                "{\"thinking_enabled\":true,\"thinking_budget_tokens\":4000}");
        Map<String, Object> skillDef = new HashMap<>();
        skillDef.put("execution_config",
                "{\"thinking_enabled\":true,\"thinking_budget_tokens\":8000}");

        LlmChatRequest.ThinkingConfig resolved =
                stepLoopService.resolveThinkingConfig(agentDef, skillDef);

        assertThat(resolved).isNotNull();
        assertThat(resolved.isEnabled()).isTrue();
        assertThat(resolved.getBudgetTokens())
                .as("skill-level thinking_budget_tokens must override agent-level value")
                .isEqualTo(8000);
    }

    /**
     * F.2 case 4: skill missing entirely (null) must fall through to
     * agent-only behavior — the AgentRunService non-skill path keeps
     * working untouched.
     */
    @Test
    @DisplayName("merge_skillNull_fallsThroughToAgent")
    void merge_skillNull_fallsThroughToAgent() {
        Map<String, Object> agentDef = new HashMap<>();
        agentDef.put("execution_config",
                "{\"thinking_enabled\":true,\"thinking_budget_tokens\":6000}");

        LlmChatRequest.ThinkingConfig resolved =
                stepLoopService.resolveThinkingConfig(agentDef, null);

        assertThat(resolved)
                .as("null skillDef must preserve agent-only resolution (back-compat)")
                .isNotNull();
        assertThat(resolved.isEnabled()).isTrue();
        assertThat(resolved.getBudgetTokens()).isEqualTo(6000);
    }

    /**
     * F.2 case 5 — full LLM-request integration: insert a real
     * ab_agent_definition row with empty execution_config plus a hand-built
     * skill row map carrying thinking_enabled=true. Drive the full
     * executeAgentLoop entry point that SkillEngine.executeOrchestration
     * uses, then inspect the captured LlmChatRequest. This is the test
     * that proves the end-to-end wiring (not just unit merge) actually
     * surfaces ThinkingConfig.enabled=true at the LLM provider boundary.
     */
    @Test
    @DisplayName("executeAgentLoop_skillEnablesThinking_propagatesToProvider")
    void executeAgentLoop_skillEnablesThinking_propagatesToProvider() throws Exception {
        // Agent has no thinking opt-in
        Map<String, Object> agentDef = insertAgentDef("merge", "{}");

        // Skill row mimics the seeded report_analysis shape (string JSON in
        // execution_config, like JdbcTemplate-loaded JSONB)
        Map<String, Object> skillDef = new HashMap<>();
        skillDef.put("skill_code", "report_analysis_test_" + System.nanoTime());
        skillDef.put("execution_config",
                "{\"thinking_enabled\":true,\"thinking_budget_tokens\":8000}");

        LlmProvider provider = mockProviderReturningEndTurn();

        stepLoopService.executeAgentLoop(
                getTestTenant().getId(), "run-pid-" + System.nanoTime(),
                "task-pid-" + System.nanoTime(), (String) agentDef.get("agent_code"),
                "system", "msg", List.of(), agentDef, skillDef,
                provider, anthropicConfig(),
                TraceContext.builder().traceId("trace-test-merge").build(),
                StepLoopService.MAX_TOOL_LOOPS);

        ArgumentCaptor<LlmChatRequest> reqCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, times(1)).chat(reqCaptor.capture(), anyString(), anyString());

        LlmChatRequest captured = reqCaptor.getValue();
        assertThat(captured.getThinking())
                .as("F.2: ab_agent_skill.execution_config.thinking_enabled must surface in LlmChatRequest "
                        + "even when ab_agent_definition.execution_config is empty")
                .isNotNull();
        assertThat(captured.getThinking().isEnabled()).isTrue();
        assertThat(captured.getThinking().getBudgetTokens()).isEqualTo(8_000);
    }
}
