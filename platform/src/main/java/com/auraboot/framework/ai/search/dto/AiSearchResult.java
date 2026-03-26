package com.auraboot.framework.ai.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Result of an AI-powered natural language search.
 * Contains both the LLM's interpretation (parsedFilters, explanation)
 * and the actual query results.
 *
 * @author AuraBoot Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSearchResult {

    /** The model code the LLM resolved the query to */
    private String modelCode;

    /** Human-readable label for the model */
    private String modelLabel;

    /** Structured filters the LLM extracted from the natural language query */
    private List<ParsedFilter> parsedFilters;

    /** Search results */
    private List<Map<String, Object>> records;

    /** Total number of matching records */
    private long totalCount;

    /** LLM's natural-language explanation of how it interpreted the query */
    private String explanation;

    /** Whether LLM was used (false = keyword fallback) */
    private boolean llmUsed;

    /** Sort field applied (if any) */
    private String sortField;

    /** Sort direction applied (if any) */
    private String sortOrder;
}
