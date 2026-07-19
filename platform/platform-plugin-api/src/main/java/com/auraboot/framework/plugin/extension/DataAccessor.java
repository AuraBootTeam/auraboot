package com.auraboot.framework.plugin.extension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * Query records where a single field is in the provided value set.
     *
     * <p>The default implementation preserves binary/source compatibility for
     * existing plugin test doubles by delegating to {@link #query} once per
     * distinct non-null value. Runtime platform implementations should override
     * this method and issue a single dynamic query with an {@code IN} condition.
     *
     * @param modelCode the model code
     * @param fieldName field code or supported system field name
     * @param values    candidate values; duplicates and nulls are ignored
     * @return list of matching records
     */
    default List<Map<String, Object>> queryIn(String modelCode, String fieldName, Collection<?> values) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName cannot be null or blank");
        }
        List<Object> queryValues = distinctNonNullValues(values);
        if (queryValues.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (Object value : queryValues) {
            records.addAll(query(modelCode, Map.of(fieldName, value)));
        }
        return records;
    }

    private static List<Object> distinctNonNullValues(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Object> distinct = new LinkedHashSet<>();
        for (Object value : values) {
            if (value != null) {
                distinct.add(value);
            }
        }
        return List.copyOf(distinct);
    }

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
     * Bulk create — single-statement fast path: one multi-row INSERT in a single transaction,
     * skipping the per-row select-back / change-log / automation / SLA / virtual-field tail that
     * {@link #create}/{@link #batchCreate} run. Per-row validation, system-field enrichment,
     * primary-key generation and type conversion still run. Intended for mechanical bulk loads
     * (e.g. importing many BOM rows) where per-row side effects are neither needed nor wanted.
     *
     * <p>The default delegates to {@link #batchCreate} so existing implementors and test doubles
     * keep working unchanged; runtime implementations override it for the real fast path.
     *
     * @return created records with generated ids, in input order (enables caller-side id correlation)
     * @since 2.8.0
     */
    default List<Map<String, Object>> bulkCreate(String modelCode, List<Map<String, Object>> dataList) {
        return batchCreate(modelCode, dataList);
    }

    /**
     * Delete a record by ID.
     *
     * @param modelCode the model code
     * @param recordId  the record ID to delete
     */
    void delete(String modelCode, String recordId);

    /**
     * Atomically increment one numeric field, optionally bounded by another
     * numeric field. Runtime implementations execute one UPDATE ... RETURNING;
     * the default fails closed so plugins cannot silently fall back to a racy
     * read-modify-write sequence.
     *
     * @since 2.7.0
     */
    default Optional<Long> incrementWithinCap(String modelCode,
                                              String recordId,
                                              String counterCode,
                                              long delta,
                                              String capCode) {
        throw new UnsupportedOperationException("Atomic increment is not supported by this DataAccessor");
    }

    /** Atomically increment one numeric field without a cap. */
    default Optional<Long> increment(String modelCode,
                                     String recordId,
                                     String counterCode,
                                     long delta) {
        return incrementWithinCap(modelCode, recordId, counterCode, delta, null);
    }
}
