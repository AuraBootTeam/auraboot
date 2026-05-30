package com.auraboot.framework.chatbi.v2.provider;

import com.auraboot.framework.chatbi.v2.dto.Operator;
import com.auraboot.framework.chatbi.v2.dto.TokenType;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pin down the LLM IO contract — prompt shape + parse tolerance. */
class ChatBiPromptBuilderTest {

    private final ChatBiPromptBuilder b = new ChatBiPromptBuilder();

    // -- system prompt ---------------------------------------------------

    @Test
    void systemPromptInjectsCatalogAndOutputContract() {
        SemanticMetaResponse catalog = catalogWithSalesModel();
        String sys = b.buildSystemPrompt(catalog);

        assertThat(sys)
                .contains("AuraBoot ChatBI")
                .contains("Output contract")
                .contains("\"tokens\"")
                .contains("sales.total_sales")  // metric code from catalog
                .contains("销售额")              // zh label
                .contains("Example 1")
                .contains("Example 3");
    }

    @Test
    void systemPromptHandlesEmptyCatalog() {
        String sys = b.buildSystemPrompt(new SemanticMetaResponse());
        assertThat(sys).contains("no models registered");
    }

    // -- messages -------------------------------------------------------

    @Test
    void buildMessagesAppendsCurrentQuestionAfterHistory() {
        ConversationContext ctx = new ConversationContext();
        ctx.setMessageHistory(List.of(
                new ConversationContext.Message("user", "今年销售额按月趋势"),
                new ConversationContext.Message("assistant", "已聚合")));

        var msgs = b.buildMessages("按区域分布", ctx);

        assertThat(msgs).hasSize(3);
        assertThat(msgs.get(0).getRole()).isEqualTo("user");
        assertThat(msgs.get(0).getContent()).isEqualTo("今年销售额按月趋势");
        assertThat(msgs.get(2).getRole()).isEqualTo("user");
        assertThat(msgs.get(2).getContent()).isEqualTo("按区域分布");
    }

    @Test
    void buildMessagesHandlesNullHistory() {
        var msgs = b.buildMessages("hello", null);
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).getContent()).isEqualTo("hello");
    }

    // -- parseResponse --------------------------------------------------

    @Test
    void parseResponseHappyPathChinese() {
        String json = """
                {
                  "tokens": [
                    {"type":"METRIC","rawText":"销售额","resolvedCode":"sales.total_sales","position":0},
                    {"type":"DIMENSION","rawText":"按月","resolvedCode":"sales.order_date","dateBucket":"month","position":1},
                    {"type":"TIME_RANGE","rawText":"今年","resolvedCode":"YEAR_TO_DATE","position":2}
                  ],
                  "confidence": 0.95,
                  "needsClarification": false,
                  "disambiguation": null,
                  "suggestedFollowUps": ["按区域分布", "TOP10 客户"]
                }
                """;
        IntentResult r = b.parseResponse(json);

        assertThat(r.confidence()).isEqualTo(0.95);
        assertThat(r.needsClarification()).isFalse();
        assertThat(r.disambiguation()).isNull();
        assertThat(r.suggestedFollowUps()).containsExactly("按区域分布", "TOP10 客户");
        assertThat(r.tokens()).hasSize(3);
        assertThat(r.tokens().get(0).type()).isEqualTo(TokenType.METRIC);
        assertThat(r.tokens().get(0).resolvedCode()).isEqualTo("sales.total_sales");
        assertThat(r.tokens().get(1).dateBucket()).isEqualTo("month");
        assertThat(r.tokens().get(2).type()).isEqualTo(TokenType.TIME_RANGE);
    }

    @Test
    void parseResponseHandlesMarkdownFence() {
        String fenced = """
                ```json
                {"tokens":[{"type":"TOP_N","rawText":"top 10","value":10,"position":0}],
                 "confidence":0.9,"needsClarification":false,"suggestedFollowUps":[]}
                ```
                """;
        IntentResult r = b.parseResponse(fenced);
        assertThat(r.tokens()).hasSize(1);
        assertThat(r.tokens().get(0).type()).isEqualTo(TokenType.TOP_N);
        assertThat(r.tokens().get(0).value()).isEqualTo(10L);
    }

    @Test
    void parseResponseHandlesLeadingProse() {
        String mixed = "Sure, here you go:\n\n{\"tokens\":[],\"confidence\":0.7,"
                + "\"needsClarification\":false,\"suggestedFollowUps\":[]}\n\nHope this helps.";
        IntentResult r = b.parseResponse(mixed);
        assertThat(r.confidence()).isEqualTo(0.7);
        assertThat(r.tokens()).isEmpty();
    }

    @Test
    void parseResponseExtractsDisambiguation() {
        String json = """
                {"tokens":[],"confidence":0.4,"needsClarification":true,
                 "disambiguation":{"ambiguousTerm":"销量","candidates":[
                   {"type":"METRIC","code":"sales.units_sold","label":"销售数量","score":0.82},
                   {"type":"METRIC","code":"sales.total_sales","label":"销售金额","score":0.78}]},
                 "suggestedFollowUps":[]}
                """;
        IntentResult r = b.parseResponse(json);
        assertThat(r.needsClarification()).isTrue();
        assertThat(r.disambiguation()).isNotNull();
        assertThat(r.disambiguation().ambiguousTerm()).isEqualTo("销量");
        assertThat(r.disambiguation().candidates()).hasSize(2);
        assertThat(r.disambiguation().candidates().get(0).score()).isEqualTo(0.82);
    }

    @Test
    void parseResponseClampsConfidenceOutOfRange() {
        IntentResult low = b.parseResponse("{\"tokens\":[],\"confidence\":-0.5,\"suggestedFollowUps\":[]}");
        IntentResult high = b.parseResponse("{\"tokens\":[],\"confidence\":1.7,\"suggestedFollowUps\":[]}");
        assertThat(low.confidence()).isEqualTo(0.0);
        assertThat(high.confidence()).isEqualTo(1.0);
    }

    @Test
    void parseResponseSkipsUnknownTokenTypes() {
        String json = """
                {"tokens":[
                   {"type":"METRIC","rawText":"x","resolvedCode":"s.t","position":0},
                   {"type":"BOGUS","rawText":"y","position":1}
                ],"confidence":0.5,"needsClarification":false,"suggestedFollowUps":[]}
                """;
        IntentResult r = b.parseResponse(json);
        assertThat(r.tokens()).hasSize(1);
        assertThat(r.tokens().get(0).type()).isEqualTo(TokenType.METRIC);
    }

    @Test
    void parseResponseParsesOperatorAndValue() {
        String json = """
                {"tokens":[{"type":"DIMENSION","rawText":"华东","resolvedCode":"sales.region",
                  "operator":"EQ","value":"CN_EAST","position":0}],
                 "confidence":0.9,"needsClarification":false,"suggestedFollowUps":[]}
                """;
        IntentResult r = b.parseResponse(json);
        assertThat(r.tokens().get(0).operator()).isEqualTo(Operator.EQ);
        assertThat(r.tokens().get(0).value()).isEqualTo("CN_EAST");
    }

    @Test
    void parseResponseRejectsNullAndEmpty() {
        assertThat(b.parseResponse(null).confidence()).isZero();
        assertThat(b.parseResponse("").confidence()).isZero();
        assertThat(b.parseResponse("   ").confidence()).isZero();
    }

    @Test
    void parseResponseRejectsMalformedJsonGracefully() {
        IntentResult r = b.parseResponse("{ not even close to json");
        assertThat(r.confidence()).isZero();
        assertThat(r.tokens()).isEmpty();
    }

    @Test
    void parseResponseRejectsPureProse() {
        IntentResult r = b.parseResponse("I cannot answer that question.");
        assertThat(r.confidence()).isZero();
    }

    @Test
    void extractJsonBlockBalancesNestedBraces() {
        String text = "prefix {\"a\":{\"b\":1,\"c\":\"}{\"},\"d\":2} suffix";
        String json = ChatBiPromptBuilder.extractJsonBlock(text);
        assertThat(json).isEqualTo("{\"a\":{\"b\":1,\"c\":\"}{\"},\"d\":2}");
    }

    // -- helpers --------------------------------------------------------

    private static SemanticMetaResponse catalogWithSalesModel() {
        SemanticMetaResponse r = new SemanticMetaResponse();
        SemanticMetaResponse.ModelMeta m = new SemanticMetaResponse.ModelMeta();
        m.setCode("sales");
        m.setLabel(Map.of("zh-CN", "销售", "en-US", "Sales"));
        SemanticMetaResponse.MetricMeta metric = new SemanticMetaResponse.MetricMeta();
        metric.setCode("sales.total_sales");
        metric.setLabel(Map.of("zh-CN", "销售额", "en-US", "Total Sales"));
        m.setMetrics(List.of(metric));
        SemanticMetaResponse.DimensionMeta dim = new SemanticMetaResponse.DimensionMeta();
        dim.setCode("sales.order_date");
        dim.setLabel(Map.of("zh-CN", "下单日期", "en-US", "Order Date"));
        dim.setPrimaryTime(true);
        dim.setTimeGrains(List.of("day", "month", "quarter"));
        m.setDimensions(List.of(dim));
        r.setModels(List.of(m));
        return r;
    }
}
