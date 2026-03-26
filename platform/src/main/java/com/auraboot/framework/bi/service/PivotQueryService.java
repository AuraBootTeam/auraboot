package com.auraboot.framework.bi.service;

import com.auraboot.framework.bi.dto.PivotQueryRequest;
import com.auraboot.framework.bi.dto.PivotQueryResponse;

/**
 * Service for executing pivot/cross-tabulation queries.
 * Builds dynamic SQL with GROUP BY and performs application-level pivoting.
 */
public interface PivotQueryService {

    /**
     * Execute a pivot query against a model's underlying table.
     *
     * @param request the pivot query parameters
     * @param tenantId current tenant ID
     * @return pivoted result with row/col headers and cells
     */
    PivotQueryResponse executePivot(PivotQueryRequest request, Long tenantId);
}
