package com.auraboot.framework.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Retrieval results plus user-visible diagnostics (G8): conditions that
 * silently reduced recall (e.g. knowledge bases dropped for embedding
 * dimension mismatch) are surfaced as warnings instead of log-only.
 */
@Data
@AllArgsConstructor
public class RetrievalOutcome {
    private List<RetrievalResult> results;
    private List<String> warnings;

    /**
     * Which search path actually served this query: {@code hybrid} (vector + keyword),
     * {@code keyword} (the fallback taken when the query could not be embedded), or {@code none}
     * (nothing was searched).
     *
     * <p>Recall silently halves when embedding fails — the caller still gets results, so nothing
     * looks broken. Reporting the path is what turns that from a mystery into a diagnosis, and it
     * is the only way a caller can tell a real vector search from a keyword search wearing its
     * clothes.
     */
    private String path;
}
