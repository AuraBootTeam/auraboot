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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-LLM <strong>adversarial</strong> parameter-extraction measurement. Where
 * {@link AgentFormFillLiveIT} uses clean tasks with explicit literal values (and the model
 * scored 100%), this stress-tests the same native tool-use path with the hard cases that
 * decide whether the agent is actually robust or merely good on easy inputs:
 *
 * <ul>
 *   <li>H1 partial info — must NOT fabricate the missing required field</li>
 *   <li>H2/H3 implicit numbers — "一打"→12, "四千五"→4500</li>
 *   <li>H4 self-correction — "ACME-001,不对是 ACME-002" → must use the corrected value</li>
 *   <li>H5 relative date — "这周五" → resolve to a date or leave blank, not fabricate garbage</li>
 *   <li>H6 distractor — irrelevant chatter must not become a hallucinated field</li>
 *   <li>H7 tool discrimination — pick update vs create from two offered tools</li>
 *   <li>H8 unit-in-value — "120 件" → quantity 120 (strip unit)</li>
 * </ul>
 *
 * <p>Each case carries its own pass predicate (heterogeneous expectations). The printed
 * report is the real deliverable; the aggregate assertion floor is intentionally loose
 * because these are HARD — the point is to surface the real limit, not to manufacture a pass.
 *
 * <p>Opt-in: {@code @Tag("agent-eval-live")} + {@code DEEPSEEK_API_KEY}.
 */
@Slf4j
@Tag("agent-eval-live")
@DisplayName("Live quality: adversarial form-fill parameter extraction vs a real LLM (DeepSeek)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "agent.anthropic.api-key=",
        "agent.llm.stub-mode=false",
})
class AgentFormFillHardLiveIT extends BaseIntegrationTest {

    private static final String PROVIDER = "deepseek";
    private static final String DELETE_SEED =
            "DELETE FROM ab_cloud_config WHERE service_type='llm' AND provider_code='" + PROVIDER
                    + "' AND config_level='tenant' AND tenant_id=?";

    @Autowired private LlmProviderFactory llmProviderFactory;
    @Autowired private CloudConfigService cloudConfigService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long tenantId;

    @FunctionalInterface
    private interface Check {
        boolean ok(boolean didCall, String toolName, Map<String, Object> args);
    }

    private record Scenario(String id, List<LlmChatRequest.Tool> tools, String task,
                            String expectation, Check check) {
    }

    private List<Scenario> scenarios() {
        List<LlmChatRequest.Tool> order = List.of(orderTool());
        List<Scenario> s = new ArrayList<>();

        s.add(new Scenario("H1-partial-no-fabricate", order,
                "给客户 ACME-001 建一张订单,产品 SKU-203。", // quantity omitted (required)
                "fill customer+sku, do NOT fabricate quantity (blank or no-call)",
                (called, tool, a) -> !called
                        || (contains(a.get("customer_id"), "ACME-001") && contains(a.get("sku"), "SKU-203")
                            && !isNonEmpty(a.get("quantity")))));

        s.add(new Scenario("H2-implicit-dozen", order,
                "客户 ACME-001 订 SKU-9,数量一打,交期 2026-08-01。", // 一打 = 12
                "一打 -> quantity 12",
                (called, tool, a) -> called && numEq(a.get("quantity"), 12)));

        s.add(new Scenario("H3-chinese-amount", List.of(returnTool()),
                "客户 ACME-001 退 SKU-203 共 2 件,质量问题,退款四千五。", // 四千五 = 4500
                "四千五 -> refund_amount 4500",
                (called, tool, a) -> called && numEq(a.get("refund_amount"), 4500)));

        s.add(new Scenario("H4-self-correction", List.of(returnTool()),
                "客户 ACME-001,不对,是 ACME-002,要退 SKU-9 共 3 件,质量问题,退款 900。",
                "use corrected customer ACME-002 (not ACME-001)",
                (called, tool, a) -> called && contains(a.get("customer_id"), "ACME-002")
                        && !equalsIgnoreCase(a.get("customer_id"), "ACME-001")));

        s.add(new Scenario("H5-relative-date", order,
                "给客户 ACME-001 建订单,SKU-203,数量 10,交期这周五。", // relative date
                "fill known fields; delivery_date resolved-ISO or blank, not garbage",
                (called, tool, a) -> called && contains(a.get("customer_id"), "ACME-001")
                        && numEq(a.get("quantity"), 10)
                        && (!isNonEmpty(a.get("delivery_date")) || isIsoDate(a.get("delivery_date")))));

        s.add(new Scenario("H6-distractor-field", order,
                "给客户 ACME-001 建订单 SKU-203 数量 10,交期 2026-08-01,顺便说今天天气不错。",
                "ignore chatter; no out-of-schema field (e.g. weather/天气)",
                (called, tool, a) -> called && a.keySet().stream()
                        .allMatch(k -> List.of("customer_id", "sku", "quantity", "delivery_date", "notes").contains(k))));

        s.add(new Scenario("H7-discriminate-update-vs-create",
                List.of(orderTool(), updateOrderTool()),
                "把订单 SO-100 的数量改成 50。",
                "pick update_order (not create_order), order_id SO-100, quantity 50",
                (called, tool, a) -> called && "update_order".equals(tool)
                        && contains(a.get("order_id"), "SO-100") && numEq(a.get("quantity"), 50)));

        s.add(new Scenario("H8-unit-in-value", order,
                "给客户 ACME-001 建订单,SKU-203,数量 120 件,交期 2026-08-01。", // "120 件"
                "120 件 -> quantity 120 (strip unit)",
                (called, tool, a) -> called && numEq(a.get("quantity"), 120)));

        return s;
    }

    @BeforeEach
    void seedDeepSeek() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "DEEPSEEK_API_KEY not set — skipping adversarial form-fill measurement");
        tenantId = getTestTenant().getId();
        jdbcTemplate.update(DELETE_SEED, tenantId);
        String configJson = "{"
                + "\"apiKey\":\"" + apiKey + "\","
                + "\"baseUrl\":\"https://api.deepseek.com\","
                + "\"defaultModel\":\"deepseek-chat\","
                + "\"apiFormat\":\"chat_completions\","
                + "\"models\":[\"deepseek-chat\"],"
                + "\"displayName\":\"DeepSeek (hard form-fill)\""
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
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    @DisplayName("real DeepSeek on adversarial inputs — surface the real limit")
    void adversarialFormFillQuality() throws Exception {
        LlmProviderFactory.ProviderResolution res = llmProviderFactory.resolveProvider(tenantId, PROVIDER);
        assertTrue(res != null && res.getProvider() != null, "DeepSeek provider must resolve");
        LlmProvider provider = res.getProvider();
        LlmProviderFactory.ProviderConfig cfg = res.getConfig();

        List<Scenario> cases = scenarios();
        int pass = 0;
        StringBuilder rows = new StringBuilder();

        for (Scenario sc : cases) {
            LlmChatRequest req = LlmChatRequest.builder()
                    .model(cfg.getDefaultModel())
                    .systemPrompt("You are an enterprise operations assistant. Use the provided tool to act. "
                            + "Only fill a field if the user's message clearly supplies its value — never invent "
                            + "identifiers, quantities, dates, or amounts that were not given. If the user corrects "
                            + "themselves, use the corrected value.")
                    .messages(List.of(LlmChatRequest.Message.text("user", sc.task())))
                    .tools(sc.tools())
                    .toolChoice("auto")
                    .maxTokens(1024)
                    .build();

            LlmChatResponse resp = provider.chat(req, cfg.getApiKey(), cfg.getBaseUrl());
            String[] call = firstToolCall(resp);
            boolean didCall = call != null;
            String toolName = didCall ? call[0] : null;
            @SuppressWarnings("unchecked")
            Map<String, Object> args = didCall ? (Map<String, Object>) lastInput : Map.of();

            boolean ok = sc.check().ok(didCall, toolName, args);
            if (ok) pass++;
            rows.append(String.format("  %-34s pass=%s  [%s]  tool=%s args=%s%n",
                    sc.id(), ok ? "Y" : "N", sc.expectation(),
                    didCall ? toolName : "(no-call)", didCall ? args : "{}"));
        }

        StringBuilder report = new StringBuilder();
        report.append("\n========== ADVERSARIAL FORM-FILL (DeepSeek deepseek-chat, single sample) ==========\n");
        report.append(rows);
        report.append("  --------------------------------------------------------------------------------\n");
        report.append(String.format("  HARD PASS = %d/%d (%.0f%%)%n", pass, cases.size(),
                100.0 * pass / cases.size()));
        report.append("================================================================================\n");
        System.out.print(report);
        log.warn(report.toString());

        // Loose floor: these are HARD on purpose. The report is the signal; we only fail if the
        // model collapses (< half). Anything passing here is a genuine robustness datapoint.
        assertTrue(pass * 2 >= cases.size(),
                "adversarial pass rate collapsed below 50%: " + pass + "/" + cases.size());
    }

    // ---- tools ---------------------------------------------------------------

    private static LlmChatRequest.Tool orderTool() {
        return LlmChatRequest.Tool.builder().name("create_order")
                .description("Create a new sales order for a customer")
                .inputSchema(objectSchema(Map.of(
                        "customer_id", prop("string", "Customer code, e.g. ACME-001"),
                        "sku", prop("string", "Product SKU"),
                        "quantity", prop("integer", "Number of units"),
                        "delivery_date", prop("string", "Requested delivery date, ISO yyyy-MM-dd"),
                        "notes", prop("string", "Optional remarks")),
                        List.of("customer_id", "sku", "quantity"))).build();
    }

    private static LlmChatRequest.Tool updateOrderTool() {
        return LlmChatRequest.Tool.builder().name("update_order")
                .description("Update an existing sales order's quantity")
                .inputSchema(objectSchema(Map.of(
                        "order_id", prop("string", "Existing order id, e.g. SO-100"),
                        "quantity", prop("integer", "New quantity")),
                        List.of("order_id", "quantity"))).build();
    }

    private static LlmChatRequest.Tool returnTool() {
        return LlmChatRequest.Tool.builder().name("create_return")
                .description("Create a product return / refund request")
                .inputSchema(objectSchema(Map.of(
                        "customer_id", prop("string", "Customer code"),
                        "sku", prop("string", "Product SKU"),
                        "quantity", prop("integer", "Units returned"),
                        "refund_amount", prop("number", "Refund amount"),
                        "reason", prop("string", "Return reason")),
                        List.of("customer_id", "sku", "quantity", "refund_amount", "reason"))).build();
    }

    // ---- helpers -------------------------------------------------------------

    /** Side-channel for the matched input map (avoids a wrapper record). */
    private static Map<String, Object> lastInput;

    private static String[] firstToolCall(LlmChatResponse resp) {
        lastInput = null;
        if (resp == null || resp.getContent() == null) return null;
        for (LlmChatResponse.ContentBlock b : resp.getContent()) {
            if ("tool_use".equals(b.getType())) {
                lastInput = b.getInput() != null ? b.getInput() : Map.of();
                return new String[]{b.getName()};
            }
        }
        return null;
    }

    private static boolean isNonEmpty(Object v) {
        return v != null && !v.toString().trim().isEmpty();
    }

    private static boolean contains(Object actual, String expected) {
        return actual != null && actual.toString().trim().toLowerCase()
                .contains(expected.trim().toLowerCase());
    }

    private static boolean equalsIgnoreCase(Object actual, String expected) {
        return actual != null && actual.toString().trim().equalsIgnoreCase(expected.trim());
    }

    private static boolean numEq(Object actual, double expected) {
        if (actual == null) return false;
        if (actual instanceof Number n) return Math.abs(n.doubleValue() - expected) < 1e-6;
        try {
            return Math.abs(Double.parseDouble(actual.toString().trim().replaceAll("[^0-9.\\-]", "")) - expected) < 1e-6;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isIsoDate(Object v) {
        return v != null && v.toString().trim().matches("\\d{4}-\\d{2}-\\d{2}");
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }
}
