package com.auraboot.framework.chatbi.v2.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.chatbi.v2.compiler.TokenCompileException;
import com.auraboot.framework.chatbi.v2.compiler.TokenCompiler;
import com.auraboot.framework.chatbi.v2.dto.ChatBiAnswerResponse;
import com.auraboot.framework.chatbi.v2.dto.SearchToken;
import com.auraboot.framework.chatbi.v2.dto.TokenType;
import com.auraboot.framework.chatbi.v2.entity.ChatBiAnswer;
import com.auraboot.framework.chatbi.v2.mapper.ChatBiAnswerMapper;
import com.auraboot.framework.chatbi.v2.provider.ConversationContext;
import com.auraboot.framework.chatbi.v2.provider.Disambiguation;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatBiAnswerServiceTest {

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

        // Real persistence bean over the mocked mapper + conversation service. In a
        // plain unit test the REQUIRES_NEW proxy is a no-op, so it simply forwards to
        // the mocks — the transaction isolation itself is asserted structurally by
        // bestEffortPersistenceRunsInRequiresNewTransaction() below.
        persistence = new ChatBiAnswerPersistence(answerMapper, conversationService);

        service = new ChatBiAnswerService(router, catalog, queryService,
                compiler, conversationService, disambig, persistence);

        MetaContext.setCurrentTenantId(1L);
        MetaContext.setCurrentUserId(100L);
    }

    @AfterEach
    void teardown() {
        MetaContext.clear();
    }

    private static IntentResult goodIntent(double confidence) {
        return new IntentResult(
                List.of(new SearchToken(TokenType.METRIC, "sales", "sales.total_sales",
                                        null, null, 0, null, null)),
                confidence, false, null, List.of("by region"),
                new LlmUsage("claude-sonnet-4-7", 100, 20, 0.5, 80L));
    }

    private static LlmProviderRouter.RouteOutcome routeOutcome(IntentResult r, String winner) {
        return new LlmProviderRouter.RouteOutcome(r, winner, List.of(
                new LlmProviderRouter.Attempt(winner,
                        LlmProviderRouter.Outcome.SUCCESS, null)));
    }

    // -- happy path ------------------------------------------------------

    @Test
    void askHappyPathCompilesExecutesPersistsAndAppends() throws Exception {
        when(catalog.listCatalog(1L)).thenReturn(new SemanticMetaResponse());
        when(router.translate(eq("sales by month"), any(), any()))
                .thenReturn(routeOutcome(goodIntent(0.95), "anthropic"));
        when(disambig.evaluate(any(), eq(1L), anyString(), anyString()))
                .thenReturn(DisambiguationService.Verdict.useTop1());
        when(compiler.compile(any(), any())).thenReturn(new SemanticQueryRequest());
        SemanticQueryResponse exec = new SemanticQueryResponse();
        exec.setRows(List.of(Map.of("month", "2026-01", "total_sales", 100)));
        exec.setRowcount(1);
        exec.setSql("SELECT ...");
        when(queryService.executeQuery(any(), any())).thenReturn(exec);

        ChatBiAnswerResponse r = service.ask("sales by month", "CONV-PID", "MODEL-PID");

        assertThat(r.getStatus()).isEqualTo(ChatBiAnswerResponse.STATUS_SUCCESS);
        assertThat(r.getRowCount()).isEqualTo(1);
        assertThat(r.getRows()).hasSize(1);
        assertThat(r.getLlmUsed()).isEqualTo("anthropic");
        assertThat(r.getAnswerPid()).hasSize(26); // ULID

        // Conversation appends: user question + assistant summary.
        verify(conversationService).append(1L, "CONV-PID", "user", "sales by month");
        verify(conversationService).append(1L, "CONV-PID", "assistant", "(1 rows)");
        // Persisted with SUCCESS status.
        ArgumentCaptor<ChatBiAnswer> cap = ArgumentCaptor.forClass(ChatBiAnswer.class);
        verify(answerMapper).insert(cap.capture());
        assertThat(cap.getValue().getStatus())
                .isEqualTo(ChatBiAnswerResponse.STATUS_SUCCESS);
        assertThat(cap.getValue().getLlmUsed()).isEqualTo("anthropic");
    }

    @Test
    void askWithoutConversationPidSkipsAppend() throws Exception {
        when(catalog.listCatalog(1L)).thenReturn(new SemanticMetaResponse());
        when(router.translate(any(), any(), any()))
                .thenReturn(routeOutcome(goodIntent(0.9), "anthropic"));
        when(disambig.evaluate(any(), anyLong(), anyString(), anyString()))
                .thenReturn(DisambiguationService.Verdict.useTop1());
        when(compiler.compile(any(), any())).thenReturn(new SemanticQueryRequest());
        SemanticQueryResponse exec = new SemanticQueryResponse();
        exec.setRowcount(0);
        when(queryService.executeQuery(any(), any())).thenReturn(exec);

        ChatBiAnswerResponse r = service.ask("ad-hoc", null, null);

        assertThat(r.getStatus()).isEqualTo(ChatBiAnswerResponse.STATUS_SUCCESS);
        verify(conversationService, never()).loadContext(anyLong(), anyString());
        verify(conversationService, never()).append(anyLong(), anyString(), anyString(), anyString());
    }

    // -- TX-003: best-effort persistence failures must not fail the answer ---

    private void stubHappyPath() throws Exception {
        when(catalog.listCatalog(1L)).thenReturn(new SemanticMetaResponse());
        when(router.translate(any(), any(), any()))
                .thenReturn(routeOutcome(goodIntent(0.95), "anthropic"));
        when(disambig.evaluate(any(), eq(1L), anyString(), anyString()))
                .thenReturn(DisambiguationService.Verdict.useTop1());
        when(compiler.compile(any(), any())).thenReturn(new SemanticQueryRequest());
        SemanticQueryResponse exec = new SemanticQueryResponse();
        exec.setRows(List.of(Map.of("month", "2026-01", "total_sales", 100)));
        exec.setRowcount(1);
        exec.setSql("SELECT ...");
        when(queryService.executeQuery(any(), any())).thenReturn(exec);
    }

    @Test
    void conversationAppendFailureDoesNotFailAnswer() throws Exception {
        stubHappyPath();
        // Simulate the aborting write: append blows up (e.g. 25P02 on a bad row).
        doThrow(new RuntimeException("current transaction is aborted (25P02)"))
                .when(conversationService).append(anyLong(), anyString(), anyString(), anyString());

        ChatBiAnswerResponse r = service.ask("sales by month", "CONV-PID", "MODEL-PID");

        // The answer itself still succeeds with its rows; no exception escapes.
        assertThat(r.getStatus()).isEqualTo(ChatBiAnswerResponse.STATUS_SUCCESS);
        assertThat(r.getRowCount()).isEqualTo(1);
        assertThat(r.getRows()).hasSize(1);
    }

    @Test
    void answerPersistFailureDoesNotFailAnswer() throws Exception {
        stubHappyPath();
        // Simulate the answer-row insert failing.
        doThrow(new RuntimeException("insert failed"))
                .when(answerMapper).insert(any(ChatBiAnswer.class));

        ChatBiAnswerResponse r = service.ask("sales by month", "CONV-PID", "MODEL-PID");

        assertThat(r.getStatus()).isEqualTo(ChatBiAnswerResponse.STATUS_SUCCESS);
        assertThat(r.getRowCount()).isEqualTo(1);
        assertThat(r.getRows()).hasSize(1);
    }

    /**
     * Falsifiable structural guard: the real transaction isolation relies on the
     * best-effort writes running in a SEPARATE physical transaction. A plain unit
     * test cannot exercise the transaction manager, so assert the annotation is
     * actually present — this goes red if a regression drops REQUIRES_NEW or inlines
     * the writes back into {@code ask()}.
     */
    @Test
    void bestEffortPersistenceRunsInRequiresNewTransaction() {
        for (String name : List.of("persistAnswer", "appendTurn")) {
            Method method = Arrays.stream(ChatBiAnswerPersistence.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "ChatBiAnswerPersistence." + name + " must exist"));
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as("%s must be @Transactional", name).isNotNull();
            assertThat(tx.propagation()).as("%s must be REQUIRES_NEW", name)
                    .isEqualTo(Propagation.REQUIRES_NEW);
        }
    }

    // -- short-circuits --------------------------------------------------

    @Test
    void emptyQuestionShortCircuitsToFailed() {
        ChatBiAnswerResponse r = service.ask("   ", "CONV", null);
        assertThat(r.getStatus()).isEqualTo(ChatBiAnswerResponse.STATUS_FAILED);
        assertThat(r.getErrorMessage()).contains("empty");
        verify(router, never()).translate(anyString(), any(), any());
    }

    @Test
    void disambiguationShortCircuitsBeforeCompile() throws Exception {
        when(catalog.listCatalog(1L)).thenReturn(new SemanticMetaResponse());
        Disambiguation d = new Disambiguation("销量", List.of(
                new Disambiguation.Candidate("METRIC", "sales.units_sold", "数量", 0.82),
                new Disambiguation.Candidate("METRIC", "sales.total_sales", "金额", 0.78)));
        IntentResult ambig = new IntentResult(List.of(), 0.4, true, d, List.of(),
                new LlmUsage("claude-sonnet-4-7", 50, 10, 0.2, 40L));
        when(router.translate(any(), any(), any()))
                .thenReturn(routeOutcome(ambig, "anthropic"));
        when(disambig.evaluate(any(), eq(1L), anyString(), anyString()))
                .thenReturn(DisambiguationService.Verdict.prompt(d, "LOW_CONFIDENCE", "log-pid"));

        ChatBiAnswerResponse r = service.ask("销量", "CONV", null);

        assertThat(r.getStatus()).isEqualTo(ChatBiAnswerResponse.STATUS_DISAMBIGUATION);
        assertThat(r.getDisambiguation()).isNotNull();
        assertThat(r.getDisambiguation().ambiguousTerm()).isEqualTo("销量");
        verify(compiler, never()).compile(any(), any());
        verify(queryService, never()).executeQuery(any(), any());
        ArgumentCaptor<ChatBiAnswer> cap = ArgumentCaptor.forClass(ChatBiAnswer.class);
        verify(answerMapper).insert(cap.capture());
        assertThat(cap.getValue().getStatus())
                .isEqualTo(ChatBiAnswerResponse.STATUS_DISAMBIGUATION);
    }

    // -- failures --------------------------------------------------------

    @Test
    void compileFailureReturnsFailed() throws Exception {
        when(catalog.listCatalog(1L)).thenReturn(new SemanticMetaResponse());
        when(router.translate(any(), any(), any()))
                .thenReturn(routeOutcome(goodIntent(0.9), "anthropic"));
        when(disambig.evaluate(any(), anyLong(), anyString(), anyString()))
                .thenReturn(DisambiguationService.Verdict.useTop1());
        when(compiler.compile(any(), any()))
                .thenThrow(new TokenCompileException("E_UNMAPPED", "unmapped METRIC"));

        ChatBiAnswerResponse r = service.ask("garbled", "CONV", null);

        assertThat(r.getStatus()).isEqualTo(ChatBiAnswerResponse.STATUS_FAILED);
        assertThat(r.getErrorMessage()).contains("compile");
        verify(queryService, never()).executeQuery(any(), any());
    }

    @Test
    void executeFailureReturnsFailed() throws Exception {
        when(catalog.listCatalog(1L)).thenReturn(new SemanticMetaResponse());
        when(router.translate(any(), any(), any()))
                .thenReturn(routeOutcome(goodIntent(0.9), "anthropic"));
        when(disambig.evaluate(any(), anyLong(), anyString(), anyString()))
                .thenReturn(DisambiguationService.Verdict.useTop1());
        when(compiler.compile(any(), any())).thenReturn(new SemanticQueryRequest());
        when(queryService.executeQuery(any(), any()))
                .thenThrow(new RuntimeException("RLS denied"));

        ChatBiAnswerResponse r = service.ask("denied query", "CONV", null);

        assertThat(r.getStatus()).isEqualTo(ChatBiAnswerResponse.STATUS_FAILED);
        assertThat(r.getErrorMessage()).contains("execution");
    }

    // -- viz heuristic ---------------------------------------------------

    @Test
    void suggestVizTypeBucketsTimeSeriesAsLine() {
        List<SearchToken> tokens = List.of(
                new SearchToken(TokenType.METRIC, "x", "s.t", null, null, 0, null, null),
                new SearchToken(TokenType.DIMENSION, "d", "s.d", null, null, 1, "month", null));
        assertThat(ChatBiAnswerService.suggestVizType(tokens, 12)).isEqualTo("line");
    }

    @Test
    void suggestVizTypeSingleMetricSingleRowIsKpi() {
        List<SearchToken> tokens = List.of(
                new SearchToken(TokenType.METRIC, "x", "s.t", null, null, 0, null, null));
        assertThat(ChatBiAnswerService.suggestVizType(tokens, 1)).isEqualTo("kpi");
    }

    @Test
    void suggestVizTypeOneDimensionPlusMetricIsBar() {
        List<SearchToken> tokens = List.of(
                new SearchToken(TokenType.METRIC, "x", "s.t", null, null, 0, null, null),
                new SearchToken(TokenType.DIMENSION, "d", "s.region", null, null, 1, null, null));
        assertThat(ChatBiAnswerService.suggestVizType(tokens, 4)).isEqualTo("bar");
    }

    @Test
    void suggestVizTypeTwoDimensionsIsPivot() {
        List<SearchToken> tokens = List.of(
                new SearchToken(TokenType.METRIC, "x", "s.t", null, null, 0, null, null),
                new SearchToken(TokenType.DIMENSION, "d1", "s.region", null, null, 1, null, null),
                new SearchToken(TokenType.DIMENSION, "d2", "s.cat", null, null, 2, null, null));
        assertThat(ChatBiAnswerService.suggestVizType(tokens, 16)).isEqualTo("pivot");
    }

    @Test
    void suggestVizTypeEmptyTokensIsTable() {
        assertThat(ChatBiAnswerService.suggestVizType(List.of(), 5)).isEqualTo("table");
        assertThat(ChatBiAnswerService.suggestVizType(null, 5)).isEqualTo("table");
    }
}
