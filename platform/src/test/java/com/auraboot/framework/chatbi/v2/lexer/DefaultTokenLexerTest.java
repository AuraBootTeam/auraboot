package com.auraboot.framework.chatbi.v2.lexer;

import com.auraboot.framework.chatbi.v2.dto.SearchToken;
import com.auraboot.framework.chatbi.v2.dto.TokenType;
import com.auraboot.framework.chatbi.v2.provider.IntentResult;
import com.auraboot.framework.chatbi.v2.provider.LlmUsage;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultTokenLexerTest {

    private DefaultTokenLexer lexer;

    @BeforeEach
    void setUp() {
        lexer = new DefaultTokenLexer();
    }

    @Test
    void validatesLlmTokensAgainstCatalogAndFillsDeterministicDimensions() {
        SemanticMetaResponse catalog = catalog("sales", "MODEL-1");
        List<SearchToken> hintTokens = List.of(
                SearchToken.metric("total_sales", "销售额", 0),
                SearchToken.metric("sales.hallucinated", "不存在", 1));
        IntentResult hint = new IntentResult(
                hintTokens, 0.95d, false, null, List.of(), LlmUsage.zero());

        List<SearchToken> out = lexer.lex("按区域看销售总额", catalog, hint);

        assertThat(out)
                .extracting(SearchToken::type, SearchToken::resolvedCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                TokenType.METRIC, "sales.total_sales"),
                        org.assertj.core.groups.Tuple.tuple(
                                TokenType.DIMENSION, "sales.region"));
    }

    @Test
    void emptyHintMatchesChineseCatalogTimeRangeGrainAndTopN() {
        List<SearchToken> out = lexer.lex(
                "最近30天按月按区域看销售总额前10名",
                catalog("sales", "MODEL-1"),
                IntentResult.empty());

        assertThat(out)
                .extracting(SearchToken::type)
                .containsExactly(
                        TokenType.METRIC,
                        TokenType.DIMENSION,
                        TokenType.DIMENSION,
                        TokenType.TIME_RANGE,
                        TokenType.TOP_N);
        assertThat(out.get(0).resolvedCode()).isEqualTo("sales.total_sales");
        assertThat(out.get(1).resolvedCode()).isEqualTo("sales.region");
        assertThat(out.get(2).resolvedCode()).isEqualTo("sales.order_date");
        assertThat(out.get(2).dateBucket()).isEqualTo("month");
        assertThat(out.get(3).resolvedCode()).isEqualTo("last_30_days");
        assertThat(out.get(4).value()).isEqualTo(10);
    }

    @Test
    void emptyHintMatchesEnglishLabels() {
        List<SearchToken> out = lexer.lex(
                "Top 5 Total Sales by Region this year",
                catalog("sales", "MODEL-1"),
                IntentResult.empty());

        assertThat(out)
                .extracting(SearchToken::type, SearchToken::resolvedCode)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(
                                TokenType.METRIC, "sales.total_sales"),
                        org.assertj.core.groups.Tuple.tuple(
                                TokenType.DIMENSION, "sales.region"),
                        org.assertj.core.groups.Tuple.tuple(
                                TokenType.TIME_RANGE, "ytd"));
        assertThat(out).anyMatch(token ->
                token.type() == TokenType.TOP_N && Integer.valueOf(5).equals(token.value()));
    }

    @Test
    void sameStrengthMetricAcrossModelsDoesNotGuess() {
        SemanticMetaResponse catalog = catalog("sales", "MODEL-1");
        catalog.getModels().add(catalog("finance", "MODEL-2").getModels().get(0));

        List<SearchToken> out =
                lexer.lex("销售总额", catalog, IntentResult.empty());

        assertThat(out).isEmpty();
    }

    @Test
    void unknownQuestionAndNullInputsYieldEmptyList() {
        assertThat(lexer.lex(
                "tell me something unrelated",
                catalog("sales", "MODEL-1"),
                IntentResult.empty())).isEmpty();
        assertThat(lexer.lex("anything", null, null)).isEmpty();
    }

    private SemanticMetaResponse catalog(String modelCode, String pid) {
        SemanticMetaResponse response = new SemanticMetaResponse();
        SemanticMetaResponse.ModelMeta model = new SemanticMetaResponse.ModelMeta();
        model.setPid(pid);
        model.setCode(modelCode);

        SemanticMetaResponse.MetricMeta metric =
                new SemanticMetaResponse.MetricMeta();
        metric.setCode("total_sales");
        metric.setLabel(Map.of(
                "zh-CN", "销售总额",
                "en-US", "Total Sales"));
        model.setMetrics(List.of(metric));

        SemanticMetaResponse.DimensionMeta region =
                new SemanticMetaResponse.DimensionMeta();
        region.setCode("region");
        region.setType("categorical");
        region.setLabel(Map.of(
                "zh-CN", "区域",
                "en-US", "Region"));

        SemanticMetaResponse.DimensionMeta orderDate =
                new SemanticMetaResponse.DimensionMeta();
        orderDate.setCode("order_date");
        orderDate.setType("time");
        orderDate.setPrimaryTime(true);
        orderDate.setTimeGrains(List.of("day", "week", "month", "quarter", "year"));
        orderDate.setLabel(Map.of(
                "zh-CN", "下单日期",
                "en-US", "Order Date"));
        model.setDimensions(List.of(region, orderDate));

        response.setModels(new java.util.ArrayList<>(List.of(model)));
        return response;
    }
}
