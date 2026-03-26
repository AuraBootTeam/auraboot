package com.auraboot.framework.ai.search.service;

import com.auraboot.framework.ai.search.dto.AiSearchRequest;
import com.auraboot.framework.ai.search.dto.AiSearchResult;

/**
 * AI-powered natural language search service.
 * <p>
 * Accepts a free-form text query, uses LLM to parse it into structured
 * filters (model + conditions + sort), then executes against DynamicDataService.
 * Falls back to cross-model keyword search when no LLM is configured.
 *
 * @author AuraBoot Team
 */
public interface AiSearchService {

    /**
     * Execute an AI-powered search.
     *
     * @param request the search request containing the natural language query
     * @return structured result with parsed interpretation and matching records
     */
    AiSearchResult search(AiSearchRequest request);
}
