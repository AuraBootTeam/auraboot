package com.auraboot.framework.plugin.extension;

import java.util.List;
import java.util.Map;

/**
 * Data access interface for plugin command handlers.
 * Provides a sandboxed subset of DynamicDataService operations
 * that plugins can use to read and write dynamic entity data.
 *
 * All operations are tenant-scoped and run within the same
 * transactional boundary as the command execution.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
public interface DataAccessor {

    /**
     * Get a single record by ID.
     *
     * @param modelCode the model code (e.g., "pe_warehouse_in")
     * @param recordId  the record ID
     * @return record data map, or null if not found
     */
    Map<String, Object> getById(String modelCode, String recordId);

    /**
     * Query records with simple field-value equality filters.
     * Returns all matching records (no pagination).
     *
     * @param modelCode the model code
     * @param filters   field-value pairs for equality matching (e.g., {"pe_wh_in_line_receipt_id": "123"})
     * @return list of matching records
     */
    List<Map<String, Object>> query(String modelCode, Map<String, Object> filters);

    /**
     * Create a new record.
     *
     * @param modelCode the model code
     * @param data      the record data
     * @return the created record with generated ID
     */
    Map<String, Object> create(String modelCode, Map<String, Object> data);

    /**
     * Update an existing record.
     *
     * @param modelCode the model code
     * @param recordId  the record ID
     * @param data      the fields to update
     * @return the updated record
     */
    Map<String, Object> update(String modelCode, String recordId, Map<String, Object> data);

    /**
     * Batch create multiple records.
     *
     * @param modelCode the model code
     * @param dataList  list of record data
     * @return list of created records
     */
    List<Map<String, Object>> batchCreate(String modelCode, List<Map<String, Object>> dataList);

    /**
     * Delete a record by ID.
     *
     * @param modelCode the model code
     * @param recordId  the record ID to delete
     */
    void delete(String modelCode, String recordId);
}
