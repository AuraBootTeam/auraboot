package com.auraboot.framework.rag.service;

import com.auraboot.framework.aurabot.service.RagContextProvider;
import com.auraboot.framework.rag.dto.RetrievalResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Enterprise implementation of RagContextProvider.
 * Bridges AuraBot (core) with RagRetrievalService (enterprise-ai).
 */
@Component
@RequiredArgsConstructor
public class RagContextProviderImpl implements RagContextProvider {

    private final RagRetrievalService ragRetrievalService;

    @Override
    public boolean hasActiveKnowledgeBases(Long tenantId) {
        return ragRetrievalService.hasActiveKnowledgeBases(tenantId);
    }

    @Override
    public String retrieveContext(Long tenantId, String query, List<String> kbPids) {
        List<RetrievalResult> results = ragRetrievalService.retrieve(tenantId, query, kbPids, 5, null);
        return ragRetrievalService.buildRagContext(results);
    }
}
