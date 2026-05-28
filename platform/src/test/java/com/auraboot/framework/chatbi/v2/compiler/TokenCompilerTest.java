package com.auraboot.framework.chatbi.v2.compiler;

import com.auraboot.framework.chatbi.v2.dto.Operator;
import com.auraboot.framework.chatbi.v2.dto.SearchToken;
import com.auraboot.framework.chatbi.v2.dto.TokenType;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TokenCompiler}.
 *
 * <p>Pure-function tests against synthetic Token sequences. No catalog, no DB.
 * Covers the canonical PRD 17 §3.2 example end-to-end plus per-TokenType
 * mapping rules and the documented error paths.
 */
class TokenCompilerTest {

    private TokenCompiler compiler;

    @BeforeEach
    void setUp() {
        compiler = new TokenCompiler();
    }

    @Test
    void emptyTokenListYieldsEmptyRequest() {
        SemanticQueryRequest req = compiler.compile(List.of(), "sales");
        assertThat(req.getMetrics()).isEmpty();
        assertThat(req.getDimensions()).isEmpty();
        assertThat(req.getFilters()).isEmpty();
        assertThat(req.getTimeRange()).isNull();
        assertThat(req.getLimit()).isZero();
    }

    @Test
    void metricTokenIsQualifiedAndAppended() {
        SearchToken t = SearchToken.metric("total_sales", "销售额", 0);
        SemanticQueryRequest req = compiler.compile(List.of(t), "sales");
        assertThat(req.getMetrics()).containsExactly("sales.total_sales");
    }

    @Test
    void metricAlreadyQualifiedIsNotDoubleQualified() {
        SearchToken t = SearchToken.metric("sales.total_sales", "销售额", 0);
        SemanticQueryRequest req = compiler.compile(List.of(t), "sales");
        assertThat(req.getMetrics()).containsExactly("sales.total_sales");
    }

    @Test
    void dimensionWithBucketAppendsGrainSuffix() {
        SearchToken t = SearchToken.dimension("order_date", "按月", 1, "month", null, null);
        SemanticQueryRequest req = compiler.compile(List.of(t), "sales");
        assertThat(req.getDimensions()).containsExactly("sales.order_date__month");
        assertThat(req.getFilters()).isEmpty();
    }

    @Test
    void dimensionWithFilterAlsoAppendsFilter() {
        SearchToken t = SearchToken.dimension("region", "华东", 2, null, Operator.EQ, "华东");
        SemanticQueryRequest req = compiler.compile(List.of(t), "sales");
        assertThat(req.getDimensions()).containsExactly("sales.region");
        assertThat(req.getFilters()).hasSize(1);
        SemanticQueryRequest.Filter f = req.getFilters().get(0);
        assertThat(f.getField()).isEqualTo("sales.region");
        assertThat(f.getOp()).isEqualTo("eq");
        assertThat(f.getValue()).isEqualTo("华东");
    }

    @Test
    void timeRangePresetIsAccepted() {
        SearchToken t = SearchToken.timeRange("ytd", "今年", 0);
        SemanticQueryRequest req = compiler.compile(List.of(t), "sales");
        assertThat(req.getTimeRange()).isNotNull();
        assertThat(req.getTimeRange().getPreset()).isEqualTo("ytd");
    }

    @Test
    void unknownTimeRangePresetThrows() {
        SearchToken t = SearchToken.timeRange("forever", "永远", 0);
        assertThatThrownBy(() -> compiler.compile(List.of(t), "sales"))
                .isInstanceOf(TokenCompileException.class)
                .extracting("code").isEqualTo("BAD_TIME_RANGE");
    }

    @Test
    void metricWithBlankCodeThrowsUnknownMetric() {
        SearchToken t = new SearchToken(TokenType.METRIC, "销售额", null, null, null, 0, null, null);
        assertThatThrownBy(() -> compiler.compile(List.of(t), "sales"))
                .isInstanceOf(TokenCompileException.class)
                .extracting("code").isEqualTo("UNKNOWN_METRIC");
    }

    @Test
    void dimensionWithBlankCodeThrowsUnknownDimension() {
        SearchToken t = new SearchToken(TokenType.DIMENSION, "区域", "", null, null, 0, null, null);
        assertThatThrownBy(() -> compiler.compile(List.of(t), "sales"))
                .isInstanceOf(TokenCompileException.class)
                .extracting("code").isEqualTo("UNKNOWN_DIMENSION");
    }

    @Test
    void topNTokenSetsLimit() {
        SearchToken t = SearchToken.topN(10, "top 10", 3);
        SemanticQueryRequest req = compiler.compile(List.of(t), "sales");
        assertThat(req.getLimit()).isEqualTo(10);
    }

    @Test
    void prdCanonicalExampleCompilesCorrectly() {
        // PRD 17 §3.2: "今年华东地区销售额按月趋势"
        List<SearchToken> tokens = List.of(
                SearchToken.metric("sales.total_sales", "销售额", 4),
                SearchToken.dimension("region", "华东地区", 2, null, Operator.EQ, "华东"),
                SearchToken.dimension("order_date", "按月", 5, "month", null, null),
                SearchToken.timeRange("ytd", "今年", 0));

        SemanticQueryRequest req = compiler.compile(tokens, "sales");

        assertThat(req.getMetrics()).containsExactly("sales.total_sales");
        assertThat(req.getDimensions()).containsExactly("sales.region", "sales.order_date__month");
        assertThat(req.getFilters()).hasSize(1);
        assertThat(req.getFilters().get(0).getField()).isEqualTo("sales.region");
        assertThat(req.getFilters().get(0).getOp()).isEqualTo("eq");
        assertThat(req.getFilters().get(0).getValue()).isEqualTo("华东");
        assertThat(req.getTimeRange().getPreset()).isEqualTo("ytd");
    }

    @Test
    void informationalTokensAreIgnored() {
        List<SearchToken> tokens = List.of(
                new SearchToken(TokenType.KEYWORD, "by", null, null, null, 0, null, null),
                new SearchToken(TokenType.OPERATOR, ">", null, Operator.GT, null, 1, null, null),
                new SearchToken(TokenType.VALUE, "100", null, null, 100, 2, null, null));
        SemanticQueryRequest req = compiler.compile(tokens, "sales");
        assertThat(req.getMetrics()).isEmpty();
        assertThat(req.getDimensions()).isEmpty();
        assertThat(req.getFilters()).isEmpty();
    }

    @Test
    void nullSemanticModelCodeLeavesCodesBare() {
        SearchToken t = SearchToken.metric("total_sales", "销售额", 0);
        SemanticQueryRequest req = compiler.compile(List.of(t), null);
        assertThat(req.getMetrics()).containsExactly("total_sales");
    }
}
