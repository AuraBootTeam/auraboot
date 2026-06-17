package com.auraboot.framework.agent;

import com.auraboot.framework.ai.chatbi.service.ChatBiLlmParser;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-LLM <strong>intent-parsing quality</strong> for ChatBI (生成图表 / NL→数据). Where
 * {@code ChatBiLlmParserTest} mocks the provider (tests parsing of a canned reply), this drives
 * {@link ChatBiLlmParser#tryParse} against a <strong>real</strong> model: given a BI model
 * schema + a natural-language question, does DeepSeek extract the right aggregation, group-by,
 * and aggregation field — and ground every field reference in the schema (no hallucination)?
 *
 * <p>Self-contained: the model schema is passed in (no DB lookup), isolating the model's
 * intent judgment from data-execution infra — same controlled technique as the other live ITs.
 *
 * <p>Opt-in: {@code @Tag("agent-eval-live")} + {@code DEEPSEEK_API_KEY}.
 */
@Slf4j
@Tag("agent-eval-live")
@DisplayName("Live quality: ChatBI NL→intent parsing vs a real LLM (DeepSeek)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "agent.anthropic.api-key=",
        "agent.llm.stub-mode=false",
})
class ChatBiIntentLiveIT extends BaseIntegrationTest {

    private static final String PROVIDER = "deepseek";
    private static final String DELETE_SEED =
            "DELETE FROM ab_cloud_config WHERE service_type='llm' AND provider_code='" + PROVIDER
                    + "' AND config_level='tenant' AND tenant_id=?";

    private static final Set<String> FIELDS =
            Set.of("region", "product", "status", "amount", "quantity", "order_date");

    private static final ModelDefinition SALES = ModelDefinition.builder()
            .code("sales_order")
            .displayName("Sales Order")
            .fields(List.of(
                    FieldDefinition.builder().code("region").dataType("string").build(),
                    FieldDefinition.builder().code("product").dataType("string").build(),
                    FieldDefinition.builder().code("status").dataType("string").build(),
                    FieldDefinition.builder().code("amount").dataType("decimal").build(),
                    FieldDefinition.builder().code("quantity").dataType("integer").build(),
                    FieldDefinition.builder().code("order_date").dataType("date").build()))
            .build();

    @Autowired private ChatBiLlmParser chatBiLlmParser;
    @Autowired private CloudConfigService cloudConfigService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long tenantId;

    /** A BI question with its expected parsed intent. okAgg uses "" for the null aggregation. */
    private record Scenario(String id, String question, Set<String> okAgg, String expectGroup,
                            String expectAggField, String expectFilterField) {
    }

    private List<Scenario> scenarios() {
        return List.of(
                new Scenario("Q1-sum-by-region", "按地区汇总销售总额",
                        Set.of("sum"), "region", "amount", null),
                new Scenario("Q2-count-by-status", "每个订单状态各有多少订单",
                        Set.of("count"), "status", null, null),
                new Scenario("Q3-avg-amount", "所有订单的平均金额是多少",
                        Set.of("avg"), null, "amount", null),
                new Scenario("Q4-count-by-product", "按产品统计订单数量",
                        Set.of("count", "sum"), "product", null, null),
                new Scenario("Q5-filter-amount", "查出金额大于1000的订单",
                        Set.of(""), null, null, "amount"));
    }

    @BeforeEach
    void seedDeepSeek() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "DEEPSEEK_API_KEY not set — skipping ChatBI intent measurement");
        tenantId = getTestTenant().getId();
        jdbcTemplate.update(DELETE_SEED, tenantId);
        String configJson = "{"
                + "\"apiKey\":\"" + apiKey + "\","
                + "\"baseUrl\":\"https://api.deepseek.com\","
                + "\"defaultModel\":\"deepseek-chat\","
                + "\"apiFormat\":\"chat_completions\","
                + "\"models\":[\"deepseek-chat\"],"
                + "\"displayName\":\"DeepSeek (chatbi intent)\""
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
    @DisplayName("real DeepSeek parses aggregation/group-by/field, grounded in the schema")
    void chatBiIntentQuality() {
        List<Scenario> cases = scenarios();
        int aggOk = 0, groupOk = 0, grounded = 0, filterOk = 0, filterExpected = 0, pass = 0;
        StringBuilder rows = new StringBuilder();

        for (Scenario sc : cases) {
            ChatBiLlmParser.ParsedQuery q = chatBiLlmParser.tryParse(tenantId, sc.question(), SALES, List.of());

            String agg = q == null ? null : norm(q.getAggregationFunction());
            String group = q == null ? null : norm(q.getGroupByField());
            String aggField = q == null ? null : norm(q.getAggregationField());
            List<ChatBiLlmParser.ParsedFilter> filters = q == null || q.getFilters() == null ? List.of() : q.getFilters();

            boolean isAggOk = sc.okAgg().contains(agg == null ? "" : agg);
            boolean isGroupOk = Objects.equals(group, sc.expectGroup());
            boolean isAggFieldOk = sc.expectAggField() == null || Objects.equals(aggField, sc.expectAggField());
            // grounded: every field reference the model produced is a real schema field code.
            boolean isGrounded = (group == null || FIELDS.contains(group))
                    && (aggField == null || FIELDS.contains(aggField))
                    && filters.stream().map(f -> norm(f.getFieldCode())).allMatch(c -> c == null || FIELDS.contains(c));
            boolean isFilterOk = true;
            if (sc.expectFilterField() != null) {
                filterExpected++;
                isFilterOk = filters.stream().anyMatch(f -> sc.expectFilterField().equals(norm(f.getFieldCode())));
                if (isFilterOk) filterOk++;
            }

            if (isAggOk) aggOk++;
            if (isGroupOk) groupOk++;
            if (isGrounded) grounded++;
            boolean ok = isAggOk && isGroupOk && isAggFieldOk && isGrounded && isFilterOk;
            if (ok) pass++;

            rows.append(String.format("  %-22s pass=%s | agg=%-6s(exp%s) group=%-8s(exp%s) aggField=%-7s grounded=%s%n",
                    sc.id(), ok ? "Y" : "N", agg, sc.okAgg(), group, sc.expectGroup(), aggField, isGrounded ? "Y" : "N"));
        }

        StringBuilder report = new StringBuilder();
        report.append("\n========== CHATBI NL→INTENT (DeepSeek deepseek-chat, single sample) ==========\n");
        report.append(rows);
        report.append("  --------------------------------------------------------------------------\n");
        report.append(String.format("  OVERALL n=%d  pass=%d  aggCorrect=%d  groupCorrect=%d  grounded(no-halluc)=%d/%d  filterCorrect=%d/%d%n",
                cases.size(), pass, aggOk, groupOk, grounded, cases.size(), filterOk, filterExpected));
        report.append("==============================================================================\n");
        System.out.print(report);
        log.warn(report.toString());

        // Floors: grounding is the hard requirement (no hallucinated field codes); intent
        // accuracy is reported and floored leniently (single sample).
        assertTrue(grounded == cases.size(), "model produced ungrounded field reference(s): " + grounded + "/" + cases.size());
        assertTrue(pass * 100 >= cases.size() * 60, "intent pass rate below 60%: " + pass + "/" + cases.size());
    }

    private static String norm(String s) {
        return s == null || s.isBlank() ? null : s.trim().toLowerCase();
    }
}
