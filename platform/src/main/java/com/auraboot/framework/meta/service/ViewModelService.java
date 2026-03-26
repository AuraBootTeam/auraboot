package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.NamedQueryTestRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.ResolvedFieldDTO;
import com.auraboot.framework.meta.dto.ViewModelSummaryDTO;
import com.auraboot.framework.meta.dto.ViewModelValidationResult;

import java.util.List;
import java.util.Map;

/**
 * Service for ViewModel (VIEW type model) operations.
 * Handles three-layer field resolution and data query proxying.
 */
public interface ViewModelService {

    /**
     * Resolve all fields for a ViewModel through three-layer merging:
     * Layer 1: base fields (from entity binding or named query)
     * Layer 2: binding overrides (required, visible, editable, etc.)
     * Layer 3: computed field overrides (expressions, virtual fields)
     *
     * @param viewModelCode the ViewModel model code
     * @return list of resolved fields
     */
    List<ResolvedFieldDTO> resolveViewFields(String viewModelCode);

    /**
     * Execute a data query against the ViewModel's underlying data source.
     * Proxies to entity query (inherit mode) or NamedQuery execution (compose/free mode).
     *
     * @param viewModelCode the ViewModel model code
     * @param request       query parameters
     * @return paginated query results
     */
    PaginationResult<Map<String, Object>> queryViewData(String viewModelCode, NamedQueryTestRequest request);

    /**
     * Get a summary of the ViewModel configuration.
     *
     * @param viewModelCode the ViewModel model code
     * @return summary DTO
     */
    ViewModelSummaryDTO getSummary(String viewModelCode);

    /**
     * Validate the ViewModel configuration for completeness and correctness.
     *
     * @param viewModelCode the ViewModel model code
     * @return validation result with errors and warnings
     */
    ViewModelValidationResult validateConfig(String viewModelCode);

    /**
     * Evict all ViewModel caches.
     * Should be called when a VIEW model, its base entity bindings,
     * or related named query fields are modified.
     */
    void evictAllCache();
}
