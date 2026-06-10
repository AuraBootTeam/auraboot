package com.auraboot.framework.rag.eval;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-function metrics for one retrieval result against a golden query.
 *
 * <p>See {@code docs/backlog/2026-05-27-rag-d7-eval-harness-design.md} §4 for
 * definitions. Designed to be:
 * <ul>
 *   <li>Stateless (every method static)</li>
 *   <li>Free of fallback / silent NaN — empty expected sets throw on recall/precision queries</li>
 *   <li>K-agnostic — caller controls top-K truncation before passing retrieved list</li>
 * </ul>
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {}

    /**
     * Recall@K = |retrieved ∩ expected| / |expected|.
     *
     * @throws IllegalArgumentException if expected is empty (recall undefined)
     */
    public static double recall(List<String> retrievedTopK, Collection<String> expected) {
        if (expected == null || expected.isEmpty()) {
            throw new IllegalArgumentException(
                    "recall undefined for empty expected set; use noAnswerRate for expected=neither queries");
        }
        Set<String> expectedSet = new HashSet<>(expected);
        long hits = safe(retrievedTopK).stream().filter(expectedSet::contains).count();
        return (double) hits / expectedSet.size();
    }

    /**
     * Precision@K = |retrieved ∩ expected| / K.
     *
     * <p>Uses the actual size of {@code retrievedTopK} as K (caller already
     * truncated). If caller passes empty list, returns 0.
     *
     * @throws IllegalArgumentException if expected is empty
     */
    public static double precision(List<String> retrievedTopK, Collection<String> expected) {
        if (expected == null || expected.isEmpty()) {
            throw new IllegalArgumentException(
                    "precision undefined for empty expected set; use falsePositiveRate for expected=neither queries");
        }
        List<String> retrieved = safe(retrievedTopK);
        if (retrieved.isEmpty()) return 0.0;
        Set<String> expectedSet = new HashSet<>(expected);
        long hits = retrieved.stream().filter(expectedSet::contains).count();
        return (double) hits / retrieved.size();
    }

    /**
     * Reciprocal rank = 1 / (1-based rank of the first relevant item), 0 if no
     * relevant item appears in {@code retrievedTopK}. Averaging this across
     * queries yields MRR@K (caller controls K by truncating the list).
     *
     * @throws IllegalArgumentException if expected is empty (rank undefined)
     */
    public static double reciprocalRank(List<String> retrievedTopK, Collection<String> expected) {
        if (expected == null || expected.isEmpty()) {
            throw new IllegalArgumentException(
                    "reciprocalRank undefined for empty expected set; use noAnswerRate for expected=neither queries");
        }
        Set<String> expectedSet = new HashSet<>(expected);
        List<String> retrieved = safe(retrievedTopK);
        for (int i = 0; i < retrieved.size(); i++) {
            if (expectedSet.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * For expected=neither queries: did the retriever correctly return empty?
     *
     * @return true if retrieved is empty (correct NoAnswer)
     */
    public static boolean correctNoAnswer(List<String> retrievedTopK) {
        return safe(retrievedTopK).isEmpty();
    }

    /**
     * For expected=neither queries: false-positive = returned anything.
     */
    public static boolean falsePositive(List<String> retrievedTopK) {
        return !correctNoAnswer(retrievedTopK);
    }

    private static List<String> safe(List<String> in) {
        return in == null ? List.of() : in;
    }
}
