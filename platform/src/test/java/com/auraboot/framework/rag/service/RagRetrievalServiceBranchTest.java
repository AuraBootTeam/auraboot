package com.auraboot.framework.rag.service;

import com.auraboot.framework.rag.dto.RetrievalResult;
import com.auraboot.framework.rag.entity.KnowledgeBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagRetrievalService branch coverage")
class RagRetrievalServiceBranchTest {

    @Mock private EmbeddingService embeddingService;
    @Mock private KnowledgeBaseService kbService;
    @Mock private QueryRewriteService queryRewriteService;
    @Mock private JdbcTemplate jdbcTemplate;

    private RagRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new RagRetrievalService(embeddingService, kbService, queryRewriteService, jdbcTemplate);
    }

    @Test
    @DisplayName("retrieve returns empty for null query")
    void retrieveNullQuery() {
        assertTrue(service.retrieve(1L, null, null, null, null).isEmpty());
    }

    @Test
    @DisplayName("retrieve returns empty for blank query")
    void retrieveBlankQuery() {
        assertTrue(service.retrieve(1L, "   ", null, null, null).isEmpty());
    }

    @Test
    @DisplayName("retrieve returns empty when no active KBs found")
    void retrieveNoActiveKbs() {
        when(queryRewriteService.rewrite(anyString()))
                .thenReturn(new QueryRewriteService.QueryRewriteResult("q", "q", false));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(1L)))
                .thenReturn(Collections.emptyList());

        List<RetrievalResult> result = service.retrieve(1L, "q", null, null, null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("retrieve returns empty when first KB lookup yields null")
    void retrieveNullFirstKb() {
        when(queryRewriteService.rewrite(anyString()))
                .thenReturn(new QueryRewriteService.QueryRewriteResult("q", "q", false));
        when(kbService.findKbByPid("kb1")).thenReturn(null);

        List<RetrievalResult> result = service.retrieve(1L, "q", List.of("kb1"), null, null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("retrieve falls back to keyword search when embedding throws")
    void retrieveEmbeddingFails() {
        when(queryRewriteService.rewrite(anyString()))
                .thenReturn(new QueryRewriteService.QueryRewriteResult("q", "q", false));
        KnowledgeBase kb = new KnowledgeBase();
        kb.setEmbeddingProvider("openai");
        when(kbService.findKbByPid("kb1")).thenReturn(kb);
        when(embeddingService.embed(eq(1L), eq("q"), eq("openai")))
                .thenThrow(new RuntimeException("api down"));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("sql"));
        when(queryRewriteService.rerank(any(), eq("q"), eq(5))).thenReturn(List.of());

        List<RetrievalResult> result = service.retrieve(1L, "q", List.of("kb1"), null, null);
        assertTrue(result.isEmpty());
        verify(queryRewriteService).rerank(any(), eq("q"), eq(5));
    }

    @Test
    @DisplayName("retrieve clamps topK above 20")
    void retrieveClampTopK() {
        when(queryRewriteService.rewrite(anyString()))
                .thenReturn(new QueryRewriteService.QueryRewriteResult("q", "q", false));
        KnowledgeBase kb = new KnowledgeBase();
        kb.setEmbeddingProvider("openai");
        when(kbService.findKbByPid("kb1")).thenReturn(kb);
        when(embeddingService.embed(eq(1L), eq("q"), eq("openai"))).thenReturn(new float[]{0.1f, 0.2f});
        lenient().when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(queryRewriteService.rerank(any(), eq("q"), eq(20))).thenReturn(List.of());

        service.retrieve(1L, "q", List.of("kb1"), 999, 0.5);
        verify(queryRewriteService).rerank(any(), eq("q"), eq(20));
    }

    @Test
    @DisplayName("hasActiveKnowledgeBases returns true when count > 0")
    void hasActiveTrue() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(1L))).thenReturn(3);
        assertTrue(service.hasActiveKnowledgeBases(1L));
    }

    @Test
    @DisplayName("hasActiveKnowledgeBases returns false when count is zero")
    void hasActiveZero() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(1L))).thenReturn(0);
        assertFalse(service.hasActiveKnowledgeBases(1L));
    }

    @Test
    @DisplayName("hasActiveKnowledgeBases returns false when count is null")
    void hasActiveNull() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(1L))).thenReturn(null);
        assertFalse(service.hasActiveKnowledgeBases(1L));
    }

    @Test
    @DisplayName("hasActiveKnowledgeBases swallows exception and returns false")
    void hasActiveSwallowsException() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(1L)))
                .thenThrow(new RuntimeException("db"));
        assertFalse(service.hasActiveKnowledgeBases(1L));
    }

    @Test
    @DisplayName("buildRagContext returns empty for null input")
    void buildRagContextNull() {
        assertEquals("", service.buildRagContext(null));
    }

    @Test
    @DisplayName("buildRagContext returns empty for empty list")
    void buildRagContextEmpty() {
        assertEquals("", service.buildRagContext(List.of()));
    }

    @Test
    @DisplayName("buildRagContext formats results into reference section")
    void buildRagContextFormats() {
        RetrievalResult r = RetrievalResult.builder()
                .chunkPid("c1")
                .docName("doc-A")
                .chunkIndex(7)
                .content("body content")
                .build();
        String out = service.buildRagContext(List.of(r));
        assertTrue(out.contains("## Reference Knowledge"));
        assertTrue(out.contains("Source: doc-A"));
        assertTrue(out.contains("Chunk 7"));
        assertTrue(out.contains("body content"));
    }

    @Test
    @DisplayName("buildTsQuery returns empty for null/blank")
    void buildTsQueryEmptyForBlank() {
        assertEquals("", RagRetrievalService.buildTsQuery(null));
        assertEquals("", RagRetrievalService.buildTsQuery("   "));
    }

    @Test
    @DisplayName("buildTsQuery joins Latin words with OR")
    void buildTsQueryLatin() {
        assertEquals("hello | world", RagRetrievalService.buildTsQuery("hello world"));
    }

    @Test
    @DisplayName("buildTsQuery splits each CJK character into a separate term")
    void buildTsQueryCjk() {
        String out = RagRetrievalService.buildTsQuery("你好");
        assertEquals("你 | 好", out);
    }

    @Test
    @DisplayName("buildTsQuery flushes Latin buffer before CJK char")
    void buildTsQueryMixed() {
        String out = RagRetrievalService.buildTsQuery("foo你bar");
        assertEquals("foo | 你 | bar", out);
    }

    @Test
    @DisplayName("buildTsQuery skips punctuation, keeps underscore and digits")
    void buildTsQueryPunctuationSkipped() {
        String out = RagRetrievalService.buildTsQuery("foo_1, bar!");
        assertEquals("foo_1 | bar", out);
    }

    @Test
    @DisplayName("buildTsQuery on punctuation-only input returns empty")
    void buildTsQueryPunctOnly() {
        assertEquals("", RagRetrievalService.buildTsQuery("!!!,."));
    }
}
