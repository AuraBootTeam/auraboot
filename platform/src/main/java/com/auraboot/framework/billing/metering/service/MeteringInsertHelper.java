package com.auraboot.framework.billing.metering.service;

import com.auraboot.framework.billing.metering.mapper.UsageDedupeConflictMapper;
import com.auraboot.framework.billing.metering.mapper.UsageEventMapper;
import com.auraboot.framework.billing.metering.model.UsageDedupeConflict;
import com.auraboot.framework.billing.metering.model.UsageEvent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inner-transaction helper for usage event inserts.
 *
 * <p>Runs in a dedicated {@link Propagation#REQUIRES_NEW} transaction so that a
 * unique-key violation from the DB (concurrent duplicate insert) rolls back ONLY
 * this inner transaction — leaving the caller's outer transaction clean for the
 * subsequent re-query to distinguish DUPLICATE vs CONFLICT.
 *
 * <p>This is the correct use of {@code REQUIRES_NEW}: the outer transaction cannot
 * be used for the insert because a PG constraint violation marks it aborted;
 * isolating the insert in its own transaction keeps the outer one usable for reads.
 *
 * <h3>Why not ON CONFLICT DO NOTHING?</h3>
 * <p>MyBatis-Plus {@code BaseMapper.insert()} does not support {@code ON CONFLICT}
 * syntax in a database-agnostic way.  A custom XML mapper could be added in a
 * future iteration, but the savepoint / REQUIRES_NEW approach is simpler and safe
 * for the metering insert volume (events are not a hot path per-row).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeteringInsertHelper {

    private final UsageEventMapper          usageEventMapper;
    private final UsageDedupeConflictMapper conflictMapper;

    /**
     * Attempt to insert a usage event in an isolated inner transaction.
     *
     * @return {@code true} if the row was inserted; {@code false} if a unique
     *         constraint violation occurred (duplicate or concurrent insert)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryInsertEvent(UsageEvent event) {
        try {
            usageEventMapper.insert(event);
            return true;
        } catch (DataIntegrityViolationException ex) {
            // Unique constraint on (source_service, idempotency_key) — expected for duplicates.
            // Roll back this inner transaction; caller's outer transaction remains usable.
            log.debug("[metering] inner insert failed (unique constraint) for idem={} source={}",
                    event.getIdempotencyKey(), event.getSourceService());
            return false;
        }
    }

    /**
     * Load the existing usage event by (source_service, idempotency_key).
     * Called after a failed insert to determine DUPLICATE vs CONFLICT.
     * Runs in {@code NOT_SUPPORTED} to avoid inheriting the outer transaction's
     * read state (defensive; outer is already clean at this point).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UsageEvent findExisting(String sourceService, String idempotencyKey) {
        return usageEventMapper.selectOne(
                new LambdaQueryWrapper<UsageEvent>()
                        .eq(UsageEvent::getSourceService, sourceService)
                        .eq(UsageEvent::getIdempotencyKey, idempotencyKey)
        );
    }

    /**
     * Insert a conflict record in the caller's transaction.
     * Caller must ensure this is called within an active transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertConflict(UsageDedupeConflict conflict) {
        conflictMapper.insert(conflict);
    }
}
