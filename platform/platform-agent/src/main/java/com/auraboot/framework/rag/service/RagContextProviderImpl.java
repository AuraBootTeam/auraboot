package com.auraboot.framework.rag.service;

import com.auraboot.framework.aurabot.service.RagContextProvider;
import com.auraboot.framework.rag.d7.D7CompiledKnowledgeMatch;
import com.auraboot.framework.rag.d7.D7CompiledKnowledgeService;
import com.auraboot.framework.rag.d7.D7ContextAssembler;
import com.auraboot.framework.rag.d7.D7KnowledgeProperties;
import com.auraboot.framework.rag.d7.D7RagFusion;
import com.auraboot.framework.rag.d7.D7RetrievalTraceWriter;
import com.auraboot.framework.rag.dto.RetrievalOutcome;
import com.auraboot.framework.rag.dto.RetrievalResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Core implementation of RagContextProvider.
 * Bridges AuraBot chat flows with RagRetrievalService inside the core AI runtime.
 */
@Component
@RequiredArgsConstructor
public class RagContextProviderImpl implements RagContextProvider {

    private final RagRetrievalService ragRetrievalService;
    private final D7CompiledKnowledgeService d7CompiledKnowledgeService;
    private final D7ContextAssembler d7ContextAssembler;
    private final D7RetrievalTraceWriter d7RetrievalTraceWriter;
    private final D7KnowledgeProperties d7KnowledgeProperties;

    @Override
    public boolean hasActiveKnowledgeBases(Long tenantId) {
        if (ragRetrievalService.hasActiveKnowledgeBases(tenantId)) {
            return true;
        }
        return d7KnowledgeProperties.isEnabled() && d7CompiledKnowledgeService.hasRetrievablePages(tenantId);
    }

    @Override
    public String retrieveContext(Long tenantId, String query, List<String> kbPids) {
        int maxTokens = d7KnowledgeProperties.getContextMaxTokens();
        if (!d7KnowledgeProperties.isEnabled()) {
            List<RetrievalResult> results = ragRetrievalService.retrieve(tenantId, query, kbPids, 5, null);
            // G6: trace the raw-only path too (writer no-ops unless tracing is enabled)
            d7RetrievalTraceWriter.recordRetrieval(tenantId, query, List.of(), results);
            // Same budgeted renderer, raw-only (G4)
            return d7ContextAssembler.buildFusedContext(
                    D7RagFusion.fuse(List.of(), results,
                            d7KnowledgeProperties.getRrfK(), d7KnowledgeProperties.getCompiledRrfWeight()),
                    maxTokens);
        }

        int rawTopK = d7KnowledgeProperties.getRawTopK() > 0 ? d7KnowledgeProperties.getRawTopK() : 5;
        int compiledTopK = d7KnowledgeProperties.getMaxCompiledPages() > 0
                ? d7KnowledgeProperties.getMaxCompiledPages()
                : 3;
        List<D7CompiledKnowledgeMatch> compiledMatches =
                d7CompiledKnowledgeService.retrieve(tenantId, query, compiledTopK);
        List<RetrievalResult> rawResults = ragRetrievalService.retrieve(tenantId, query, kbPids, rawTopK, null);
        d7RetrievalTraceWriter.recordRetrieval(tenantId, query, compiledMatches, rawResults);
        // G5 (DDR-A option A1): RRF fusion replaces "compiled always first"
        return d7ContextAssembler.buildFusedContext(
                D7RagFusion.fuse(compiledMatches, rawResults,
                        d7KnowledgeProperties.getRrfK(), d7KnowledgeProperties.getCompiledRrfWeight()),
                maxTokens);
    }

    /**
     * B-2 diagnostic retrieval seam. The rendered prompt context and the raw retrieval
     * outcome are produced by the same call, so trace/eval never infer a path by running
     * a second search that could return different rankings.
     */
    @Override
    public RetrievedContext retrieveContextWithDiagnostics(Long tenantId,
                                                           String query,
                                                           List<String> kbPids) {
        int maxTokens = d7KnowledgeProperties.getContextMaxTokens();
        if (!d7KnowledgeProperties.isEnabled()) {
            RetrievalOutcome outcome =
                    ragRetrievalService.retrieveWithDiagnostics(tenantId, query, kbPids, 5, null);
            List<RetrievalResult> rawResults = outcome.getResults();
            d7RetrievalTraceWriter.recordRetrieval(tenantId, query, List.of(), rawResults);
            String context = d7ContextAssembler.buildFusedContext(
                    D7RagFusion.fuse(List.of(), rawResults,
                            d7KnowledgeProperties.getRrfK(),
                            d7KnowledgeProperties.getCompiledRrfWeight()),
                    maxTokens);
            return new RetrievedContext(
                    context,
                    diagnostics(outcome),
                    evidence(outcome, rawResults, query));
        }

        int rawTopK = d7KnowledgeProperties.getRawTopK() > 0
                ? d7KnowledgeProperties.getRawTopK()
                : 5;
        int compiledTopK = d7KnowledgeProperties.getMaxCompiledPages() > 0
                ? d7KnowledgeProperties.getMaxCompiledPages()
                : 3;
        List<D7CompiledKnowledgeMatch> compiledMatches =
                d7CompiledKnowledgeService.retrieve(tenantId, query, compiledTopK);
        RetrievalOutcome outcome = ragRetrievalService.retrieveWithDiagnostics(
                tenantId, query, kbPids, rawTopK, null);
        List<RetrievalResult> rawResults = outcome.getResults();
        d7RetrievalTraceWriter.recordRetrieval(tenantId, query, compiledMatches, rawResults);
        String context = d7ContextAssembler.buildFusedContext(
                D7RagFusion.fuse(compiledMatches, rawResults,
                        d7KnowledgeProperties.getRrfK(),
                        d7KnowledgeProperties.getCompiledRrfWeight()),
                maxTokens);
        return new RetrievedContext(
                context,
                diagnostics(outcome, compiledMatches.size()),
                evidence(outcome, rawResults, query));
    }

    private static RetrievalDiagnostics diagnostics(RetrievalOutcome outcome) {
        return diagnostics(outcome, 0);
    }

    private static RetrievalDiagnostics diagnostics(RetrievalOutcome outcome,
                                                    int additionalResultCount) {
        List<RetrievalResult> results = outcome != null && outcome.getResults() != null
                ? outcome.getResults()
                : List.of();
        List<RetrievalScore> scores = results.stream()
                .map(result -> new RetrievalScore(
                        result.getChunkPid(),
                        result.getVectorScore(),
                        result.getBm25Score(),
                        result.getHybridScore(),
                        result.getSimilarity()))
                .toList();
        return new RetrievalDiagnostics(
                outcome != null ? outcome.getPath() : "none",
                results.size() + Math.max(additionalResultCount, 0),
                scores,
                outcome != null ? outcome.getWarnings() : List.of());
    }

    private static List<RetrievalEvidence> evidence(
            RetrievalOutcome outcome,
            List<RetrievalResult> results,
            String query) {
        String path = outcome != null ? outcome.getPath() : "none";
        List<String> warnings = outcome != null ? outcome.getWarnings() : List.of();
        return results.stream()
                .map(result -> new RetrievalEvidence(
                        "chunk:" + result.getChunkPid(),
                        query,
                        result.getKbPid(),
                        result.getKbName(),
                        result.getIndexReleasePid(),
                        result.getDocumentPid(),
                        result.getDocumentVersionPid(),
                        result.getDocName(),
                        result.getChunkPid(),
                        result.getChunkIndex(),
                        path,
                        result.getVectorScore(),
                        result.getBm25Score(),
                        result.getHybridScore(),
                        result.getRerankScore(),
                        result.getCitationLocator(),
                        warnings))
                .toList();
    }
}
