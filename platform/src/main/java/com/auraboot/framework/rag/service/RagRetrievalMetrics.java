package com.auraboot.framework.rag.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Micrometer metrics for the RAG retrieval path (G6) — previously only the D7
 * leg had observability. Exposes latency per search path, zero-result rate,
 * degradation reasons (embedding failure, hybrid SQL fallback, dimension
 * mismatch drops) and embedding retry outcomes.
 */
@Component
public class RagRetrievalMetrics {

    private final MeterRegistry registry;

    public RagRetrievalMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** @param path one of {@code hybrid}/{@code vector}/{@code keyword} */
    public void recordRetrieval(String path, long durationMs, int resultCount) {
        Timer.builder("rag.retrieval.duration").tag("path", path)
                .register(registry).record(Duration.ofMillis(durationMs));
        if (resultCount == 0) {
            Counter.builder("rag.retrieval.zero_results").tag("path", path)
                    .register(registry).increment();
        }
    }

    /** @param reason one of {@code embedding_failed}/{@code hybrid_sql_failed}/{@code keyword_sql_failed} */
    public void recordDegraded(String reason) {
        Counter.builder("rag.retrieval.degraded").tag("reason", reason)
                .register(registry).increment();
    }

    /** Knowledge bases dropped from a query because of embedding-provider/dimension mismatch (G8). */
    public void recordKbDropped(int count) {
        Counter.builder("rag.retrieval.kb_dropped_dimension_mismatch")
                .register(registry).increment(count);
    }

    /** Chunks dropped by the relevance-rejection floor (G10) — high counts hint the floor is too aggressive. */
    public void recordRejectionFloor(int count) {
        Counter.builder("rag.retrieval.rejection_floor_dropped")
                .register(registry).increment(count);
    }

    /** @param outcome {@code success}/{@code failed}/{@code exhausted} */
    public void recordEmbeddingRetry(String outcome, int count) {
        Counter.builder("rag.embedding.retry").tag("outcome", outcome)
                .register(registry).increment(count);
    }

    /**
     * Documents reclaimed by the parse reconcile pass (i.e. left stranded in {@code pending} /
     * {@code processing} by a worker that died mid-parse).
     *
     * @param outcome {@code recovered}/{@code failed}/{@code exhausted}
     */
    public void recordDocumentReconcile(String outcome, int count) {
        Counter.builder("rag.document.reconcile").tag("outcome", outcome)
                .register(registry).increment(count);
    }
}
