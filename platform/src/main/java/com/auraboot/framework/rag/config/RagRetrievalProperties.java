package com.auraboot.framework.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for the RAG (Path A) retrieval relevance-rejection floor (G10).
 *
 * <p>Background: the Phase-2 golden-query eval found that every off-topic
 * ({@code expected_path=neither}) query still retrieved <em>something</em> —
 * keyword {@code tsquery @@ tsv} OR-matching returns any chunk sharing a single
 * incidental term, so correct-rejection rate was 0/10. Without a floor the chat
 * layer cannot trust a "no relevant knowledge" signal.
 *
 * <p>The floor is a per-result keep/drop decision applied after rerank, using
 * whichever signal the retrieval mode actually produced (see
 * {@code RagRetrievalService}):
 * <ul>
 *   <li><b>hybrid mode</b> (embedding present): keep iff the chunk's vector
 *       similarity ≥ {@link #minVectorSimilarity}. A chunk that only matched via
 *       the keyword OR-branch carries its real (low) cosine similarity, so an
 *       off-topic query that merely shares platform vocabulary is rejected while
 *       a semantic paraphrase the vector leg found is kept.</li>
 *   <li><b>keyword-fallback mode</b> (no embedding key — distance is 1.0, no
 *       vector signal): keep iff the chunk's keyword coverage ≥
 *       {@link #minKeywordCoverage} (fraction of the query's distinct terms the
 *       chunk contains).</li>
 * </ul>
 *
 * <p>Defaults are calibrated from the 52-query golden set first run so that no
 * answerable true hit is dropped (Path A keyword recall stays 0.600): the lowest
 * true-hit coverage observed was 0.273 and the highest off-topic coverage below
 * it was 0.250. {@link #minVectorSimilarity} mirrors the existing
 * {@code DEFAULT_THRESHOLD=0.8} cosine-distance gate (1 − 0.8) and stays inert
 * until an embedding key is configured; recalibrate it on the live-mode rerun.
 * See docs/backlog/2026-06-10-rag-system-review-and-gap-tracker.md G10.
 */
@Data
@Component
@ConfigurationProperties(prefix = "aurabot.rag")
public class RagRetrievalProperties {

    /** Master switch for the Path-A rejection floor (G10). Default on. */
    private boolean rejectionFloorEnabled = true;

    /**
     * Minimum keyword coverage (distinct query terms matched / total) a chunk
     * must reach in keyword-fallback mode to survive the floor. Must stay below
     * the lowest answerable true-hit coverage (0.273 on the golden set).
     */
    private double minKeywordCoverage = 0.27;

    /**
     * Minimum vector similarity (1 − cosine distance) a chunk must reach in
     * hybrid mode to survive the floor. Mirrors the cosine-distance gate
     * (1 − {@code 0.8}); inert in keyword-fallback mode where similarity is 0.
     * Recalibrate on the live embedding-key rerun.
     */
    private double minVectorSimilarity = 0.20;
}
