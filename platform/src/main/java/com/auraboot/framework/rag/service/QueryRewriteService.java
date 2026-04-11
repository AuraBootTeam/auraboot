package com.auraboot.framework.rag.service;

import com.auraboot.framework.rag.config.SynonymConfig;
import com.auraboot.framework.rag.dto.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Query rewrite and result reranking for RAG retrieval.
 * <p>
 * Query expansion: adds related terms for short/ambiguous queries using a
 * configurable synonym map loaded from {@code aurabot/synonyms.yml}.
 * Reranking: boosts results whose content has higher term overlap with the query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteService {

    private final SynonymConfig synonymConfig;

    /**
     * Expand a query by adding related terms for short or domain-specific queries.
     *
     * @param query original user query
     * @return expanded query (original + expansions), or original if no expansion needed
     */
    public QueryRewriteResult rewrite(String query) {
        if (query == null || query.isBlank()) {
            return new QueryRewriteResult(query, query, false);
        }

        String normalized = query.toLowerCase().trim();
        Set<String> expansionTerms = new LinkedHashSet<>();

        // Split query into words
        String[] words = normalized.split("\\s+");

        // Only expand short queries (1-3 words) to avoid over-expansion
        if (words.length > 3) {
            return new QueryRewriteResult(query, query, false);
        }

        Map<String, List<String>> expansions = synonymConfig.getExpansions();
        for (String word : words) {
            List<String> related = expansions.get(word);
            if (related != null) {
                expansionTerms.addAll(related);
            }
        }

        if (expansionTerms.isEmpty()) {
            return new QueryRewriteResult(query, query, false);
        }

        // Build expanded query: original terms + expansion terms
        String expanded = query + " " + String.join(" ", expansionTerms);
        log.debug("Query expanded: '{}' → '{}'", query, expanded);
        return new QueryRewriteResult(query, expanded, true);
    }

    /**
     * Rerank retrieval results by computing content-query term overlap score.
     * Boosts results whose content contains more query terms (case-insensitive).
     *
     * @param results    original retrieval results
     * @param query      user query (for term matching)
     * @param maxResults max results to return after reranking
     * @return reranked list (highest relevance first)
     */
    public List<RetrievalResult> rerank(List<RetrievalResult> results, String query, int maxResults) {
        if (results == null || results.isEmpty() || query == null || query.isBlank()) {
            return results != null ? results : List.of();
        }

        // Extract query terms (lowercase, deduplicated)
        Set<String> queryTerms = Arrays.stream(query.toLowerCase().split("\\s+"))
                .filter(t -> t.length() > 1) // skip single chars
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (queryTerms.isEmpty()) return results;

        // Score each result by term overlap
        List<ScoredResult> scored = results.stream()
                .map(r -> {
                    String contentLower = r.getContent().toLowerCase();
                    double termHits = 0;
                    for (String term : queryTerms) {
                        if (contentLower.contains(term)) termHits++;
                    }
                    // Overlap ratio (0..1)
                    double overlapScore = termHits / queryTerms.size();

                    // Combined score: 60% hybrid (vector+bm25) + 40% term overlap
                    double rerankedScore = 0.6 * r.getHybridScore() + 0.4 * overlapScore;

                    return new ScoredResult(r, rerankedScore);
                })
                .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
                .limit(maxResults)
                .toList();

        return scored.stream()
                .map(sr -> {
                    RetrievalResult r = sr.result();
                    // Update hybrid score to reflect reranking
                    r.setHybridScore(sr.score());
                    return r;
                })
                .toList();
    }

    public record QueryRewriteResult(String originalQuery, String expandedQuery, boolean wasExpanded) {}

    private record ScoredResult(RetrievalResult result, double score) {}
}
