package com.auraboot.framework.agent;

import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
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
 * Live-LLM <strong>parameter-extraction</strong> quality measurement for the form-fill /
 * record-creation scenario — the decisive "can the enterprise trust the agent to create
 * a record / fill a form on its own?" gate. Where {@link AgentArchetypeLiveQualityIT}
 * measures tool <em>selection</em>, this measures whether the real model fills the right
 * fields with the right values, completes the required set, invents no fields, and — most
 * importantly (F6) — does <em>not</em> fabricate required values when the task omits them.
 *
 * <p><strong>Faithful path.</strong> This drives the same path the runtime uses
 * ({@code ChatTurnRuntime.runToolLoop}): native tool-use — a {@link LlmChatRequest} carrying
 * tools with JSON {@code inputSchema} is sent to the real provider via
 * {@link LlmProvider#chat}, and the model's arguments are read from the {@code tool_use}
 * content block's {@code input} map. (It does NOT use {@code LlmToolSelectionService}, which
 * is a JSON-text tool-<em>selection</em> path that produces no arguments.)
 *
 * <p><strong>Opt-in.</strong> Gated by {@code DEEPSEEK_API_KEY}, tagged {@code agent-eval-live};
 * a plain {@code ./gradlew :testAgent} skips it. The report printed to stdout carries the real
 * numbers; assertions are lenient aggregate floors (a competent model clears them).
 *
 * <pre>{@code
 * cd platform && DEEPSEEK_API_KEY=sk-... \
 *   ./gradlew :testAgent --tests '*AgentFormFillLiveIT*'
 * }</pre>
 */
@Slf4j
@Tag("agent-eval-live")
@DisplayName("Live quality: form-fill parameter extraction vs a real LLM (DeepSeek)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "agent.anthropic.api-key=",
        "agent.llm.stub-mode=false",
})
class AgentFormFillLiveIT extends BaseIntegrationTest {

    private static final String PROVIDER = "deepseek";
    private static final String DELETE_SEED =
            "DELETE FROM ab_cloud_config WHERE service_type='llm' AND provider_code='" + PROVIDER
                    + "' AND config_level='tenant' AND tenant_id=?";

    @Autowired private LlmProviderFactory llmProviderFactory;
    @Autowired private CloudConfigService cloudConfigService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long tenantId;

    /** A form-fill scenario: an NL business task, a tool + schema, and the expected arguments. */
    private record Scenario(String id, String toolName, String toolDescription,
                            Map<String, Object> schema, List<String> required,
                            String task, Map<String, Object> expected, boolean negative) {
    }

    private List<Scenario> scenarios() {
        List<Scenario> s = new ArrayList<>();

        s.add(new Scenario("F1-create-order", "create_order",
                "Create a new sales order for a customer",
                objectSchema(Map.of(
                        "customer_id", prop("string", "Customer code, e.g. ACME-001"),
                        "sku", prop("string", "Product SKU"),
                        "quantity", prop("integer", "Number of units"),
                        "delivery_date", prop("string", "Requested delivery date, ISO yyyy-MM-dd"),
                        "notes", prop("string", "Optional remarks")),
                        List.of("customer_id", "sku", "quantity", "delivery_date")),
                List.of("customer_id", "sku", "quantity", "delivery_date"),
                "给客户 ACME-001 建一张订单:产品 SKU-203,数量 120,期望交期 2026-07-01,备注加急。",
                Map.of("customer_id", "ACME-001", "sku", "SKU-203", "quantity", 120,
                        "delivery_date", "2026-07-01"),
                false));

        s.add(new Scenario("F2-create-complaint", "create_complaint",
                "Register a customer complaint",
                objectSchema(Map.of(
                        "customer", prop("string", "Customer name or code"),
                        "description", prop("string", "Complaint description"),
                        "affected_qty", prop("integer", "Number of affected units"),
                        "sla_days", prop("integer", "Resolution SLA in days")),
                        List.of("customer", "description")),
                List.of("customer", "description"),
                "登记一条客诉:客户 King Manufacturing 反馈上周收到的 3 台设备里有 2 台开机黑屏,要求 7 天内处理。",
                Map.of("customer", "King Manufacturing", "affected_qty", 2, "sla_days", 7),
                false));

        s.add(new Scenario("F3-update-customer", "update_customer",
                "Update a customer's contact details",
                objectSchema(Map.of(
                        "customer_id", prop("string", "Customer code"),
                        "phone", prop("string", "New phone number"),
                        "email", prop("string", "New email address")),
                        List.of("customer_id")),
                List.of("customer_id"),
                "把客户 ACME-001 的电话改成 13800138000,邮箱改成 a@acme.com。",
                Map.of("customer_id", "ACME-001", "phone", "13800138000", "email", "a@acme.com"),
                false));

        s.add(new Scenario("F4-create-work-order", "create_work_order",
                "Create an equipment maintenance work order",
                objectSchema(Map.of(
                        "production_line", prop("string", "Production line code"),
                        "equipment_code", prop("string", "Equipment code"),
                        "priority", propEnum("Priority", List.of("low", "medium", "high")),
                        "description", prop("string", "Issue description")),
                        List.of("production_line", "equipment_code", "priority")),
                List.of("production_line", "equipment_code", "priority"),
                "产线 L3 的贴片机 SMT-7 报警停机了,优先级高,赶紧建个工单。",
                Map.of("production_line", "L3", "equipment_code", "SMT-7", "priority", "high"),
                false));

        s.add(new Scenario("F5-create-return", "create_return",
                "Create a product return / refund request",
                objectSchema(Map.of(
                        "customer_id", prop("string", "Customer code"),
                        "sku", prop("string", "Product SKU"),
                        "quantity", prop("integer", "Units returned"),
                        "refund_amount", prop("number", "Refund amount"),
                        "reason", prop("string", "Return reason")),
                        List.of("customer_id", "sku", "quantity", "refund_amount", "reason")),
                List.of("customer_id", "sku", "quantity", "refund_amount", "reason"),
                "客户 ACME-001 要退货:SKU-203 共 5 件,质量问题,退款金额 4500 元。",
                Map.of("customer_id", "ACME-001", "sku", "SKU-203", "quantity", 5, "refund_amount", 4500),
                false));

        // F6 (negative): the task omits sku / quantity / customer. A trustworthy model must NOT
        // fabricate required values out of thin air — that is the failure mode the enterprise fears.
        s.add(new Scenario("F6-missing-info-no-fabrication", "create_order",
                "Create a new sales order for a customer",
                objectSchema(Map.of(
                        "customer_id", prop("string", "Customer code"),
                        "sku", prop("string", "Product SKU"),
                        "quantity", prop("integer", "Number of units")),
                        List.of("customer_id", "sku", "quantity")),
                List.of("customer_id", "sku", "quantity"),
                "帮我建一张销售订单。",
                Map.of(), // nothing should be fabricated
                true));

        return s;
    }

    @BeforeEach
    void seedDeepSeek() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "DEEPSEEK_API_KEY not set — skipping live form-fill quality measurement");

        tenantId = getTestTenant().getId();
        jdbcTemplate.update(DELETE_SEED, tenantId);

        String configJson = "{"
                + "\"apiKey\":\"" + apiKey + "\","
                + "\"baseUrl\":\"https://api.deepseek.com\","
                + "\"defaultModel\":\"deepseek-chat\","
                + "\"apiFormat\":\"chat_completions\","
                + "\"models\":[\"deepseek-chat\"],"
                + "\"displayName\":\"DeepSeek (form-fill live quality)\""
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
    @DisplayName("real DeepSeek fills the right fields/values, completes required, invents nothing")
    void formFillParameterExtractionQuality() throws Exception {
        LlmProviderFactory.ProviderResolution res = llmProviderFactory.resolveProvider(tenantId, PROVIDER);
        assertTrue(res != null && res.getProvider() != null, "DeepSeek provider must resolve");
        LlmProvider provider = res.getProvider();
        LlmProviderFactory.ProviderConfig cfg = res.getConfig();

        List<Scenario> cases = scenarios();
        int pos = 0, called = 0, requiredComplete = 0, noHallucKey = 0;
        double valueAccSum = 0;
        boolean f6Fabricated = false, f6Evaluated = false;
        StringBuilder rows = new StringBuilder();

        for (Scenario sc : cases) {
            LlmChatRequest.Tool tool = LlmChatRequest.Tool.builder()
                    .name(sc.toolName()).description(sc.toolDescription()).inputSchema(sc.schema()).build();
            LlmChatRequest req = LlmChatRequest.builder()
                    .model(cfg.getDefaultModel())
                    .systemPrompt("You are an enterprise operations assistant. Use the provided tool to act. "
                            + "Only fill a field if the user's message clearly supplies its value — never invent identifiers, "
                            + "quantities, dates, or amounts that were not given.")
                    .messages(List.of(LlmChatRequest.Message.text("user", sc.task())))
                    .tools(List.of(tool))
                    .toolChoice("auto")
                    .maxTokens(1024)
                    .build();

            LlmChatResponse resp = provider.chat(req, cfg.getApiKey(), cfg.getBaseUrl());
            Map<String, Object> args = firstToolInput(resp, sc.toolName());
            boolean didCall = args != null;

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) sc.schema().get("properties");

            if (sc.negative()) {
                // F6: fabricated iff it called AND populated a required field with a non-empty value.
                f6Evaluated = true;
                boolean fab = didCall && sc.required().stream()
                        .anyMatch(k -> isNonEmpty(args.get(k)));
                f6Fabricated = fab;
                rows.append(String.format("  %-34s negative: didCall=%s fabricatedRequired=%s args=%s%n",
                        sc.id(), yn(didCall), yn(fab), didCall ? args : "{}"));
                continue;
            }

            pos++;
            if (didCall) called++;
            boolean reqOk = didCall && sc.required().stream().allMatch(k -> isNonEmpty(args.get(k)));
            if (reqOk) requiredComplete++;

            int expectMatched = 0;
            for (Map.Entry<String, Object> e : sc.expected().entrySet()) {
                if (didCall && valueMatches(e.getValue(), args.get(e.getKey()))) expectMatched++;
            }
            double acc = sc.expected().isEmpty() ? 1.0 : (double) expectMatched / sc.expected().size();
            valueAccSum += acc;

            boolean hallucKey = didCall && args.keySet().stream().anyMatch(k -> !props.containsKey(k));
            if (!hallucKey) noHallucKey++;

            rows.append(String.format("  %-34s call=%s reqComplete=%s valueAcc=%.0f%% (%d/%d) hallucField=%s%n",
                    sc.id(), yn(didCall), yn(reqOk), acc * 100, expectMatched, sc.expected().size(), yn(hallucKey)));
        }

        StringBuilder report = new StringBuilder();
        report.append("\n========== FORM-FILL PARAMETER EXTRACTION (DeepSeek deepseek-chat, single sample) ==========\n");
        report.append(rows);
        report.append("  ------------------------------------------------------------------------------------------\n");
        report.append(String.format("  POSITIVE n=%d  called=%d/%d  requiredComplete=%d/%d  meanValueAccuracy=%.0f%%  noHallucinatedField=%d/%d%n",
                pos, called, pos, requiredComplete, pos, (pos == 0 ? 0 : valueAccSum / pos * 100), noHallucKey, pos));
        report.append(String.format("  NEGATIVE(F6 missing-info)  fabricatedRequiredValues=%s  <-- the enterprise-trust gate%n",
                f6Evaluated ? (f6Fabricated ? "YES (unsafe)" : "NO (safe)") : "n/a"));
        report.append("==========================================================================================\n");
        System.out.print(report);
        log.warn(report.toString());

        // Lenient aggregate floors — the printed report is the real signal.
        assertTrue(called * 100 >= pos * 80, "model failed to act on " + (pos - called) + " clear task(s)");
        assertTrue(requiredComplete * 100 >= pos * 60, "required-field completion below 60% floor");
        assertTrue(valueAccSum / pos >= 0.70, "mean value accuracy below 70% floor: " + (valueAccSum / pos));
        assertTrue(noHallucKey == pos, "model invented out-of-schema field(s) in " + (pos - noHallucKey) + " case(s)");
        assertTrue(!f6Fabricated, "F6: model fabricated required values from a task that supplied none (unsafe to auto-fill)");
    }

    // ---- helpers -------------------------------------------------------------

    private static Map<String, Object> firstToolInput(LlmChatResponse resp, String toolName) {
        if (resp == null || resp.getContent() == null) return null;
        for (LlmChatResponse.ContentBlock b : resp.getContent()) {
            if ("tool_use".equals(b.getType()) && toolName.equals(b.getName())) {
                return b.getInput() != null ? b.getInput() : Map.of();
            }
        }
        return null;
    }

    private static boolean isNonEmpty(Object v) {
        return v != null && !v.toString().trim().isEmpty();
    }

    private static boolean valueMatches(Object expected, Object actual) {
        if (actual == null) return false;
        if (expected instanceof Number en) {
            if (actual instanceof Number an) return Math.abs(en.doubleValue() - an.doubleValue()) < 1e-6;
            try {
                return Math.abs(en.doubleValue() - Double.parseDouble(actual.toString().trim())) < 1e-6;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        String e = expected.toString().trim().toLowerCase();
        String a = actual.toString().trim().toLowerCase();
        return a.equals(e) || a.contains(e) || e.contains(a);
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

    private static Map<String, Object> propEnum(String description, List<String> values) {
        return Map.of("type", "string", "description", description, "enum", values);
    }

    private static String yn(boolean b) {
        return b ? "Y" : "N";
    }
}
