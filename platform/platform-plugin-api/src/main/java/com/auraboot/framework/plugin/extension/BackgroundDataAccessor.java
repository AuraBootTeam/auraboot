package com.auraboot.framework.plugin.extension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
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
 * <p><b>Transactions:</b> Each method participates in an existing host transaction when one is
 * active. Background plugins that need a durable multi-model unit must use
 * {@link #executeInTransaction(Runnable)}; the default fails closed rather than silently exposing
 * a write-to-outbox crash window.
 *
 * @since 2.5.0
 */
public interface BackgroundDataAccessor {

    /** Hard ceiling for one database-backed background page or claim. */
    int MAX_BOUNDED_BATCH_SIZE = 1_000;

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

    /**
     * Query one keyset page ordered by the model's stable public {@code pid}.
     *
     * <p>The predicate, cursor, ordering and limit must all be executed by the database. A host
     * implementation must not call {@link #query} and truncate the returned list in memory. The
     * default fails closed so older plugin test doubles remain source-compatible without silently
     * claiming production-grade scan semantics.</p>
     *
     * @param afterRecordPid exclusive public-record cursor, or {@code null} for the first page
     * @since 2.9.0
     */
    default BoundedPage queryPage(long tenantId,
                                  String modelCode,
                                  Map<String, Object> exactFilters,
                                  String afterRecordPid,
                                  int limit) {
        throw new UnsupportedOperationException(
                "BackgroundDataAccessor bounded-page capability is unavailable");
    }

    /**
     * Atomically claim a bounded set of rows for leased background work.
     *
     * <p>Production implementations must select eligible rows, lock them without waiting on rows
     * claimed by another worker, apply {@link BatchClaimRequest#claimValues()}, and return the
     * claimed rows in one database transaction. The contract is deliberately generic: queue and
     * outbox plugins provide model field codes, while the host resolves those codes through model
     * metadata and owns the SQL.</p>
     *
     * @since 2.9.0
     */
    default List<Map<String, Object>> claimBatch(long tenantId, BatchClaimRequest request) {
        throw new UnsupportedOperationException(
                "BackgroundDataAccessor atomic-claim capability is unavailable");
    }

    /**
     * Opaque, process-local identity of the physical transaction resource used by this accessor.
     * Accessors participating in one atomic unit must report the same non-blank identity.
     *
     * @since 2.9.0
     */
    default String transactionResourceId() {
        throw new UnsupportedOperationException(
                "BackgroundDataAccessor transaction-resource identity is unavailable");
    }

    /**
     * Execute all accessor calls made by {@code work} in one host-owned transaction.
     *
     * <p>The transaction manager behind this capability must own the same physical resource
     * reported by {@link #transactionResourceId()}. Implementations must roll back the whole unit
     * when {@code work} throws. The default deliberately fails closed.</p>
     *
     * @since 2.9.0
     */
    default void executeInTransaction(Runnable work) {
        throw new UnsupportedOperationException(
                "BackgroundDataAccessor transaction-execution capability is unavailable");
    }

    /**
     * Query records whose field value belongs to a tenant-scoped candidate set.
     * Runtime implementations should issue one {@code IN} query; the default keeps
     * source compatibility for lightweight plugin test doubles.
     */
    default List<Map<String, Object>> queryIn(long tenantId, String modelCode,
                                               String fieldName, Collection<?> values) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName cannot be null or blank");
        }
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<Object> distinct = new LinkedHashSet<>();
        values.stream().filter(java.util.Objects::nonNull).forEach(distinct::add);
        if (distinct.isEmpty()) return List.of();
        List<Map<String, Object>> records = new ArrayList<>();
        for (Object value : distinct) {
            records.addAll(query(tenantId, modelCode, Map.of(fieldName, value)));
        }
        return records;
    }

    /** Update fields of an existing record. */
    Map<String, Object> update(long tenantId, String modelCode, String recordId, Map<String, Object> data);

    /**
     * Atomically replace one field only when its stored value still equals {@code expectedValue}.
     * The tenant, record identity, data scope, and expected value must be guarded by one write.
     *
     * @since 2.8.0
     */
    boolean compareAndSet(long tenantId,
                          String modelCode,
                          String recordId,
                          String fieldCode,
                          Object expectedValue,
                          Object nextValue);

    boolean compareAndSet(long tenantId,
                          String modelCode,
                          String recordId,
                          String fieldCode,
                          Object expectedValue,
                          Map<String, Object> nextValues);

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
     * @param delta       increment amount (positive)
     * @param capCode     field code of the cap column, or {@code null} for unbounded
     * @return the new counter value wrapped in {@link Optional}, or {@link Optional#empty()}
     *         if the row was not found or was already at cap
     * @throws IllegalArgumentException if {@code counterCode} or {@code capCode} is unknown
     *                                  or non-numeric on {@code modelCode}
     * @since 2.6.0
     */
    Optional<Long> incrementWithinCap(long tenantId, String modelCode, String recordId,
                                      String counterCode, long delta, String capCode);

    /**
     * Atomically increment a numeric counter column with no cap (unbounded).
     *
     * @since 2.6.0
     */
    default Optional<Long> increment(long tenantId, String modelCode, String recordId,
                                     String counterCode, long delta) {
        return incrementWithinCap(tenantId, modelCode, recordId, counterCode, delta, null);
    }

    /** Immutable result of a database-backed keyset page. */
    record BoundedPage(List<Map<String, Object>> records, String nextCursor) {
        public BoundedPage {
            records = records == null ? List.of() : List.copyOf(records);
            if (records.size() > MAX_BOUNDED_BATCH_SIZE) {
                throw new IllegalArgumentException("bounded page exceeds hard maximum");
            }
        }
    }

    /**
     * Generic scalar-field lease claim.
     *
     * <p>{@code exactFilters} are equality predicates, {@code inFilters} are SQL {@code IN}
     * predicates, and {@code notAfterFilters} are inclusive upper bounds ({@code <=}). Every map
     * key and order field is a model field code, never a physical column supplied by a plugin.
     * JSON/array claim mutations are intentionally outside this mechanical-control-plane API.</p>
     */
    record BatchClaimRequest(
            String modelCode,
            Map<String, Object> exactFilters,
            Map<String, List<Object>> inFilters,
            Map<String, Object> notAfterFilters,
            Map<String, Object> claimValues,
            List<String> orderByFields,
            int limit) {

        public BatchClaimRequest {
            if (modelCode == null || modelCode.isBlank()) {
                throw new IllegalArgumentException("modelCode cannot be null or blank");
            }
            if (limit <= 0 || limit > MAX_BOUNDED_BATCH_SIZE) {
                throw new IllegalArgumentException(
                        "limit must be between 1 and " + MAX_BOUNDED_BATCH_SIZE);
            }
            exactFilters = immutableMap(exactFilters);
            notAfterFilters = immutableMap(notAfterFilters);
            claimValues = immutableMap(claimValues);
            if (claimValues.isEmpty()) {
                throw new IllegalArgumentException("claimValues cannot be empty");
            }
            if (claimValues.values().stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("claimValues cannot contain null values");
            }
            if (exactFilters.values().stream().anyMatch(java.util.Objects::isNull)
                    || notAfterFilters.values().stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("claim filters cannot contain null values");
            }
            Map<String, List<Object>> copiedIn = new java.util.LinkedHashMap<>();
            if (inFilters != null) {
                inFilters.forEach((field, values) -> {
                    if (field == null || field.isBlank() || values == null || values.isEmpty()
                            || values.stream().anyMatch(java.util.Objects::isNull)) {
                        throw new IllegalArgumentException(
                                "IN filters require a field and non-null candidate values");
                    }
                    copiedIn.put(field, List.copyOf(values));
                });
            }
            inFilters = Map.copyOf(copiedIn);
            orderByFields = orderByFields == null ? List.of() : List.copyOf(orderByFields);
            if (orderByFields.stream().anyMatch(
                    field -> field == null || field.isBlank())) {
                throw new IllegalArgumentException("orderByFields cannot contain blanks");
            }
        }

        private static Map<String, Object> immutableMap(Map<String, Object> source) {
            if (source == null || source.isEmpty()) return Map.of();
            java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
            source.forEach((field, value) -> {
                if (field == null || field.isBlank()) {
                    throw new IllegalArgumentException("claim field codes cannot be blank");
                }
                copy.put(field, value);
            });
            return java.util.Collections.unmodifiableMap(copy);
        }
    }
}
