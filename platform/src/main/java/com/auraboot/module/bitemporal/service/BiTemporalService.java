package com.auraboot.module.bitemporal.service;

import com.auraboot.module.bitemporal.entity.BiTemporalRecord;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Bi-temporal versioning service.
 * Manages entity versions across both valid time (business) and transaction time (system).
 *
 * @since 6.0.0
 */
public interface BiTemporalService {

    /**
     * Insert a new bi-temporal record.
     *
     * @param entityType the entity type (e.g. "bom", "price")
     * @param entityId   the entity identifier
     * @param validFrom  business-time start (inclusive)
     * @param validTo    business-time end (exclusive), use INFINITY for open-ended
     * @param payload    the entity state as JSON
     * @param userId     the user performing the operation
     * @return the created record
     */
    BiTemporalRecord put(String entityType, String entityId,
                         LocalDateTime validFrom, LocalDateTime validTo,
                         JsonNode payload, Long userId);

    /**
     * Point-in-time query: find the record valid at the given business and system times.
     */
    BiTemporalRecord getAsOf(String entityType, String entityId,
                             LocalDateTime validTime, LocalDateTime txTime);

    /**
     * Get the current version (valid now, latest transaction).
     */
    BiTemporalRecord getCurrent(String entityType, String entityId);

    /**
     * Get the full version history of an entity.
     */
    List<BiTemporalRecord> getHistory(String entityType, String entityId);

    /**
     * Correct a record: close the old transaction period and insert a new version.
     * This is a retroactive correction — the original record's tx_to is set to now,
     * and a new record with the corrected payload is inserted.
     *
     * @param entityType the entity type
     * @param entityId   the entity identifier
     * @param validFrom  the corrected business-time start
     * @param validTo    the corrected business-time end
     * @param payload    the corrected payload
     * @param userId     the user performing the correction
     * @return the new corrected record
     */
    BiTemporalRecord correct(String entityType, String entityId,
                             LocalDateTime validFrom, LocalDateTime validTo,
                             JsonNode payload, Long userId);

    /**
     * Find all current records of a given entity type at the specified valid time.
     * Useful for bulk queries like "all BOM_LINE versions effective on a given date".
     *
     * @param entityType the entity type (e.g. "bom_line")
     * @param validTime  the business time to query
     * @return list of matching records
     */
    List<BiTemporalRecord> getAllByTypeAsOf(String entityType, LocalDateTime validTime);

    /**
     * Terminate an entity at the given business time by setting valid_to on the current record.
     *
     * @param entityType the entity type
     * @param entityId   the entity identifier
     * @param validTime  the business time at which the entity is terminated
     */
    void terminate(String entityType, String entityId, LocalDateTime validTime);
}
