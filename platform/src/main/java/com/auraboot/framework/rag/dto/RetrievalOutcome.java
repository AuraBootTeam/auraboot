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
}
