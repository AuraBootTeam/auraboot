package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;

/**
 * Service interface for executing aggregate queries.
 * Used by dashboard chart components to fetch aggregated data.
 *
 * <p>Supports two query types:
 * <ul>
 *   <li>"aggregate" - Dynamic aggregation queries with metrics and dimensions</li>
 *   <li>"namedQuery" - Predefined queries stored in the database</li>
 * </ul>
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
public interface AggregateQueryService {

    /**
     * Execute an aggregate query and return the results.
     *
     * @param request the aggregate query request containing metrics, dimensions, and filters
     * @return the query response with aggregated data rows, summary, and metadata
     */
    AggregateQueryResponse execute(AggregateQueryRequest request);
}
