package com.auraboot.framework.rag.service;

import com.auraboot.framework.aurabot.service.RagContextProvider;
import com.auraboot.framework.rag.d7.D7CompiledKnowledgeMatch;
import com.auraboot.framework.rag.d7.D7CompiledKnowledgeService;
import com.auraboot.framework.rag.d7.D7ContextAssembler;
import com.auraboot.framework.rag.d7.D7KnowledgeProperties;
import com.auraboot.framework.rag.d7.D7RetrievalTraceWriter;
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
        if (!d7KnowledgeProperties.isEnabled()) {
            List<RetrievalResult> results = ragRetrievalService.retrieve(tenantId, query, kbPids, 5, null);
            return ragRetrievalService.buildRagContext(results);
        }

        int rawTopK = d7KnowledgeProperties.getRawTopK() > 0 ? d7KnowledgeProperties.getRawTopK() : 5;
        int compiledTopK = d7KnowledgeProperties.getMaxCompiledPages() > 0
                ? d7KnowledgeProperties.getMaxCompiledPages()
                : 3;
        List<D7CompiledKnowledgeMatch> compiledMatches =
                d7CompiledKnowledgeService.retrieve(tenantId, query, compiledTopK);
        List<RetrievalResult> rawResults = ragRetrievalService.retrieve(tenantId, query, kbPids, rawTopK, null);
        d7RetrievalTraceWriter.recordRetrieval(tenantId, query, compiledMatches, rawResults);
        String rawContext = ragRetrievalService.buildRagContext(rawResults);
        return d7ContextAssembler.buildAuraBotContext(compiledMatches, rawContext);
    }
}
