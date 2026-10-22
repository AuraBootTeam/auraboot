package com.auraboot.framework.rag.util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Query&#8596;content keyword-coverage signal for the relevance-rejection floor (G10).
 *
 * <p>Coverage = (distinct query terms present in the content) / (distinct query
 * terms), tokenizing <em>both</em> sides with the same {@link CjkBigramSegmenter#tsQueryTerms}
 * the BM25 leg indexes and queries with: CJK runs become overlapping bigrams,
 * latin/digit runs become lowercased words, punctuation is dropped. This mirrors
 * {@code to_tsvector('simple', segment(...))} lexeme membership closely enough
 * that a chunk the SQL returned via {@code tsquery @@ tsv} scores its true
 * fraction of matched query terms.
 *
 * <p>Why this is the floor signal rather than {@code ts_rank_cd}: raw BM25 rank
 * is unbounded and document-length dependent, so an absolute cutoff is fragile
 * across modes. Coverage is normalized to [0,1] and mode-independent — it
 * distinguishes "matched one incidental shared term" (the no-answer false
 * positives in the Phase-2 eval, e.g. an off-topic query sharing only
 * {@code API} / {@code 接口} with the corpus) from "matched a real fraction of
 * the query" without any vector leg. See
 * docs/backlog/2026-06-10-rag-system-review-and-gap-tracker.md G10.
 */
public final class KeywordCoverage {

    private KeywordCoverage() {
    }

    /**
     * @return fraction in [0,1] of the query's distinct tsquery terms that appear
     *         as terms in {@code content}; 0 when the query has no terms.
     */
    public static double coverage(String query, String content) {
        List<String> queryTerms = CjkBigramSegmenter.tsQueryTerms(query);
        if (queryTerms.isEmpty()) {
            return 0.0;
        }
        Set<String> distinctQueryTerms = new HashSet<>(queryTerms);
        Set<String> contentTerms = new HashSet<>(CjkBigramSegmenter.tsQueryTerms(content));
        long hits = distinctQueryTerms.stream().filter(contentTerms::contains).count();
        return (double) hits / distinctQueryTerms.size();
    }
}
