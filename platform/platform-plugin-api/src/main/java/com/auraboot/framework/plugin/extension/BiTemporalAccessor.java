package com.auraboot.framework.plugin.extension;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Bi-temporal data access interface for plugin command handlers.
 * Provides versioning operations for entities that need both business-time
 * and transaction-time tracking (e.g., BOM versions, supplier prices).
 *
 * All operations are tenant-scoped and run within the same
 * transactional boundary as the command execution.
 *
 * @author AuraBoot Team
 * @since 6.0.0
 */
public interface BiTemporalAccessor {

    /** Sentinel value for open-ended valid_to / tx_to. */
    LocalDateTime INFINITY = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    /**
     * Insert a new bi-temporal version for an entity.
     *
     * @param entityType the entity type (e.g. "bom_line", "supplier_price")
     * @param entityId   the entity identifier (usually the record's PID)
     * @param validFrom  business-time start (inclusive)
     * @param validTo    business-time end (exclusive), use INFINITY for open-ended
     * @param payload    the entity state as a map (will be stored as JSONB)
     * @param userId     the user performing the operation
     * @return the created version info: { id, entityType, entityId, versionNo }
     */
    Map<String, Object> put(String entityType, String entityId,
                            LocalDateTime validFrom, LocalDateTime validTo,
                            Map<String, Object> payload, Long userId);

    /**
     * Point-in-time query: find the record valid at the given business and system times.
     *
     * @param entityType the entity type
     * @param entityId   the entity identifier
     * @param validTime  the business time to query
     * @param txTime     the system/transaction time to query
     * @return the matching version payload, or null if not found
     */
    Map<String, Object> getAsOf(String entityType, String entityId,
                                LocalDateTime validTime, LocalDateTime txTime);

    /**
     * Get the current version (valid now, latest transaction).
     *
     * @param entityType the entity type
     * @param entityId   the entity identifier
     * @return the current version payload, or null if not found
     */
    Map<String, Object> getCurrent(String entityType, String entityId);

    /**
     * Correct a record: close the old transaction period and insert a corrected version.
     * This is a retroactive correction - the original record's tx_to is set to now,
     * and a new record with the corrected payload is inserted.
     *
     * @param entityType the entity type
     * @param entityId   the entity identifier
     * @param validFrom  the corrected business-time start
     * @param validTo    the corrected business-time end
     * @param payload    the corrected payload as a map
     * @param userId     the user performing the correction
     * @return the new corrected version info
     * @throws RuntimeException if no current record exists for correction
     */
    Map<String, Object> correct(String entityType, String entityId,
                                LocalDateTime validFrom, LocalDateTime validTo,
                                Map<String, Object> payload, Long userId);
}
