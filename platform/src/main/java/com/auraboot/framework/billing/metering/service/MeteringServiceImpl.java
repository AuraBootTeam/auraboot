package com.auraboot.framework.billing.metering.service;

import com.auraboot.framework.billing.catalog.spi.ResourceCatalogService;
import com.auraboot.framework.billing.metering.mapper.UsageEventMapper;
import com.auraboot.framework.billing.metering.model.DedupeStatus;
import com.auraboot.framework.billing.metering.model.RatingStatus;
import com.auraboot.framework.billing.metering.model.UsageDedupeConflict;
import com.auraboot.framework.billing.metering.model.UsageEvent;
import com.auraboot.framework.billing.metering.spi.MeteringResult;
import com.auraboot.framework.billing.metering.spi.MeteringService;
import com.auraboot.framework.billing.metering.spi.UsageEventRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * OSS base implementation of {@link MeteringService}.
 *
 * <h3>Supported features (OSS)</h3>
 * <ul>
 *   <li>Idempotent event recording with UNIQUE / DUPLICATE_IGNORED / CONFLICT outcomes.
 *   <li>DB UNIQUE constraint on {@code (source_service, idempotency_key)} as the
 *       concurrency hard-stop — concurrent duplicate inserts are caught and re-routed.
 *   <li>Payload equality check on resourceCode + quantity + occurredAt.
 *   <li>Conflict logging to {@code ab_billing_usage_dedupe_conflict} (P2-9).
 *   <li>Resource catalog validation via {@link ResourceCatalogService#isRegistered}.
 *   <li>Read-only queries: findByCode, listByAccount (capped at {@value #MAX_LIST_LIMIT}).
 * </ul>
 *
 * <h3>Dedup / concurrent insert design</h3>
 * <p>The insert is delegated to {@link MeteringInsertHelper#tryInsertEvent} which runs in
 * {@code REQUIRES_NEW}.  When the DB unique constraint fires (concurrent or repeat insert),
 * only the inner transaction rolls back — this outer transaction remains clean for the
 * re-query that classifies the outcome as DUPLICATE vs CONFLICT.
 *
 * <h3>TODO / Enterprise gaps</h3>
 * <ul>
 *   <li><b>P2-4</b>: HTTP write endpoint for external callers (OSS only exposes read).
 *   <li><b>M3</b>: Rating pipeline transitions (PENDING → RATED → BILLED | SKIPPED).
 *   <li><b>M3</b>: Real-time streaming to external ledger / analytics.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeteringServiceImpl implements MeteringService {

    /** Hard cap on listByAccount results to prevent unbounded scans. */
    public static final int MAX_LIST_LIMIT = 1000;

    private final ResourceCatalogService resourceCatalogService;
    private final UsageEventMapper       usageEventMapper;
    private final MeteringInsertHelper   insertHelper;
    private final ObjectMapper           objectMapper;

    // ─────────────────────────────────────────────────────────────────────────
    // record
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public MeteringResult record(UsageEventRequest request) {
        // 1. Schema validation — required fields
        MeteringResult validationError = validateRequired(request);
        if (validationError != null) {
            log.warn("[metering] record rejected — validation: {}", validationError.getReason());
            return validationError;
        }

        // 2. Resource catalog check
        if (!resourceCatalogService.isRegistered(request.getResourceCode())) {
            log.warn("[metering] record rejected — unregistered resource: {}", request.getResourceCode());
            return MeteringResult.rejected("RESOURCE_NOT_REGISTERED: " + request.getResourceCode());
        }

        // 3. Attempt insert in an isolated inner transaction.
        //    MeteringInsertHelper.tryInsertEvent() runs in REQUIRES_NEW so a
        //    unique-key violation rolls back only the inner TX — this outer TX
        //    remains usable for the re-query below.
        String eventCode = generateEventCode();
        UsageEvent event = buildEvent(request, eventCode);

        boolean inserted = insertHelper.tryInsertEvent(event);

        if (inserted) {
            log.info("[metering] ACCEPTED — eventCode={} accountId={} resource={} qty={}",
                    eventCode, request.getAccountId(), request.getResourceCode(), request.getQuantity());
            return MeteringResult.accepted(eventCode);
        }

        // 4. Insert failed — re-query to distinguish DUPLICATE vs CONFLICT
        return resolveConflict(request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // findByCode
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Optional<UsageEvent> findByCode(String eventCode) {
        return Optional.ofNullable(
                usageEventMapper.selectOne(
                        new LambdaQueryWrapper<UsageEvent>()
                                .eq(UsageEvent::getEventCode, eventCode)
                )
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // listByAccount
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<UsageEvent> listByAccount(Long accountId, String resourceCode, int limit) {
        int effectiveLimit = Math.min(Math.max(1, limit), MAX_LIST_LIMIT);
        return usageEventMapper.selectList(
                new LambdaQueryWrapper<UsageEvent>()
                        .eq(UsageEvent::getAccountId, accountId)
                        .eq(resourceCode != null, UsageEvent::getResourceCode, resourceCode)
                        .orderByDesc(UsageEvent::getOccurredAt)
                        .last("LIMIT " + effectiveLimit)
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Re-query the existing event after a failed insert and decide DUPLICATE vs CONFLICT.
     * Called from within the outer @Transactional context (which is still clean).
     */
    private MeteringResult resolveConflict(UsageEventRequest request) {
        // Read committed: use NOT_SUPPORTED helper to see the just-committed row
        UsageEvent existing = insertHelper.findExisting(
                request.getSourceService(), request.getIdempotencyKey());

        if (existing == null) {
            // Extremely rare race: inserted then immediately deleted.
            log.warn("[metering] conflict re-query returned null — race; source={} idem={}",
                    request.getSourceService(), request.getIdempotencyKey());
            return MeteringResult.rejected("TRANSIENT_CONFLICT — please retry");
        }

        if (isPayloadEqual(existing, request)) {
            log.info("[metering] DUPLICATE_IGNORED — eventCode={} accountId={} resource={}",
                    existing.getEventCode(), request.getAccountId(), request.getResourceCode());
            return MeteringResult.duplicateIgnored(existing.getEventCode());
        }

        // Payload differs → CONFLICT — log to conflict table, do NOT charge
        writeConflict(request, existing.getEventCode());
        log.warn("[metering] CONFLICT — existing={} accountId={} resource={} — payload mismatch, not charged",
                existing.getEventCode(), request.getAccountId(), request.getResourceCode());
        return MeteringResult.conflict(existing.getEventCode(),
                "CONFLICT: same idempotency key with different payload");
    }

    /**
     * Payload equality: two requests with the same (source_service, idempotency_key) are
     * considered equal if resourceCode, quantity (same scale comparison), and occurredAt match.
     */
    private boolean isPayloadEqual(UsageEvent existing, UsageEventRequest incoming) {
        return Objects.equals(existing.getResourceCode(), incoming.getResourceCode())
                && existing.getQuantity() != null
                && incoming.getQuantity() != null
                && existing.getQuantity().compareTo(incoming.getQuantity()) == 0
                && Objects.equals(existing.getOccurredAt(), incoming.getOccurredAt());
    }

    /**
     * Persist a conflict record to {@code ab_billing_usage_dedupe_conflict}.
     * The conflicting request payload is serialized to JSON for investigation.
     * Runs in the outer transaction (MANDATORY).
     */
    private void writeConflict(UsageEventRequest request, String existingEventCode) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            log.warn("[metering] failed to serialize conflict payload: {}", e.getMessage());
            payloadJson = request.toString();
        }

        UsageDedupeConflict conflict = UsageDedupeConflict.builder()
                .sourceService(request.getSourceService())
                .idempotencyKey(request.getIdempotencyKey())
                .existingEventCode(existingEventCode)
                .conflictingPayloadJson(payloadJson)
                .detectedAt(Instant.now())
                .build();

        insertHelper.insertConflict(conflict);
    }

    /** Build a {@link UsageEvent} entity from the validated request. */
    private UsageEvent buildEvent(UsageEventRequest req, String eventCode) {
        return UsageEvent.builder()
                .eventCode(eventCode)
                .idempotencyKey(req.getIdempotencyKey())
                .accountId(req.getAccountId())
                .workspaceId(req.getWorkspaceId())
                .userId(req.getUserId())
                .subscriptionId(req.getSubscriptionId())
                .resourceCode(req.getResourceCode())
                .resourceSubtype(req.getResourceSubtype())
                .quantity(req.getQuantity())
                .unit(req.getUnit())
                .occurredAt(req.getOccurredAt())
                .receivedAt(Instant.now())
                .sourceService(req.getSourceService())
                .sourceRef(req.getSourceRef())
                .ratingStatus(RatingStatus.PENDING.name())
                .dedupeStatus(DedupeStatus.UNIQUE.name())
                .metadataJson(req.getMetadataJson())
                .build();
    }

    /** Validate required fields. Returns a REJECTED result if any are missing/invalid, or null if valid. */
    private MeteringResult validateRequired(UsageEventRequest req) {
        if (req == null) {
            return MeteringResult.rejected("Request must not be null");
        }
        if (isBlank(req.getIdempotencyKey())) {
            return MeteringResult.rejected("idempotencyKey is required");
        }
        if (req.getAccountId() == null) {
            return MeteringResult.rejected("accountId is required");
        }
        if (isBlank(req.getResourceCode())) {
            return MeteringResult.rejected("resourceCode is required");
        }
        if (req.getQuantity() == null || req.getQuantity().signum() < 0) {
            return MeteringResult.rejected("quantity is required and must be >= 0");
        }
        if (isBlank(req.getUnit())) {
            return MeteringResult.rejected("unit is required");
        }
        if (req.getOccurredAt() == null) {
            return MeteringResult.rejected("occurredAt is required");
        }
        if (isBlank(req.getSourceService())) {
            return MeteringResult.rejected("sourceService is required");
        }
        return null;
    }

    private static String generateEventCode() {
        return "UE-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
