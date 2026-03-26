package com.auraboot.framework.rag.service;

import com.auraboot.framework.rag.dto.RetrievalResult;
import com.auraboot.framework.rag.service.QueryRewriteService.QueryRewriteResult;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for QueryRewriteService — query expansion and result reranking.
 */
class QueryRewriteServiceTest {

    private final QueryRewriteService service = new QueryRewriteService();

    // =========================================================================
    // Query expansion
    // =========================================================================

    @Test
    @DisplayName("QR-01: Short domain query gets expanded")
    void rewrite_domainQuery() {
        QueryRewriteResult result = service.rewrite("bpm");
        assertThat(result.wasExpanded()).isTrue();
        assertThat(result.expandedQuery()).contains("workflow");
        assertThat(result.expandedQuery()).contains("approval");
        assertThat(result.originalQuery()).isEqualTo("bpm");
    }

    @Test
    @DisplayName("QR-02: Multi-word domain query gets expanded")
    void rewrite_multiWord() {
        QueryRewriteResult result = service.rewrite("auth plugin");
        assertThat(result.wasExpanded()).isTrue();
        assertThat(result.expandedQuery()).contains("login");
        assertThat(result.expandedQuery()).contains("module");
    }

    @Test
    @DisplayName("QR-03: Long query (>3 words) is NOT expanded")
    void rewrite_longQuery() {
        QueryRewriteResult result = service.rewrite("how to configure tenant settings properly");
        assertThat(result.wasExpanded()).isFalse();
        assertThat(result.expandedQuery()).isEqualTo(result.originalQuery());
    }

    @Test
    @DisplayName("QR-04: Unknown term is NOT expanded")
    void rewrite_unknownTerm() {
        QueryRewriteResult result = service.rewrite("kubernetes");
        assertThat(result.wasExpanded()).isFalse();
    }

    @Test
    @DisplayName("QR-05: Null/blank query returns unchanged")
    void rewrite_nullBlank() {
        assertThat(service.rewrite(null).wasExpanded()).isFalse();
        assertThat(service.rewrite("").wasExpanded()).isFalse();
        assertThat(service.rewrite("  ").wasExpanded()).isFalse();
    }

    @Test
    @DisplayName("QR-06: CRM expands to sales-related terms")
    void rewrite_crm() {
        QueryRewriteResult result = service.rewrite("crm");
        assertThat(result.wasExpanded()).isTrue();
        assertThat(result.expandedQuery()).contains("lead");
        assertThat(result.expandedQuery()).contains("opportunity");
    }

    // =========================================================================
    // Reranking
    // =========================================================================

    @Test
    @DisplayName("RR-01: Reranking boosts results with higher term overlap")
    void rerank_boostsOverlap() {
        List<RetrievalResult> results = new ArrayList<>();
        results.add(RetrievalResult.builder()
                .chunkPid("c1").docName("doc1").content("This is about database indexing")
                .hybridScore(0.9).build());
        results.add(RetrievalResult.builder()
                .chunkPid("c2").docName("doc2").content("Permission RBAC role access control")
                .hybridScore(0.8).build());

        List<RetrievalResult> reranked = service.rerank(results, "permission access", 5);

        assertThat(reranked).hasSize(2);
        // c2 should be ranked higher due to term overlap with "permission" and "access"
        assertThat(reranked.get(0).getChunkPid()).isEqualTo("c2");
    }

    @Test
    @DisplayName("RR-02: Reranking with empty results returns empty")
    void rerank_empty() {
        assertThat(service.rerank(List.of(), "query", 5)).isEmpty();
        assertThat(service.rerank(null, "query", 5)).isEmpty();
    }

    @Test
    @DisplayName("RR-03: Reranking respects maxResults limit")
    void rerank_maxResults() {
        List<RetrievalResult> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(RetrievalResult.builder()
                    .chunkPid("c" + i).docName("doc" + i).content("content " + i)
                    .hybridScore(0.5 + i * 0.01).build());
        }

        List<RetrievalResult> reranked = service.rerank(results, "content", 3);
        assertThat(reranked).hasSize(3);
    }

    @Test
    @DisplayName("RR-04: Reranking with null query returns original order")
    void rerank_nullQuery() {
        List<RetrievalResult> results = List.of(
                RetrievalResult.builder().chunkPid("c1").content("abc").hybridScore(0.5).build());
        List<RetrievalResult> reranked = service.rerank(results, null, 5);
        assertThat(reranked).hasSize(1);
    }
}
