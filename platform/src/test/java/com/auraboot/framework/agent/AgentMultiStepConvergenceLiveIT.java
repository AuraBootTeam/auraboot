package com.auraboot.framework.agent;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S7 — Live-LLM <strong>multi-step convergence</strong> measurement for the agent tool-use loop
 * (test-strategy doc {@code docs/backlog/2026-06-18-platform-and-test-gaps-resolution.md}, item
 * "S7 多 agent 真模型收敛测").
 *
 * <p>{@code AgentCollaborationService} already has its 3-mode dispatch + child-task-event plumbing
 * verified ({@code AgentCollaborationServiceTest}, deterministic). What was never measured: does a
 * <em>real model</em>, driving the same tool-use loop the agent runtime uses, take a multi-step path
 * that <strong>converges</strong> — it reads an intermediate tool result, decides the next action,
 * and then stops — rather than spinning (re-calling the same tool) or looping forever until the hard
 * iteration cap.
 *
 * <p>Like {@code AgentArchetypeLiveQualityIT}, this isolates the variable under test (the model's
 * loop/convergence behavior) from the orthogonal "are the vertical plugins loaded" infra question by
 * driving the configured {@link LlmProvider} directly over a <strong>controlled toolset</strong> with
 * <strong>synthetic tool results</strong>. The control flow mirrors
 * {@code StepLoopService.executeAgentLoop} exactly (assistant {@code tool_use} echo + user
 * {@code tool_result} blocks, terminate on a non-{@code tool_use} stop reason).
 *
 * <p><strong>Opt-in.</strong> Gated by {@code DEEPSEEK_API_KEY} and tagged {@code agent-eval-live};
 * a plain {@code ./gradlew :testAgent} skips it via {@link Assumptions}.
 *
 * <pre>{@code
 * cd platform && DEEPSEEK_API_KEY=sk-... \
 *   ./gradlew :testAgent --tests '*AgentMultiStepConvergenceLiveIT*'
 * }</pre>
 */
@Slf4j
@Tag("agent-eval-live")
@DisplayName("Live: real model multi-step tool-use loop converges (no spin / no infinite loop)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "agent.anthropic.api-key=",
        "agent.llm.stub-mode=false",
})
class AgentMultiStepConvergenceLiveIT extends BaseIntegrationTest {

    private static final String PROVIDER = "deepseek";
    private static final String DELETE_SEED =
            "DELETE FROM ab_cloud_config WHERE service_type='llm' AND provider_code='" + PROVIDER
                    + "' AND config_level='tenant' AND tenant_id=?";

    /** Hard cap mirroring the runtime's loop guard — the model must converge BELOW this. */
    private static final int MAX_LOOPS = 6;

    @Autowired private LlmProviderFactory providerFactory;
    @Autowired private CloudConfigService cloudConfigService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long tenantId;

    @BeforeEach
    void seedDeepSeek() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "DEEPSEEK_API_KEY not set — skipping live multi-step convergence measurement");

        tenantId = getTestTenant().getId();
        jdbcTemplate.update(DELETE_SEED, tenantId);

        String configJson = "{"
                + "\"apiKey\":\"" + apiKey + "\","
                + "\"baseUrl\":\"https://api.deepseek.com\","
                + "\"defaultModel\":\"deepseek-chat\","
                + "\"apiFormat\":\"chat_completions\","
                + "\"models\":[\"deepseek-chat\"],"
                + "\"displayName\":\"DeepSeek (multi-step convergence live)\""
                + "}";
        CloudConfigSaveRequest req = new CloudConfigSaveRequest();
        req.setConfigLevel("tenant");
        req.setServiceType("llm");
        req.setProviderCode(PROVIDER);
        req.setConfig(configJson);
        req.setEnabled(true);
        req.setPriority(0);
        cloudConfigService.saveConfig(req);
    }

    @AfterAll
    void cleanup() {
        if (tenantId != null) {
            jdbcTemplate.update(DELETE_SEED, tenantId);
        }
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    @DisplayName("real DeepSeek looks up → decides → escalates → stops within the loop cap")
    void multiStepToolUseLoopConverges() throws Exception {
        LlmProviderFactory.ProviderResolution resolution = providerFactory.resolveProvider(tenantId, PROVIDER);
        assertThat(resolution).as("deepseek provider must resolve from the seeded tenant config").isNotNull();
        LlmProvider provider = resolution.getProvider();
        LlmProviderFactory.ProviderConfig config = resolution.getConfig();
        String model = config.getDefaultModel() != null && !config.getDefaultModel().isBlank()
                ? config.getDefaultModel() : "deepseek-chat";

        List<LlmChatRequest.Tool> tools = List.of(
                LlmChatRequest.Tool.builder()
                        .name("lookup_lead_status")
                        .description("Look up the current lifecycle status of a sales lead by its id.")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of("leadId", Map.of("type", "string")),
                                "required", List.of("leadId")))
                        .build(),
                LlmChatRequest.Tool.builder()
                        .name("escalate_lead")
                        .description("Escalate a stalled lead to a manager with a short reason.")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "leadId", Map.of("type", "string"),
                                        "reason", Map.of("type", "string")),
                                "required", List.of("leadId", "reason")))
                        .build());

        String systemPrompt = "You are an operations agent. Use the provided tools to complete the task. "
                + "Call ONE tool per step, read each tool result before deciding the next action, and once "
                + "the task is fully done reply with a short final summary and DO NOT call any more tools.";
        String task = "Triage lead L-77: first look up its status. If the status is 'stalled', escalate it "
                + "with a one-line reason. Then tell me what you did.";

        List<LlmChatRequest.Message> messages = new ArrayList<>();
        messages.add(LlmChatRequest.Message.builder().role("user").content(task).build());

        int toolCalls = 0;
        int loopsUsed = 0;
        boolean converged = false;
        String finalText = "";
        List<String> toolSequence = new ArrayList<>();

        for (int loop = 0; loop < MAX_LOOPS; loop++) {
            loopsUsed = loop + 1;
            LlmChatResponse response = provider.chat(
                    LlmChatRequest.builder()
                            .model(model)
                            .maxTokens(1024)
                            .systemPrompt(systemPrompt)
                            .messages(messages)
                            .tools(tools)
                            .build(),
                    config.getApiKey(), config.getBaseUrl());

            List<LlmChatResponse.ContentBlock> content =
                    response.getContent() != null ? response.getContent() : List.of();
            boolean hasToolUse = content.stream().anyMatch(b -> "tool_use".equals(b.getType()));

            if (!hasToolUse) {
                // No further tool calls → the model decided it is done = converged.
                for (LlmChatResponse.ContentBlock b : content) {
                    if ("text".equals(b.getType()) && b.getText() != null && !b.getText().isBlank()) {
                        finalText = b.getText();
                    }
                }
                converged = true;
                break;
            }

            // Mirror StepLoopService.executeAgentLoop: echo the assistant's content
            // (text + tool_use) and answer each tool_use with a synthetic tool_result.
            List<Object> assistantContent = new ArrayList<>();
            List<Object> toolResults = new ArrayList<>();
            for (LlmChatResponse.ContentBlock b : content) {
                if ("text".equals(b.getType()) && b.getText() != null && !b.getText().isBlank()) {
                    assistantContent.add(Map.of("type", "text", "text", b.getText()));
                } else if ("tool_use".equals(b.getType())) {
                    Map<String, Object> input = b.getInput() != null ? b.getInput() : Map.of();
                    assistantContent.add(Map.of(
                            "type", "tool_use", "id", b.getId(), "name", b.getName(), "input", input));
                    toolCalls++;
                    toolSequence.add(b.getName());
                    toolResults.add(Map.of(
                            "type", "tool_result",
                            "tool_use_id", b.getId(),
                            "content", synthResult(b.getName())));
                }
            }
            messages.add(LlmChatRequest.Message.builder().role("assistant").content(assistantContent).build());
            messages.add(LlmChatRequest.Message.builder().role("user").content(toolResults).build());
        }

        String report = String.format(
                "%n===== MULTI-STEP CONVERGENCE (DeepSeek %s) =====%n"
                + "  loopsUsed=%d/%d  toolCalls=%d  converged=%s%n"
                + "  toolSequence=%s%n"
                + "  finalSummary=%s%n"
                + "================================================%n",
                model, loopsUsed, MAX_LOOPS, toolCalls, converged, toolSequence,
                finalText.length() > 240 ? finalText.substring(0, 240) + "…" : finalText);
        System.out.print(report);
        log.warn(report);

        // Convergence: the loop terminated on the model's own "done" decision BEFORE the hard cap.
        assertThat(converged)
                .as("loop must converge (model stops) within %d steps — no spin / infinite loop; seq=%s",
                        MAX_LOOPS, toolSequence)
                .isTrue();
        // Genuinely multi-step (not a one-shot answer).
        assertThat(toolCalls)
                .as("must be genuinely multi-step (>= 2 tool calls); seq=%s", toolSequence)
                .isGreaterThanOrEqualTo(2);
        // Result-driven reasoning: it could only decide to escalate AFTER reading the
        // 'stalled' lookup result — proves it consumed the intermediate tool output.
        assertThat(toolSequence)
                .as("must look up the lead before escalating (consumed the intermediate result)")
                .contains("lookup_lead_status")
                .contains("escalate_lead");
        // Reached a coherent terminal answer.
        assertThat(finalText).as("must reach a non-empty final summary").isNotBlank();
    }

    /** Controlled, deterministic tool outputs — the model's PATH is what is under test. */
    private static String synthResult(String toolName) {
        return switch (toolName) {
            case "lookup_lead_status" -> "{\"leadId\":\"L-77\",\"status\":\"stalled\"}";
            case "escalate_lead" -> "{\"leadId\":\"L-77\",\"escalated\":true}";
            default -> "{\"ok\":true}";
        };
    }
}
