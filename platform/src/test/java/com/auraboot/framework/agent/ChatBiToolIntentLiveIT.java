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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-LLM quality measurement for the NEW {@code chat_bi} agent-tool intent path — the coverage
 * migration that lets the legacy {@code ChatBiLlmParser} / {@code ChatBiIntentLiveIT} (now retired) retire
 * (convergence endgame §7,
 * docs/backlog/2026-06-18-aurabot-conversational-viz-convergence-endgame.md).
 *
 * <p>{@code ChatBiIntentLiveIT} (now retired) measured the v1 question parser (NL → aggregation/group-by/field).
 * The endgame moves that capability onto the agent's <strong>native tool-use</strong>: given the
 * {@code chat_bi} tool schema + a model field catalog, does a real model fill {@code modelCode /
 * dimensions / metrics} correctly and grounded in the schema (no hallucinated fields)? This pins
 * the same property at the new layer, so deleting the v1 parser does not drop coverage.
 *
 * <p>Opt-in: {@code @Tag("agent-eval-live")} + {@code DEEPSEEK_API_KEY} (skips without it).
 */
@Slf4j
@Tag("agent-eval-live")
@DisplayName("Live quality: chat_bi tool NL→params (group-by/metric, grounded) vs a real LLM (DeepSeek)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "agent.anthropic.api-key=",
        "agent.llm.stub-mode=false",
})
class ChatBiToolIntentLiveIT extends BaseIntegrationTest {

    private static final String PROVIDER = "deepseek";
    private static final String DELETE_SEED =
            "DELETE FROM ab_cloud_config WHERE service_type='llm' AND provider_code='" + PROVIDER
                    + "' AND config_level='tenant' AND tenant_id=?";

    private static final String MODEL_CODE = "sales_order";
    private static final Set<String> FIELDS =
            Set.of("so_status", "so_region", "so_amount", "so_id");

    /** chat_bi tool schema (mirrors ChatBiSkill.paramsSchema). */
    private static final LlmChatRequest.Tool CHAT_BI_TOOL = LlmChatRequest.Tool.builder()
            .name("chat-bi")
            .description("Aggregate a business model and return a chart. Fill modelCode, the group-by "
                    + "dimensions, and the metrics (field + aggregation) from the user's question.")
            .inputSchema(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "modelCode", Map.of("type", "string"),
                            "dimensions", Map.of("type", "array", "items", Map.of("type", "string")),
                            "metrics", Map.of("type", "array", "items", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "field", Map.of("type", "string"),
                                            "aggregation", Map.of("type", "string",
                                                    "enum", List.of("count", "count_distinct", "sum", "avg", "max", "min"))),
                                    "required", List.of("field", "aggregation")))),
                    "required", List.of("modelCode", "metrics")))
            .build();

    /** A BI question + expected metric aggregation + expected group-by dimension (null = none). */
    private record Case(String question, Set<String> okAgg, String expectDimension) {}

    private static final List<Case> CASES = List.of(
            new Case("How many sales orders are there per status?", Set.of("count", "count_distinct"), "so_status"),
            new Case("What is the total order amount by region?", Set.of("sum"), "so_region"),
            new Case("What is the average order amount overall?", Set.of("avg"), null),
            new Case("Count orders grouped by region.", Set.of("count", "count_distinct"), "so_region"));

    private static final String SYSTEM_PROMPT =
            "You are an analytics agent. The user asks questions about the model '" + MODEL_CODE + "' "
                    + "with fields: so_status (enum), so_region (enum), so_amount (number), so_id (primary key). "
                    + "Use the chat_bi tool to answer. Group by the relevant dimension field, and choose the "
                    + "right metric aggregation. Use so_id with count for row counts. Only reference fields that exist.";

    @Autowired private LlmProviderFactory providerFactory;
    @Autowired private CloudConfigService cloudConfigService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long tenantId;

    @BeforeEach
    void seedDeepSeek() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "DEEPSEEK_API_KEY not set — skipping chat_bi tool intent live measurement");
        tenantId = getTestTenant().getId();
        jdbcTemplate.update(DELETE_SEED, tenantId);
        String configJson = "{"
                + "\"apiKey\":\"" + apiKey + "\","
                + "\"baseUrl\":\"https://api.deepseek.com\","
                + "\"defaultModel\":\"deepseek-chat\","
                + "\"apiFormat\":\"chat_completions\","
                + "\"models\":[\"deepseek-chat\"],"
                + "\"displayName\":\"DeepSeek (chat_bi tool intent live)\""
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
    @DisplayName("real DeepSeek fills chat_bi modelCode/dimensions/metrics, grounded in the schema")
    void chatBiToolParamsQuality() throws Exception {
        LlmProviderFactory.ProviderResolution resolution = providerFactory.resolveProvider(tenantId, PROVIDER);
        org.junit.jupiter.api.Assertions.assertNotNull(resolution, "deepseek provider must resolve");
        LlmProvider provider = resolution.getProvider();
        LlmProviderFactory.ProviderConfig config = resolution.getConfig();
        String model = config.getDefaultModel() != null && !config.getDefaultModel().isBlank()
                ? config.getDefaultModel() : "deepseek-chat";

        int total = 0, called = 0, modelOk = 0, dimOk = 0, aggOk = 0, grounded = 0, pass = 0;
        StringBuilder rows = new StringBuilder();

        for (Case c : CASES) {
            total++;
            LlmChatRequest req = LlmChatRequest.builder()
                    .model(model)
                    .maxTokens(512)
                    .systemPrompt(SYSTEM_PROMPT)
                    .messages(List.of(LlmChatRequest.Message.builder().role("user").content(c.question()).build()))
                    .tools(List.of(CHAT_BI_TOOL))
                    .toolChoice("required")
                    .build();
            LlmChatResponse resp = provider.chat(req, config.getApiKey(), config.getBaseUrl());

            Map<String, Object> input = firstChatBiInput(resp);
            boolean isCalled = input != null;
            String mc = isCalled ? str(input.get("modelCode")) : null;
            List<String> dims = isCalled ? strList(input.get("dimensions")) : List.of();
            List<Map<String, Object>> metrics = isCalled ? mapList(input.get("metrics")) : List.of();
            String agg = metrics.isEmpty() ? null : str(metrics.get(0).get("aggregation"));
            agg = agg == null ? null : agg.toLowerCase();

            boolean isModelOk = MODEL_CODE.equals(mc);
            // expectDimension null = an overall metric → no group-by; else the named dimension present.
            boolean isDimOk = c.expectDimension() == null ? dims.isEmpty() : dims.contains(c.expectDimension());
            boolean isAggOk = agg != null && c.okAgg().contains(agg);
            // grounded: every referenced field (dimensions + metric fields) is a real schema field.
            boolean isGrounded = isCalled
                    && dims.stream().allMatch(FIELDS::contains)
                    && metrics.stream().map(m -> str(m.get("field"))).allMatch(FIELDS::contains);
            boolean isPass = isCalled && isModelOk && isDimOk && isAggOk && isGrounded;

            if (isCalled) called++;
            if (isModelOk) modelOk++;
            if (isDimOk) dimOk++;
            if (isAggOk) aggOk++;
            if (isGrounded) grounded++;
            if (isPass) pass++;

            rows.append(String.format("  %-42s pass=%s | called=%s model=%s dim=%s(exp %s) agg=%-4s grounded=%s%n",
                    c.question(), yn(isPass), yn(isCalled), yn(isModelOk),
                    dims, c.expectDimension(), agg, yn(isGrounded)));
        }

        String report = "\n===== chat_bi TOOL INTENT LIVE (DeepSeek " + model + ", single sample) =====\n"
                + rows
                + String.format("  OVERALL n=%d called=%d model=%d dim=%d agg=%d grounded=%d pass=%d%n",
                        total, called, modelOk, dimOk, aggOk, grounded, pass)
                + "============================================================\n";
        System.out.print(report);
        log.warn(report);

        // Lenient aggregate floors (a competent model clears them; the report is the real signal).
        assertTrue(called == total, "model must call chat_bi for every BI question: " + called + "/" + total);
        assertTrue(grounded == total, "every referenced field must be a real schema field (no hallucination): "
                + grounded + "/" + total);
        assertTrue(aggOk * 100 >= total * 75, "metric aggregation correct below 75% floor: " + aggOk + "/" + total);
        assertTrue(dimOk * 100 >= total * 75, "group-by dimension correct below 75% floor: " + dimOk + "/" + total);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstChatBiInput(LlmChatResponse resp) {
        if (resp == null || resp.getContent() == null) return null;
        for (LlmChatResponse.ContentBlock b : resp.getContent()) {
            if ("tool_use".equals(b.getType()) && "chat-bi".equals(b.getName())) {
                return b.getInput() != null ? b.getInput() : Map.of();
            }
        }
        return null;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().map(ChatBiToolIntentLiveIT::str).filter(java.util.Objects::nonNull).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().filter(x -> x instanceof Map).map(x -> (Map<String, Object>) x).toList();
    }

    private static String yn(boolean b) { return b ? "Y" : "N"; }
}
