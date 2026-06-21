package com.auraboot.framework.plugin.extension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tenant-scoped dynamic-data access for plugin background components.
 *
 * <p>Background components contributed via {@link BackgroundComponentExtension}
 * run outside any request/command context — there is no implicit tenant on the
 * thread when a {@code @KafkaListener} fires or a {@code @Scheduled} method
 * runs. This accessor takes an explicit {@code tenantId} per call and pushes
 * it onto the platform's tenant context for the duration of the operation.
 *
 * <p>For request-scoped command handlers, prefer {@link DataAccessor} which
 * is injected with the command's tenant already bound to the thread.
 *
 * <p><b>Idempotency:</b> {@link #tryCreate} returns {@code Optional.empty()}
 * on unique-constraint violation, letting at-least-once message consumers
 * (Kafka, retries) re-process the same logical record without throwing.
 *
 * <p><b>Transactions:</b> Each method runs in its own transaction. Callers
 * needing atomic multi-operation work should compose at a higher layer
 * (e.g. wrap their @KafkaListener method with @Transactional).
 *
 * @since 2.5.0
 */
public interface BackgroundDataAccessor {

    /**
     * Insert a row.
     *
     * @param tenantId  the tenant to write under
     * @param modelCode the model code (e.g. {@code "cr_crawl_url"})
     * @param data      column values keyed by field code (not column name)
     * @return the created record with system fields enriched
     */
    Map<String, Object> create(long tenantId, String modelCode, Map<String, Object> data);

    /**
     * Insert idempotently. Returns the created record on success, or
     * {@link Optional#empty()} if a unique constraint blocks the insert
     * (typically the row already exists under the same business key). Caller
     * decides whether to query the existing row, update it, or skip.
     *
     * <p>Other errors (validation, missing model, DB connection) still throw.
     *
     * @param tenantId  the tenant to write under
     * @param modelCode the model code
     * @param data      column values
     * @return populated optional on insert, empty on unique violation
     */
    Optional<Map<String, Object>> tryCreate(long tenantId, String modelCode, Map<String, Object> data);

    /**
     * Read a single record by primary key.
     *
     * @return the record, or {@code null} if not found
     */
    Map<String, Object> getById(long tenantId, String modelCode, String recordId);

    /**
     * Query by exact-match field filters. No pagination — caller is expected
     * to constrain by selective fields. Returns empty list if no matches.
     *
     * @param filters field-code &rarr; value, all ANDed equality
     */
    List<Map<String, Object>> query(long tenantId, String modelCode, Map<String, Object> filters);

    /** Update fields of an existing record. */
    Map<String, Object> update(long tenantId, String modelCode, String recordId, Map<String, Object> data);

    /** Delete a record by primary key. */
    void delete(long tenantId, String modelCode, String recordId);

    /**
     * Atomically increment a numeric counter column on the named model, optionally bounded
     * by a cap column. The operation runs under the given tenant with no implicit user context;
     * a synthetic system user-id is used for audit columns (changed_by).
     *
     * @param tenantId    the tenant to operate under
     * @param modelCode   model containing the counter
     * @param recordId    primary key value of the target row
     * @param counterCode field code of the column to increment (must be numeric)
     * @param capCode     field code of the cap column, or {@code null} for unbounded
     * @param delta       increment amount (positive)
     * @return the new counter value, or {@code -1} if the row was not found or already at cap
     * @throws IllegalArgumentException if {@code counterCode} or {@code capCode} is unknown
     *                                  or non-numeric on {@code modelCode}
     * @since 2.6.0
     */
    long incrementWithinCap(long tenantId, String modelCode, String recordId,
                             String counterCode, String capCode, long delta);

    /**
     * Atomically increment a numeric counter column with no cap (unbounded).
     *
     * @since 2.6.0
     */
    default long increment(long tenantId, String modelCode, String recordId, String counterCode, long delta) {
        return incrementWithinCap(tenantId, modelCode, recordId, counterCode, null, delta);
    }
}
