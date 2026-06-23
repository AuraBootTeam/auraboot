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
 * S1 — Live-LLM quality measurement for the <strong>intelligent customer-service</strong> scenario:
 * can the agent read a real inbound complaint <em>email</em> and extract the complaint fields
 * (account / contact / description / severity) correctly, without fabricating the ones the email
 * never states? This is the "智能客服闭环" intelligence leg — the design-doc S1 row's 🟡
 * "从邮件抽对 account/contact/description/severity".
 *
 * <p>Where {@link AgentFormFillLiveIT} measures one-line form-fill extraction, this drives realistic
 * multi-sentence emails (bilingual, with noise) and tests the harder skill: pulling the company,
 * the contact person, a usable description, and mapping natural-language urgency
 * ("产线停工 / high priority / 非常严重的质量事故 / 优先级不高") to a severity enum. The decisive
 * enterprise-trust gate is E6 (negative): a vague email that names no account and no severity must
 * NOT make the model invent them.
 *
 * <p><strong>Faithful path</strong> — same as the runtime ({@code ChatTurnRuntime.runToolLoop}):
 * native tool-use, a {@link LlmChatRequest} with a JSON {@code inputSchema} sent to the real provider
 * via {@link LlmProvider#chat}; arguments read from the {@code tool_use} block's {@code input}.
 *
 * <p><strong>Opt-in</strong> — gated by {@code DEEPSEEK_API_KEY}, tagged {@code agent-eval-live}; a
 * plain {@code ./gradlew :testAgent} skips it. Assertions are lenient aggregate floors; the printed
 * report carries the real numbers.
 *
 * <pre>{@code
 * cd platform && DEEPSEEK_API_KEY=sk-... ./gradlew :testAgent --tests '*CsComplaintEmailExtractionLiveIT*'
 * }</pre>
 * <p><strong>After running</strong>: redact {@code $DEEPSEEK_API_KEY} from build/reports +
 * build/test-results + task outputs (the seed INSERT lands in MyBatis DEBUG SQL logs).
 */
@Slf4j
@Tag("agent-eval-live")
@DisplayName("Live quality: CS complaint email → field extraction vs a real LLM (DeepSeek)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "agent.anthropic.api-key=",
        "agent.llm.stub-mode=false",
})
class CsComplaintEmailExtractionLiveIT extends BaseIntegrationTest {

    private static final String PROVIDER = "deepseek";
    private static final String DELETE_SEED =
            "DELETE FROM ab_cloud_config WHERE service_type='llm' AND provider_code='" + PROVIDER
                    + "' AND config_level='tenant' AND tenant_id=?";

    @Autowired private LlmProviderFactory llmProviderFactory;
    @Autowired private CloudConfigService cloudConfigService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long tenantId;

    /** An inbound complaint email + the fields a competent agent should extract from it. */
    private record Email(String id, String body, Map<String, Object> expected,
                         List<String> required, boolean negative) {
    }

    private static final Map<String, Object> COMPLAINT_SCHEMA = objectSchema(
            new LinkedHashMap<>(Map.of(
                    "account", prop("string", "Customer company name or account code the complaint is from. Omit if the email does not state it."),
                    "contact", prop("string", "Name of the contact person who sent the complaint. Omit if the email does not state it."),
                    "description", prop("string", "A concise description of the problem being complained about"),
                    "severity", propEnum("Severity of the complaint. Only set when the email states urgency, production impact, or explicit priority; omit for vague problem reports.", List.of("low", "medium", "high", "critical")))),
            List.of("description"));

    private List<Email> emails() {
        List<Email> e = new ArrayList<>();

        e.add(new Email("E1-line-down-high",
                "客户 King Manufacturing 的设备管理员李强来信:上周采购的 8 台 X200 控制器有 4 台无法上电,"
                        + "产线已停工,请尽快按高优先级排查处理。",
                Map.of("account", "King Manufacturing", "contact", "李强", "severity", "high"),
                List.of("account", "description", "severity"), false));

        e.add(new Email("E2-quality-incident-critical",
                "我们公司 BlueSky Electronics 收到的这批传感器精度严重偏差,已影响整条质检线,"
                        + "这是非常严重的质量事故,必须最高优先级处理!联系人:采购经理 Wang Lei。",
                Map.of("account", "BlueSky Electronics", "contact", "Wang Lei", "severity", "critical"),
                List.of("account", "description", "severity"), false));

        e.add(new Email("E3-minor-low",
                "你好,我是 Acme Foods 的陈静。我们上个月安装的包装机偶尔会卡纸,不太影响生产,"
                        + "有空帮忙看一下就行,优先级不高,谢谢。",
                Map.of("account", "Acme Foods", "contact", "陈静", "severity", "low"),
                List.of("account", "description", "severity"), false));

        e.add(new Email("E4-label-error-medium",
                "投诉来自客户 Greenfield Ltd:交付的 20 箱物料中有 3 箱标签错误需要更换,影响本周发货,"
                        + "优先级中等。对接人:仓库主管 Tom。",
                Map.of("account", "Greenfield Ltd", "contact", "Tom", "severity", "medium"),
                List.of("account", "description", "severity"), false));

        e.add(new Email("E5-english-breaker-high",
                "Subject: Urgent — repeated machine faults\n\nHi, this is Sarah from Nordic Tools. "
                        + "Three of the CNC units delivered last week keep tripping the breaker and halting our line. "
                        + "This is high priority for us, please escalate.",
                Map.of("account", "Nordic Tools", "contact", "Sarah", "severity", "high"),
                List.of("account", "description", "severity"), false));

        // E6 (negative): no account, no contact, no severity signal. A trustworthy agent must NOT
        // invent the account or the severity just to fill the required set.
        e.add(new Email("E6-vague-no-fabrication",
                "你好,设备好像有点问题,麻烦你们处理一下,谢谢。",
                Map.of(), List.of("account", "severity"), true));

        return e;
    }

    @BeforeEach
    void seedDeepSeek() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "DEEPSEEK_API_KEY not set — skipping live CS email extraction quality measurement");

        tenantId = getTestTenant().getId();
        jdbcTemplate.update(DELETE_SEED, tenantId);

        String configJson = "{"
                + "\"apiKey\":\"" + apiKey + "\","
                + "\"baseUrl\":\"https://api.deepseek.com\","
                + "\"defaultModel\":\"deepseek-chat\","
                + "\"apiFormat\":\"chat_completions\","
                + "\"models\":[\"deepseek-chat\"],"
                + "\"displayName\":\"DeepSeek (CS email extraction live quality)\""
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
    @DisplayName("real DeepSeek extracts account/contact/description/severity from emails, invents nothing")
    void csComplaintEmailExtractionQuality() throws Exception {
        LlmProviderFactory.ProviderResolution res = llmProviderFactory.resolveProvider(tenantId, PROVIDER);
        assertTrue(res != null && res.getProvider() != null, "DeepSeek provider must resolve");
        LlmProvider provider = res.getProvider();
        LlmProviderFactory.ProviderConfig cfg = res.getConfig();

        List<Email> cases = emails();
        int pos = 0, called = 0, requiredComplete = 0, noHalluc = 0;
        double valueAccSum = 0;
        boolean negFabricated = false, negEvaluated = false;
        StringBuilder rows = new StringBuilder();

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) COMPLAINT_SCHEMA.get("properties");

        for (Email em : cases) {
            LlmChatRequest.Tool tool = LlmChatRequest.Tool.builder()
                    .name("register_complaint").description("Register a customer complaint ticket")
                    .inputSchema(COMPLAINT_SCHEMA).build();
            LlmChatRequest req = LlmChatRequest.builder()
                    .model(cfg.getDefaultModel())
                    .systemPrompt("You are a customer-service triage assistant. Read the inbound complaint "
                            + "email and call the tool to register it. Extract only what the email actually "
                            + "states — never invent a company name, contact, or severity that is not supported "
                            + "by the email. Only map severity when the email contains an explicit urgency, "
                            + "priority, quality-incident, or production-impact signal. Vague wording such as "
                            + "'有点问题' is not enough for severity; omit account/contact/severity when unknown.")
                    .messages(List.of(LlmChatRequest.Message.text("user", em.body())))
                    .tools(List.of(tool))
                    .toolChoice("auto")
                    .maxTokens(1024)
                    .build();

            LlmChatResponse resp = provider.chat(req, cfg.getApiKey(), cfg.getBaseUrl());
            Map<String, Object> args = firstToolInput(resp, "register_complaint");
            boolean didCall = args != null;

            if (em.negative()) {
                negEvaluated = true;
                // fabricated iff it populated account or severity with a non-empty value the email never gave
                boolean fab = didCall && em.required().stream().anyMatch(k -> isNonEmpty(args.get(k)));
                negFabricated = fab;
                rows.append(String.format("  %-30s negative: didCall=%s fabricated(account|severity)=%s args=%s%n",
                        em.id(), yn(didCall), yn(fab), didCall ? args : "{}"));
                continue;
            }

            pos++;
            if (didCall) called++;
            boolean reqOk = didCall && em.required().stream().allMatch(k -> isNonEmpty(args.get(k)));
            if (reqOk) requiredComplete++;

            int matched = 0;
            for (Map.Entry<String, Object> ex : em.expected().entrySet()) {
                if (didCall && valueMatches(ex.getValue(), args.get(ex.getKey()))) matched++;
            }
            double acc = em.expected().isEmpty() ? 1.0 : (double) matched / em.expected().size();
            valueAccSum += acc;

            boolean hallucKey = didCall && args.keySet().stream().anyMatch(k -> !props.containsKey(k));
            if (!hallucKey) noHalluc++;

            rows.append(String.format("  %-30s call=%s reqComplete=%s fieldAcc=%.0f%% (%d/%d) descFilled=%s hallucField=%s%n",
                    em.id(), yn(didCall), yn(reqOk), acc * 100, matched, em.expected().size(),
                    yn(didCall && isNonEmpty(args.get("description"))), yn(hallucKey)));
        }

        StringBuilder report = new StringBuilder();
        report.append("\n========== CS COMPLAINT EMAIL EXTRACTION (DeepSeek deepseek-chat, single sample) ==========\n");
        report.append(rows);
        report.append("  --------------------------------------------------------------------------------------------\n");
        report.append(String.format("  POSITIVE n=%d  called=%d/%d  requiredComplete=%d/%d  meanFieldAccuracy=%.0f%%  noHallucinatedField=%d/%d%n",
                pos, called, pos, requiredComplete, pos, (pos == 0 ? 0 : valueAccSum / pos * 100), noHalluc, pos));
        report.append(String.format("  NEGATIVE(E6 vague email)  fabricatedAccountOrSeverity=%s  <-- the enterprise-trust gate%n",
                negEvaluated ? (negFabricated ? "YES (unsafe)" : "NO (safe)") : "n/a"));
        report.append("============================================================================================\n");
        System.out.print(report);
        log.warn(report.toString());

        // Lenient aggregate floors — the printed report is the real signal.
        assertTrue(called * 100 >= pos * 80, "model failed to register " + (pos - called) + " clear complaint email(s)");
        assertTrue(requiredComplete * 100 >= pos * 60, "required-field completion below 60% floor");
        assertTrue(valueAccSum / pos >= 0.60, "mean field accuracy below 60% floor: " + (valueAccSum / pos));
        assertTrue(noHalluc == pos, "model invented out-of-schema field(s) in " + (pos - noHalluc) + " case(s)");
        assertTrue(!negFabricated, "E6: model fabricated account/severity from a vague email that supplied neither (unsafe)");
    }

    // ---- helpers (mirror AgentFormFillLiveIT) --------------------------------

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
        schema.put("additionalProperties", false);
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
