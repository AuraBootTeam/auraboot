package com.auraboot.framework.agent;

import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import com.auraboot.framework.agent.entity.AbCapabilityEvalRun;
import com.auraboot.framework.agent.mapper.AbCapabilityEvalRunMapper;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.service.CapabilityEvalService;
import com.auraboot.framework.agent.service.LlmToolSelectionService;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-LLM regression for Agent capability evaluation (retro item A6).
 *
 * <p>Where {@link CapabilityEvalServiceTest} exercises the LLM-mode <em>wiring</em>
 * against the in-process {@code StubLlmProvider}, this drives the eval against a
 * <strong>real</strong> OpenAI-compatible provider so we know model-backed tool
 * selection, JSON parsing, and the selected/hallucinated partition actually work
 * end-to-end — not just the plumbing.
 *
 * <p><strong>Opt-in.</strong> Gated by the {@code DEEPSEEK_API_KEY} env var
 * ({@link Assumptions#assumeTrue}) and tagged {@code agent-eval-live}, so a plain
 * {@code ./gradlew :platform:testAgent} skips it. Any cheap OpenAI-compatible
 * provider works; DeepSeek is the default low-cost choice.
 *
 * <pre>{@code
 * cd platform && DEEPSEEK_API_KEY=sk-... \
 *   ./gradlew :platform:testAgent --tests '*CapabilityEvalLiveIT*'
 * }</pre>
 *
 * <p><strong>Why blank {@code agent.anthropic.api-key}.</strong> The
 * integration-test profile sets it to the stub sentinel, and
 * {@link LlmToolSelectionService} resolves the {@code anthropic} provider
 * <em>first</em>; without blanking it the run would route to the stub instead of
 * the seeded DeepSeek provider. With it blank, the seeded tenant-level DeepSeek
 * config becomes the first (and only) configured provider.
 *
 * <p>The seeded {@code ab_cloud_config} row is tenant-scoped and removed in
 * {@link #cleanup()} (and pre-cleaned in {@link #seedDeepSeek()}) so a real API
 * key never lingers in the shared evaluation DB.
 */
@Tag("agent-eval-live")
@DisplayName("A6: capability eval against a real LLM (DeepSeek) — live regression")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "agent.anthropic.api-key=",
        "agent.llm.stub-mode=false",
})
class CapabilityEvalLiveIT extends BaseIntegrationTest {

    private static final String PROVIDER = "deepseek";
    private static final String DELETE_SEED =
            "DELETE FROM ab_cloud_config WHERE service_type='llm' AND provider_code='" + PROVIDER
                    + "' AND config_level='tenant' AND tenant_id=?";

    @Autowired private CapabilityEvalService capabilityEvalService;
    @Autowired private LlmToolSelectionService llmToolSelectionService;
    @Autowired private LlmProviderFactory llmProviderFactory;
    @Autowired private CloudConfigService cloudConfigService;
    @Autowired private AbCapabilityEvalRunMapper evalRunMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long tenantId;

    // Seed in @BeforeEach (not @BeforeAll): BaseIntegrationTest populates the
    // test tenant and MetaContext in its own @BeforeEach, which runs before this
    // one — getTestTenant() is null during @BeforeAll. The leading DELETE keeps
    // it idempotent across the per-test re-seed.
    @BeforeEach
    void seedDeepSeek() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "DEEPSEEK_API_KEY not set — skipping live-LLM eval regression (A6)");

        tenantId = getTestTenant().getId();
        jdbcTemplate.update(DELETE_SEED, tenantId); // idempotent: clear any prior seed

        String configJson = "{"
                + "\"apiKey\":\"" + apiKey + "\","
                + "\"baseUrl\":\"https://api.deepseek.com\","
                + "\"defaultModel\":\"deepseek-chat\","
                + "\"apiFormat\":\"chat_completions\","
                + "\"models\":[\"deepseek-chat\"],"
                + "\"displayName\":\"DeepSeek (A6 live eval)\""
                + "}";

        // MetaContext (tenant/user) is already set by BaseIntegrationTest#setUp,
        // so saveConfig stamps the correct tenant_id / created_by.
        CloudConfigSaveRequest req = new CloudConfigSaveRequest();
        req.setConfigLevel("tenant"); // tenant-scoped so it sorts ahead of any platform provider
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

    /** The seeded provider must actually be what the selection path resolves to. */
    @Test
    @Order(1)
    @DisplayName("seeded DeepSeek is the resolved provider (anthropic blanked)")
    void seededProviderResolvesToDeepSeek() {
        assertTrue(llmToolSelectionService.isAvailable(tenantId),
                "an LLM provider must be available after seeding DeepSeek");

        LlmProviderFactory.ProviderConfig resolved = llmProviderFactory.resolveConfig(tenantId, null);
        assertNotNull(resolved, "first-available provider config must resolve");
        assertEquals(PROVIDER, resolved.getProviderCode(),
                "the resolved provider must be the seeded DeepSeek, not the stub/anthropic fallback");
        assertTrue(resolved.getBaseUrl() != null && resolved.getBaseUrl().contains("deepseek"),
                "resolved baseUrl must point at DeepSeek, was: " + resolved.getBaseUrl());
    }

    /**
     * Real model-driven selection from a controlled catalog: the model must pick
     * the create-order tool, every selected code must come from the catalog, and
     * any hallucinated code must be partitioned out (never in the catalog).
     */
    @Test
    @Order(2)
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    @DisplayName("real DeepSeek selects the right tool from a controlled catalog + partitions hallucinations")
    void liveToolSelectionPicksFromCatalog() throws Exception {
        List<ToolDefinition> catalog = List.of(
                tool("cmd_create_order", "Create a brand new sales order for a customer", "L2"),
                tool("cmd_cancel_order", "Cancel an existing sales order", "L3"),
                tool("nq_list_orders", "List existing orders for a customer", "L0"),
                tool("cmd_update_customer", "Update a customer's profile details", "L2"));

        LlmToolSelectionService.Selection selection = llmToolSelectionService.selectTools(
                tenantId, "I want to place a brand new sales order for a customer.", catalog, 5);

        assertNotNull(selection, "selection must not be null");
        assertFalse(selection.selected().isEmpty(),
                "a real model must select at least one tool for a clear task");
        List<String> catalogCodes = catalog.stream().map(ToolDefinition::getToolCode).toList();
        assertTrue(catalogCodes.containsAll(selection.selected()),
                "every selected code must come from the catalog, got: " + selection.selected());
        assertTrue(selection.selected().contains("cmd_create_order"),
                "the create-order tool must be selected for a create-order task, got: " + selection.selected());
        for (String h : selection.hallucinated()) {
            assertFalse(catalogCodes.contains(h),
                    "a code partitioned as hallucinated must not be in the catalog: " + h);
        }
    }

    /**
     * Full eval pipeline in LLM mode against the real provider: the report must
     * be labeled {@code llm} (proving the provider was genuinely consulted, not
     * degraded to keyword) and a run row must persist with {@code eval_mode=llm}.
     */
    @Test
    @Order(3)
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @DisplayName("full eval runs in llm mode against DeepSeek and persists eval_mode=llm")
    void liveEvalRunsInLlmModeAndPersists() {
        List<CapabilityEvalCase> cases = List.of(
                CapabilityEvalCase.builder()
                        .caseId("LIVE-001")
                        .taskDescription("Create a new sales order for a customer")
                        .expectedToolCodes(List.of("cmd_create_order"))
                        .category("tool_selection")
                        .expectedRiskLevel("L2")
                        .expectsConfirmation(false)
                        .build(),
                CapabilityEvalCase.builder()
                        .caseId("LIVE-002")
                        .taskDescription("Cancel an existing sales order")
                        .expectedToolCodes(List.of("cmd_cancel_order"))
                        .category("tool_selection")
                        .expectedRiskLevel("L3")
                        .expectsConfirmation(true)
                        .build());

        long countBefore = evalRunMapper.selectCount(
                new LambdaQueryWrapper<AbCapabilityEvalRun>().eq(AbCapabilityEvalRun::getTenantId, tenantId));

        Map<String, Object> report = capabilityEvalService.evaluateToolSelection(tenantId, "llm", cases);

        assertNotNull(report, "eval report must not be null");
        assertEquals("llm", report.get("evalMode"),
                "with a real provider configured the run must stay in llm mode, not degrade to keyword");
        assertEquals(cases.size(), ((Number) report.get("totalCases")).intValue());
        assertNotNull(report.get("weightedScore"), "report must carry a weighted score");

        long countAfter = evalRunMapper.selectCount(
                new LambdaQueryWrapper<AbCapabilityEvalRun>().eq(AbCapabilityEvalRun::getTenantId, tenantId));
        assertTrue(countAfter > countBefore, "a new eval run must be persisted");

        AbCapabilityEvalRun latest = evalRunMapper.selectList(
                new LambdaQueryWrapper<AbCapabilityEvalRun>()
                        .eq(AbCapabilityEvalRun::getTenantId, tenantId)
                        .orderByDesc(AbCapabilityEvalRun::getRunAt)
                        .last("LIMIT 1")).get(0);
        assertEquals("llm", latest.getEvalMode(),
                "the persisted run must record eval_mode=llm (honest mode label)");
    }

    private static ToolDefinition tool(String code, String description, String risk) {
        return ToolDefinition.builder()
                .toolCode(code)
                .toolName(code)
                .description(description)
                .toolType("dsl_command")
                .riskLevel(risk)
                .build();
    }
}
