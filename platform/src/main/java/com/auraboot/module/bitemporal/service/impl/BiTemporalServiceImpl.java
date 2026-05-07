package com.auraboot.module.bitemporal.service.impl;

import com.auraboot.module.bitemporal.entity.BiTemporalRecord;
import com.auraboot.module.bitemporal.mapper.BiTemporalMapper;
import com.auraboot.module.bitemporal.service.BiTemporalService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Bi-temporal versioning service implementation.
 * <p>
 * Uses a "close-and-insert" pattern for corrections and terminations:
 * the old record's tx_to is set to now (closing its transaction period),
 * and a new record is inserted with the corrected/terminated state.
 *
 * @since 6.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BiTemporalServiceImpl implements BiTemporalService {

    private final BiTemporalMapper mapper;

    @Override
    @Transactional
    public BiTemporalRecord put(String entityType, String entityId,
                                LocalDateTime validFrom, LocalDateTime validTo,
                                JsonNode payload, Long userId) {
        // Determine version number
        BiTemporalRecord current = mapper.findCurrent(entityType, entityId);
        int nextVersion = (current != null) ? current.getVersionNo() + 1 : 1;

        // TODO: [timezone-unification] Change to Instant.now() once BiTemporalRecord entity fields
        //  (txFrom, createdAt, updatedAt) are migrated from LocalDateTime to Instant.
        Instant nowInstant = Instant.now();
        LocalDateTime now = LocalDateTime.ofInstant(nowInstant, java.time.ZoneOffset.UTC);

        BiTemporalRecord record = new BiTemporalRecord();
        record.setEntityType(entityType);
        record.setEntityId(entityId);
        record.setValidFrom(validFrom);
        record.setValidTo(validTo);
        record.setTxFrom(now);
        record.setTxTo(BiTemporalRecord.INFINITY);
        record.setPayload(payload);
        record.setCreatedBy(userId);
        record.setVersionNo(nextVersion);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);

        mapper.insert(record);
        log.debug("Inserted bi-temporal record: type={}, id={}, version={}", entityType, entityId, nextVersion);
        return record;
    }

    @Override
    public BiTemporalRecord getAsOf(String entityType, String entityId,
                                    LocalDateTime validTime, LocalDateTime txTime) {
        return mapper.findAsOf(entityType, entityId, validTime, txTime);
    }

    @Override
    public BiTemporalRecord getCurrent(String entityType, String entityId) {
        return mapper.findCurrent(entityType, entityId);
    }

    @Override
    public List<BiTemporalRecord> getHistory(String entityType, String entityId) {
        return mapper.findHistory(entityType, entityId);
    }

    @Override
    public List<BiTemporalRecord> getAllByTypeAsOf(String entityType, LocalDateTime validTime) {
        return mapper.findAllByTypeAsOf(entityType, validTime);
    }

    @Override
    @Transactional
    public BiTemporalRecord correct(String entityType, String entityId,
                                    LocalDateTime validFrom, LocalDateTime validTo,
                                    JsonNode payload, Long userId) {
        // Use FOR UPDATE so two concurrent corrections on the same anchor are
        // serialized — the second writer blocks until the first commits, then
        // re-reads the new current row before its own close-and-insert. Without
        // the lock both could close the same tx_to=INFINITY row and leave two
        // open versions (REVIEW-BE8-002).
        BiTemporalRecord current = mapper.findCurrentForUpdate(entityType, entityId);
        if (current == null) {
            throw new IllegalStateException(
                    "No current record found for correction: type=" + entityType + ", id=" + entityId);
        }

        // TODO: [timezone-unification] Change to Instant.now() once BiTemporalRecord entity fields are migrated.
        Instant nowInstant = Instant.now();
        LocalDateTime now = LocalDateTime.ofInstant(nowInstant, java.time.ZoneOffset.UTC);

        // Close the old transaction period
        mapper.closeTxPeriod(current.getId(), now);
        log.debug("Closed tx period for record id={}", current.getId());

        // Insert corrected record
        int nextVersion = current.getVersionNo() + 1;

        BiTemporalRecord corrected = new BiTemporalRecord();
        corrected.setEntityType(entityType);
        corrected.setEntityId(entityId);
        corrected.setValidFrom(validFrom);
        corrected.setValidTo(validTo);
        corrected.setTxFrom(now);
        corrected.setTxTo(BiTemporalRecord.INFINITY);
        corrected.setPayload(payload);
        corrected.setCreatedBy(userId);
        corrected.setVersionNo(nextVersion);
        corrected.setCreatedAt(now);
        corrected.setUpdatedAt(now);

        mapper.insert(corrected);
        log.debug("Inserted corrected bi-temporal record: type={}, id={}, version={}",
                entityType, entityId, nextVersion);
        return corrected;
    }

    @Override
    @Transactional
    public void terminate(String entityType, String entityId, LocalDateTime validTime) {
        // Use FOR UPDATE — see BiTemporalMapper#findCurrentForUpdate Javadoc
        // and the mirrored note in correct() above. Termination is a
        // close-and-insert just like correction and is subject to the same
        // race (REVIEW-BE8-002).
        BiTemporalRecord current = mapper.findCurrentForUpdate(entityType, entityId);
        if (current == null) {
            throw new IllegalStateException(
                    "No current record found for termination: type=" + entityType + ", id=" + entityId);
        }

        // TODO: [timezone-unification] Change to Instant.now() once BiTemporalRecord entity fields are migrated.
        Instant nowInstant = Instant.now();
        LocalDateTime now = LocalDateTime.ofInstant(nowInstant, java.time.ZoneOffset.UTC);

        // Close the old transaction period
        mapper.closeTxPeriod(current.getId(), now);

        // Insert terminated version (same valid_from, but valid_to = terminateTime)
        int nextVersion = current.getVersionNo() + 1;

        BiTemporalRecord terminated = new BiTemporalRecord();
        terminated.setEntityType(entityType);
        terminated.setEntityId(entityId);
        terminated.setValidFrom(current.getValidFrom());
        terminated.setValidTo(validTime);
        terminated.setTxFrom(now);
        terminated.setTxTo(BiTemporalRecord.INFINITY);
        terminated.setPayload(current.getPayload());
        terminated.setCreatedBy(current.getCreatedBy());
        terminated.setVersionNo(nextVersion);
        terminated.setCreatedAt(now);
        terminated.setUpdatedAt(now);

        mapper.insert(terminated);
        log.debug("Terminated bi-temporal record: type={}, id={}, validTo={}",
                entityType, entityId, validTime);
    }
}
