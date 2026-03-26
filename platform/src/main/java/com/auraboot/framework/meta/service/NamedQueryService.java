package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.*;

import java.util.List;
import java.util.Map;

/**
 * Named Query Service Interface
 * Provides CRUD, execution, field management, and validation for named queries.
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
public interface NamedQueryService {

    // ==================== CRUD ====================

    /**
     * Create a named query
     */
    NamedQueryDTO create(NamedQueryCreateRequest request);

    /**
     * Update a named query
     */
    NamedQueryDTO update(String pid, NamedQueryUpdateRequest request);

    /**
     * Delete a named query
     */
    void delete(String pid);

    /**
     * Find a named query by pid
     */
    NamedQueryDTO findByPid(String pid);

    /**
     * Find a named query by code
     */
    NamedQueryDTO findByCode(String code);

    // ==================== List queries ====================

    /**
     * Paginated list with conditions
     */
    PaginationResult<NamedQueryDTO> list(NamedQueryQueryRequest request);

    /**
     * Find all enabled queries for current tenant
     */
    List<NamedQueryDTO> findEnabled();

    // ==================== Status management ====================

    /**
     * Update query status
     */
    NamedQueryDTO updateStatus(String pid, String status);

    /**
     * Batch update query status
     */
    NamedQueryBatchResult batchUpdateStatus(NamedQueryBatchStatusRequest request);

    // ==================== Field management ====================

    /**
     * Get fields for a query
     */
    List<NamedQueryFieldDTO> getFields(String queryCode);

    /**
     * Add a field to a query
     */
    NamedQueryFieldDTO addField(String queryCode, NamedQueryFieldRequest request);

    /**
     * Update a field
     */
    NamedQueryFieldDTO updateField(String queryCode, String fieldCode, NamedQueryFieldRequest request);

    /**
     * Delete a field
     */
    void deleteField(String queryCode, String fieldCode);

    /**
     * Batch save fields
     */
    NamedQueryFieldBatchResult batchSaveFields(String queryCode, NamedQueryFieldBatchRequest request);

    /**
     * Mark all fields of a query as PLUGIN-sourced.
     * Called after plugin import creates a new NQ to properly tag fields.
     */
    void markFieldsAsPluginSource(String queryCode);

    // ==================== Execution and testing ====================

    /**
     * Test a query (preview with limited results)
     */
    NamedQueryTestResult testQuery(String pid, NamedQueryTestRequest request);

    /**
     * Execute a named query with pagination
     */
    PaginationResult<Map<String, Object>> executeQuery(String code, NamedQueryTestRequest request);

    // ==================== Export ====================

    /**
     * Export named query result data as Excel/CSV/JSON file
     */
    ExportResult exportData(String code, NamedQueryDataExportRequest request);

    // ==================== Validation ====================

    /**
     * Validate a query definition
     */
    NamedQueryValidationResult validate(NamedQueryValidationRequest request);

    // ==================== Version management ====================

    /**
     * Get version history for a query
     */
    List<NamedQueryVersionDTO> getVersions(String queryCode);

    /**
     * Get a specific version
     */
    NamedQueryVersionDTO getVersion(String queryCode, int versionNo);

    // ==================== FieldUsage integration ====================

    /**
     * Get query codes that reference a given field code
     */
    List<String> getQueryCodesByFieldCode(String fieldCode);

    /**
     * Count queries that reference a given field code
     */
    int countByFieldCode(String fieldCode);
}
