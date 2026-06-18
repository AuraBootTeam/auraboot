package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.dashboard.dto.DashboardDTO;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
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
 * S5 — live-LLM quality measurement for NL → dashboard generation, end-to-end through the real
 * {@link DashboardGeneratorSkill}. Drives the skill's {@link DashboardGeneratorSkill#paramsSchema()}
 * as a native tool-use inputSchema to a real DeepSeek model, then proves the generated DSL is a legal,
 * persistable dashboard: code + title present, ≥3 widgets, every widget kind in the renderable set,
 * no hallucinated widget type — and finally {@code execute}s it so a real dashboard row is created and
 * re-readable by code (the platform path that did not exist before).
 *
 * <p>Opt-in: gated by {@code DEEPSEEK_API_KEY}, tagged {@code agent-eval-live}. After running, redact
 * {@code $DEEPSEEK_API_KEY} from build/reports + task outputs (seed INSERT lands in MyBatis DEBUG SQL).
 */
@Slf4j
@Tag("agent-eval-live")
@DisplayName("Live quality: NL → dashboard generation via dashboard:create skill (real DeepSeek)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "agent.anthropic.api-key=",
        "agent.llm.stub-mode=false",
})
class DashboardGenerationLiveIT extends BaseIntegrationTest {

    private static final String PROVIDER = "deepseek";
    private static final String DELETE_SEED =
            "DELETE FROM ab_cloud_config WHERE service_type='llm' AND provider_code='" + PROVIDER
                    + "' AND config_level='tenant' AND tenant_id=?";
    private static final Set<String> WIDGET_TYPES = Set.of(
            "smart-bar-chart", "smart-line-chart", "smart-pie-chart",
            "smart-number-card", "smart-table-chart", "smart-rich-text");

    @Autowired private LlmProviderFactory llmProviderFactory;
    @Autowired private CloudConfigService cloudConfigService;
    @Autowired private DashboardGeneratorSkill skill;
    @Autowired private DashboardService dashboardService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long tenantId;
    private String createdCode;

    @BeforeEach
    void seedDeepSeek() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "DEEPSEEK_API_KEY not set — skipping live dashboard-generation quality measurement");
        tenantId = getTestTenant().getId();
        MetaContext.setContext(tenantId, getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
        jdbcTemplate.update(DELETE_SEED, tenantId);

        String configJson = "{"
                + "\"apiKey\":\"" + apiKey + "\","
                + "\"baseUrl\":\"https://api.deepseek.com\","
                + "\"defaultModel\":\"deepseek-chat\","
                + "\"apiFormat\":\"chat_completions\","
                + "\"models\":[\"deepseek-chat\"],"
                + "\"displayName\":\"DeepSeek (dashboard-gen live quality)\""
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
        try {
            if (createdCode != null) {
                jdbcTemplate.update("UPDATE ab_dashboard SET deleted_flag = true WHERE code = ?", createdCode);
                jdbcTemplate.update("DELETE FROM ab_dashboard WHERE code = ?", createdCode);
            }
            if (tenantId != null) jdbcTemplate.update(DELETE_SEED, tenantId);
        } catch (Exception ignored) {}
    }

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    @DisplayName("real DeepSeek emits a legal multi-widget dashboard DSL that persists end-to-end")
    void nlToDashboard_generatesLegalDsl_andPersists() throws Exception {
        LlmProviderFactory.ProviderResolution res = llmProviderFactory.resolveProvider(tenantId, PROVIDER);
        assertTrue(res != null && res.getProvider() != null, "DeepSeek provider must resolve");
        LlmProvider provider = res.getProvider();
        LlmProviderFactory.ProviderConfig cfg = res.getConfig();

        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = objectMapper.convertValue(skill.paramsSchema(), Map.class);
        LlmChatRequest.Tool tool = LlmChatRequest.Tool.builder()
                .name("create_dashboard").description("Create a dashboard from the user's request").inputSchema(inputSchema).build();

        String task = "请帮我做一个销售总览看板(dashboard code 用 sales_overview):需要一个显示总销售额的数字卡、"
                + "一个按地区统计销售额的柱状图、一个按月销售趋势的折线图,以及一个 Top 10 客户的表格。";
        LlmChatRequest req = LlmChatRequest.builder()
                .model(cfg.getDefaultModel())
                .systemPrompt("You are a BI assistant. Use the tool to lay out a dashboard that answers the "
                        + "user's request: pick an appropriate widget type per metric (number-card for a single "
                        + "total, bar/line/pie for aggregations, table for row lists). Give each widget a clear title.")
                .messages(List.of(LlmChatRequest.Message.text("user", task)))
                .tools(List.of(tool))
                .toolChoice("auto")
                .maxTokens(1500)
                .build();

        LlmChatResponse resp = provider.chat(req, cfg.getApiKey(), cfg.getBaseUrl());
        Map<String, Object> args = firstToolInput(resp, "create_dashboard");
        assertTrue(args != null, "model must call create_dashboard");

        JsonNode gen = objectMapper.valueToTree(args);
        StringBuilder report = new StringBuilder("\n===== NL → DASHBOARD GENERATION (DeepSeek, single sample) =====\n");
        String genCode = gen.path("code").asText(null);
        String genTitle = gen.path("title").asText(null);
        JsonNode widgets = gen.get("widgets");
        int widgetCount = widgets != null && widgets.isArray() ? widgets.size() : 0;
        boolean allTypesLegal = widgets != null && widgets.isArray();
        boolean allHaveTitle = widgets != null && widgets.isArray();
        StringBuilder types = new StringBuilder();
        if (widgets != null && widgets.isArray()) {
            for (JsonNode w : widgets) {
                String t = w.path("type").asText("");
                types.append(t).append(" ");
                if (!WIDGET_TYPES.contains(t)) allTypesLegal = false;
                if (w.path("title").asText("").isBlank()) allHaveTitle = false;
            }
        }
        report.append(String.format("  code=%s title=%s widgets=%d types=[%s]%n", genCode, genTitle, widgetCount, types.toString().trim()));
        report.append(String.format("  allTypesLegal=%s allHaveTitle=%s%n", allTypesLegal, allHaveTitle));

        // generation-quality floors
        assertTrue(genCode != null && !genCode.isBlank(), "generated dashboard must have a code");
        assertTrue(genTitle != null && !genTitle.isBlank(), "generated dashboard must have a title");
        assertTrue(widgetCount >= 3, "expected >=3 widgets for a 4-metric request, got " + widgetCount);
        assertTrue(allTypesLegal, "model emitted an out-of-schema widget type: " + types);
        assertTrue(allHaveTitle, "every widget must have a title");

        // end-to-end: the generated DSL must actually persist through the real skill
        createdCode = genCode;
        SkillResult skillRes = skill.execute(SkillRequest.builder().skillName("dashboard:create").params(gen).build());
        assertTrue(skillRes.getStatus() == SkillResult.Status.SUCCESS, "generated dashboard must persist via the skill");
        DashboardDTO dto = dashboardService.findByCode(genCode);
        assertTrue(dto != null && dto.getWidgets() != null && dto.getWidgets().size() == widgetCount,
                "persisted dashboard must be re-readable with all generated widgets");
        report.append(String.format("  PERSISTED pid=%s widgets=%d  <-- NL → dashboard path closed end-to-end%n",
                dto.getPid(), dto.getWidgets().size()));
        report.append("==============================================================\n");
        System.out.print(report);
        log.warn(report.toString());
    }

    private static Map<String, Object> firstToolInput(LlmChatResponse resp, String toolName) {
        if (resp == null || resp.getContent() == null) return null;
        for (LlmChatResponse.ContentBlock b : resp.getContent()) {
            if ("tool_use".equals(b.getType()) && toolName.equals(b.getName())) {
                return b.getInput() != null ? b.getInput() : Map.of();
            }
        }
        return null;
    }
}
