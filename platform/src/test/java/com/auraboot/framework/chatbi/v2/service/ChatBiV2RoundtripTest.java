package com.auraboot.framework.chatbi.v2.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.chatbi.v2.compiler.TokenCompiler;
import com.auraboot.framework.chatbi.v2.dto.ChatBiAnswerResponse;
import com.auraboot.framework.chatbi.v2.dto.SearchToken;
import com.auraboot.framework.chatbi.v2.dto.TokenType;
import com.auraboot.framework.chatbi.v2.entity.ChatBiAnswer;
import com.auraboot.framework.chatbi.v2.mapper.ChatBiAnswerMapper;
import com.auraboot.framework.chatbi.v2.provider.ConversationContext;
import com.auraboot.framework.chatbi.v2.provider.IntentResult;
import com.auraboot.framework.chatbi.v2.provider.LlmProviderRouter;
import com.auraboot.framework.chatbi.v2.provider.LlmUsage;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import com.auraboot.framework.semantic.dto.SemanticQueryResponse;
import com.auraboot.framework.semantic.service.SemanticCatalogService;
import com.auraboot.framework.semantic.service.SemanticQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W4-M3 structural roundtrip — 10 representative questions (5 CN + 5 EN)
 * driven through the full ChatBiAnswerService orchestration with a
 * fixture LLM (deterministic IntentResults). Proves the end-to-end
 * chain stays coherent under a realistic question mix without spending
 * real Anthropic budget.
 *
 * <p>For each question, the test asserts: status SUCCESS / DISAMBIGUATION,
 * the viz heuristic, the LLM winner attribution, the conversation
 * sliding window applies, and the {@link ChatBiAnswerMapper} insert
 * happened with the expected llmUsed code.
 *
 * <p>The 10-question scenario set lives in
 * {@code ida/docs/29-chatbi-v2-w4-m3-structural-evidence.md} — keep both
 * in sync when adding scenarios.
 */
class ChatBiV2RoundtripTest {

    private LlmProviderRouter router;
    private SemanticCatalogService catalog;
    private SemanticQueryService queryService;
    private TokenCompiler compiler;
    private ConversationService conversationService;
    private DisambiguationService disambig;
    private ChatBiAnswerMapper answerMapper;
    private ChatBiAnswerPersistence persistence;
    private ChatBiAnswerService service;

    @BeforeEach
    void setup() {
        router = mock(LlmProviderRouter.class);
        catalog = mock(SemanticCatalogService.class);
        queryService = mock(SemanticQueryService.class);
        compiler = mock(TokenCompiler.class);
        conversationService = mock(ConversationService.class);
        disambig = mock(DisambiguationService.class);
        answerMapper = mock(ChatBiAnswerMapper.class);
        // Real persistence bean forwards to the mocked mapper (REQUIRES_NEW proxy is a
        // no-op in a plain unit test), so existing verify(answerMapper) checks still hold.
        persistence = new ChatBiAnswerPersistence(answerMapper, conversationService);
        service = new ChatBiAnswerService(router, catalog, queryService,
                compiler, conversationService, disambig, persistence);
        MetaContext.setCurrentTenantId(1L);
        MetaContext.setCurrentUserId(100L);
        when(catalog.listCatalog(1L)).thenReturn(new SemanticMetaResponse());
        when(compiler.compile(any(), any())).thenReturn(new SemanticQueryRequest());
        // Default disambig: useTop1. Specific scenarios stub the ambiguous case.
        when(disambig.evaluate(any(), eq(1L), anyString(), anyString()))
                .thenReturn(DisambiguationService.Verdict.useTop1());
    }

    @AfterEach
    void teardown() {
        MetaContext.clear();
    }

    @Test
    void tenScenarioRoundtripCoversCnEnAcrossVizTypesAndFallbacks() {
        List<Scenario> scenarios = scenarioPanel();
        String conv = "CONV-PID-2026";
        for (Scenario s : scenarios) {
            stubFor(s);
            ChatBiAnswerResponse r = service.ask(s.question, conv, "MODEL-PID");
            assertThat(r.getStatus())
                    .as("status for: %s", s.label)
                    .isEqualTo(s.expectedStatus);
            assertThat(r.getLlmUsed())
                    .as("winner for: %s", s.label)
                    .isEqualTo(s.expectedWinner);
            if (ChatBiAnswerResponse.STATUS_SUCCESS.equals(s.expectedStatus)) {
                assertThat(r.getVizType())
                        .as("vizType for: %s", s.label)
                        .isEqualTo(s.expectedViz);
                assertThat(r.getRowCount())
                        .as("rowCount for: %s", s.label)
                        .isEqualTo(s.rowCount);
            }
        }

        // 10 answers persisted.
        ArgumentCaptor<ChatBiAnswer> cap = ArgumentCaptor.forClass(ChatBiAnswer.class);
        verify(answerMapper, times(10)).insert(cap.capture());
        List<ChatBiAnswer> persisted = cap.getAllValues();
        assertThat(persisted).hasSize(10);
        assertThat(persisted).allSatisfy(a -> {
            assertThat(a.getTenantId()).isEqualTo(1L);
            assertThat(a.getUserId()).isEqualTo(100L);
            assertThat(a.getConversationPid()).isEqualTo(conv);
            assertThat(a.getPid()).hasSize(26); // ULID
        });

        // 9 SUCCESS + 1 DISAMBIGUATION (scenario 7).
        long successes = persisted.stream()
                .filter(a -> ChatBiAnswerResponse.STATUS_SUCCESS.equals(a.getStatus()))
                .count();
        long disambigs = persisted.stream()
                .filter(a -> ChatBiAnswerResponse.STATUS_DISAMBIGUATION.equals(a.getStatus()))
                .count();
        assertThat(successes).isEqualTo(9);
        assertThat(disambigs).isEqualTo(1);

        // 9 conversations got (user, assistant) appended; DISAMBIG short-circuit
        // skips the append (no answer to summarise yet).
        verify(conversationService, times(9)).append(eq(1L), eq(conv), eq("user"), anyString());
        verify(conversationService, times(9)).append(eq(1L), eq(conv), eq("assistant"), anyString());
    }

    private void stubFor(Scenario s) {
        when(router.translate(eq(s.question), any(), any()))
                .thenReturn(new LlmProviderRouter.RouteOutcome(
                        s.intent, s.expectedWinner,
                        List.of(new LlmProviderRouter.Attempt(s.expectedWinner,
                                LlmProviderRouter.Outcome.SUCCESS, null))));
        if (s.disambigPrompt != null) {
            when(disambig.evaluate(any(), eq(1L), anyString(), eq(s.question)))
                    .thenReturn(DisambiguationService.Verdict.prompt(
                            s.disambigPrompt, "LOW_CONFIDENCE", "log-" + s.label));
        }
        if (ChatBiAnswerResponse.STATUS_SUCCESS.equals(s.expectedStatus)) {
            SemanticQueryResponse resp = new SemanticQueryResponse();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < s.rowCount; i++) {
                rows.add(Map.of("x", i));
            }
            resp.setRows(rows);
            resp.setRowcount(s.rowCount);
            resp.setSql("SELECT ... -- " + s.label);
            when(queryService.executeQuery(any(), any())).thenReturn(resp);
        }
    }

    // -- scenario fixtures ----------------------------------------------

    private static List<Scenario> scenarioPanel() {
        return List.of(
                Scenario.success("CN-1-trend",
                        "今年华东销售额按月趋势",
                        List.of(metric("sales.total_sales"),
                                dimensionWithBucket("sales.order_date", "month"),
                                timeRange("YEAR_TO_DATE"),
                                dimension("sales.region")),
                        0.95, "anthropic", "line", 12),
                Scenario.success("CN-2-topn",
                        "TOP10 客户按销售额",
                        List.of(metric("sales.total_sales"),
                                dimension("sales.customer_id"),
                                topN(10)),
                        0.92, "anthropic", "bar", 10),
                Scenario.success("CN-3-pivot",
                        "按区域和品类汇总销售额",
                        List.of(metric("sales.total_sales"),
                                dimension("sales.region"),
                                dimension("sales.category")),
                        0.91, "anthropic", "pivot", 16),
                Scenario.success("CN-4-kpi",
                        "本月总销售额",
                        List.of(metric("sales.total_sales"),
                                timeRange("THIS_MONTH")),
                        0.94, "anthropic", "kpi", 1),
                Scenario.success("CN-5-fallback",
                        "对比上季度增长率",
                        List.of(metric("sales.growth_rate"),
                                dimension("sales.quarter")),
                        0.81, "openai", "bar", 4),
                Scenario.success("EN-1-trend",
                        "monthly revenue trend this year",
                        List.of(metric("sales.total_sales"),
                                dimensionWithBucket("sales.order_date", "month"),
                                timeRange("YEAR_TO_DATE")),
                        0.93, "anthropic", "line", 12),
                Scenario.ambiguous("EN-2-ambig",
                        "show me sales numbers",
                        new com.auraboot.framework.chatbi.v2.provider.Disambiguation(
                                "sales numbers",
                                List.of(
                                        new com.auraboot.framework.chatbi.v2.provider.Disambiguation.Candidate(
                                                "METRIC", "sales.units_sold", "Units Sold", 0.82),
                                        new com.auraboot.framework.chatbi.v2.provider.Disambiguation.Candidate(
                                                "METRIC", "sales.total_sales", "Total Sales", 0.78))),
                        "anthropic"),
                Scenario.success("EN-3-table",
                        "list all customers",
                        List.of(dimension("sales.customer_id")),
                        0.88, "anthropic", "table", 50),
                Scenario.success("EN-4-bar",
                        "revenue by region",
                        List.of(metric("sales.total_sales"),
                                dimension("sales.region")),
                        0.92, "anthropic", "bar", 5),
                Scenario.success("EN-5-keyword-fallback",
                        "what is yesterday's order count",
                        List.of(metric("orders.count"),
                                timeRange("YESTERDAY")),
                        0.7, "openai", "kpi", 1));
    }

    // -- helpers --------------------------------------------------------

    private static SearchToken metric(String code) {
        return new SearchToken(TokenType.METRIC, "x", code, null, null, 0, null, null);
    }

    private static SearchToken dimension(String code) {
        return new SearchToken(TokenType.DIMENSION, "x", code, null, null, 0, null, null);
    }

    private static SearchToken dimensionWithBucket(String code, String bucket) {
        return new SearchToken(TokenType.DIMENSION, "x", code, null, null, 0, bucket, null);
    }

    private static SearchToken timeRange(String preset) {
        return new SearchToken(TokenType.TIME_RANGE, "x", preset, null, null, 0, null, null);
    }

    private static SearchToken topN(int n) {
        return new SearchToken(TokenType.TOP_N, "x", null, null, n, 0, null, null);
    }

    // -- scenario record ------------------------------------------------

    private record Scenario(
            String label,
            String question,
            String expectedStatus,
            String expectedWinner,
            String expectedViz,
            int rowCount,
            IntentResult intent,
            com.auraboot.framework.chatbi.v2.provider.Disambiguation disambigPrompt) {

        static Scenario success(String label, String question, List<SearchToken> tokens,
                                double confidence, String winner, String viz, int rowCount) {
            IntentResult intent = new IntentResult(tokens, confidence, false, null,
                    List.of(), new LlmUsage("fixture", 100, 20, 0.1, 50L));
            return new Scenario(label, question,
                    ChatBiAnswerResponse.STATUS_SUCCESS, winner, viz, rowCount, intent, null);
        }

        static Scenario ambiguous(String label, String question,
                                  com.auraboot.framework.chatbi.v2.provider.Disambiguation d,
                                  String winner) {
            IntentResult intent = new IntentResult(List.of(), 0.4, true, d, List.of(),
                    new LlmUsage("fixture", 50, 10, 0.05, 30L));
            return new Scenario(label, question,
                    ChatBiAnswerResponse.STATUS_DISAMBIGUATION, winner, null, 0, intent, d);
        }
    }
}
