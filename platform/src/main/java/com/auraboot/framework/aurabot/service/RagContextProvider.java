package com.auraboot.framework.aurabot.service;

import java.util.List;

/**
 * SPI interface for RAG context injection into AuraBot.
 * Implemented by RagRetrievalService in the shared AI runtime.
 * Optional — when no implementation is available, AuraBot works without RAG.
 */
public interface RagContextProvider {

    /**
     * One ranked raw-chunk score. Keeping this transport type in the SPI avoids leaking
     * the RAG module's persistence DTO into the conversation/agent layers.
     */
    record RetrievalScore(String chunkPid,
                          double vectorScore,
                          double bm25Score,
                          double hybridScore,
                          double similarity) {
    }

    /**
     * Structured, model-independent provenance for one retrieved chunk.
     * The UI and audit trail consume this record directly; they must not parse
     * citation-looking prose from the model response.
     */
    record RetrievalEvidence(String evidenceId,
                             String query,
                             String kbPid,
                             String kbName,
                             String indexReleasePid,
                             String documentPid,
                             String documentVersionPid,
                             String documentName,
                             String chunkPid,
                             int chunkIndex,
                             String path,
                             double vectorScore,
                             double lexicalScore,
                             double fusedScore,
                             double rerankScore,
                             String citationLocator,
                             List<String> warnings) {
        public RetrievalEvidence {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * Diagnostics for the retrieval that actually served a turn.
     *
     * <p>{@code path=keyword} is not a weaker generation result. It means the vector
     * provider was unavailable and the turn is configuration-invalid for generation
     * quality statistics. Callers must preserve this value through trace/observation.
     */
    record RetrievalDiagnostics(String path,
                                int resultCount,
                                List<RetrievalScore> scores,
                                List<String> warnings) {
        public RetrievalDiagnostics {
            path = path == null || path.isBlank() ? "none" : path;
            scores = scores == null ? List.of() : List.copyOf(scores);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** Rendered prompt context plus the retrieval evidence used to build it. */
    record RetrievedContext(String context,
                            RetrievalDiagnostics diagnostics,
                            List<RetrievalEvidence> evidence) {
        public RetrievedContext {
            context = context == null ? "" : context;
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        public RetrievedContext(String context, RetrievalDiagnostics diagnostics) {
            this(context, diagnostics, List.of());
        }
    }

    /**
     * Check if tenant has active knowledge bases with embedded content.
     */
    boolean hasActiveKnowledgeBases(Long tenantId);

    /**
     * Retrieve relevant context for a user query and format it for system prompt injection.
     *
     * @param tenantId  current tenant
     * @param query     user question
     * @param kbPids    specific KB PIDs (null = all active)
     * @return formatted Markdown context section, or empty string if no results
     */
    String retrieveContext(Long tenantId, String query, List<String> kbPids);

    /**
     * Diagnostic form used by trace/eval-aware callers.
     *
     * <p>The default keeps third-party/optional implementations source-compatible.
     * Such implementations still return their context, but report no diagnostic path
     * until they opt into this richer seam.
     */
    default RetrievedContext retrieveContextWithDiagnostics(Long tenantId,
                                                            String query,
                                                            List<String> kbPids) {
        return new RetrievedContext(retrieveContext(tenantId, query, kbPids), null);
    }
}
