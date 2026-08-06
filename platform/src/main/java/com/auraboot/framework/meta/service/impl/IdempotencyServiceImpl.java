package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.meta.entity.IdempotencyRecord;
import com.auraboot.framework.meta.exception.IdempotentException;
import com.auraboot.framework.meta.mapper.IdempotencyRecordMapper;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * IdempotencyService implementation.
 * Uses ab_idempotency_record table for check-and-record semantics.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {

    private final IdempotencyRecordMapper idempotencyRecordMapper;
    private final ObjectMapper objectMapper;

    private static final long DEFAULT_EXPIRY_SECONDS = 86400; // 24 hours
    private static final String STATUS_PROCESSING = "processing";

    @Override
    public Map<String, Object> checkIdempotency(String clientRequestId, Long tenantId) {
        if (clientRequestId == null || clientRequestId.isEmpty()) {
            return null;
        }

        IdempotencyRecord record = idempotencyRecordMapper.findByClientRequestId(tenantId, clientRequestId);
        if (record == null) {
            return null;
        }

        log.debug("Idempotent replay found for clientRequestId={}", clientRequestId);
        return parseJsonToMap(record.getOutcome());
    }

    @Override
    public Map<String, Object> checkScopedIdempotency(
            String clientRequestId,
            String operationCode,
            Map<String, Object> payload,
            Long tenantId) {
        if (clientRequestId == null || clientRequestId.isBlank()
                || operationCode == null || operationCode.isBlank()) {
            return null;
        }
        String normalizedOperation = operationCode.trim();
        String storageKey = scopedKey(normalizedOperation, clientRequestId);
        IdempotencyRecord record = idempotencyRecordMapper.findByClientRequestId(tenantId, storageKey);
        if (record == null) {
            return null;
        }
        String requestedHash = computeHash(payload);
        assertMatchingIntent(record, normalizedOperation, requestedHash);
        if (!StatusConstants.COMPLETED.equals(record.getStatus())) {
            throw new IdempotentException("An identical request is already being processed");
        }
        log.debug("Scoped idempotent replay found for operation={} clientRequestId={}",
                normalizedOperation, clientRequestId);
        return parseScopedOutcome(record.getOutcome());
    }

    @Override
    public Map<String, Object> claimScopedIdempotency(
            String clientRequestId,
            String operationCode,
            Map<String, Object> payload,
            Long tenantId) {
        if (clientRequestId == null || clientRequestId.isBlank()
                || operationCode == null || operationCode.isBlank()) {
            return null;
        }
        requireActiveTransaction();
        if (tenantId == null) {
            throw new IllegalStateException("Scoped idempotency requires a tenant");
        }

        String normalizedOperation = operationCode.trim();
        String storageKey = scopedKey(normalizedOperation, clientRequestId);
        String requestHash = computeHash(payload);

        // The unique constraint also covers expired rows. Remove only this expired key inside the
        // same transaction before claiming; concurrent reclaimers still serialize on the insert.
        idempotencyRecordMapper.deleteExpiredByClientRequestId(tenantId, storageKey);

        IdempotencyRecord claim = new IdempotencyRecord();
        claim.setTenantId(tenantId);
        claim.setClientRequestId(storageKey);
        claim.setRequestHash(requestHash);
        claim.setCommandCode(normalizedOperation);
        claim.setOutcome(null);
        claim.setStatus(STATUS_PROCESSING);
        claim.setExpiresAt(Instant.now().plusSeconds(DEFAULT_EXPIRY_SECONDS));
        claim.setCreatedAt(Instant.now());

        if (idempotencyRecordMapper.insertIdempotent(claim) > 0) {
            log.debug("Claimed scoped idempotency key for operation={} clientRequestId={}",
                    normalizedOperation, clientRequestId);
            return null;
        }

        // PostgreSQL's unique-index check waits for the owning transaction. At READ COMMITTED this
        // statement therefore sees either its committed COMPLETED row, or no row after rollback.
        IdempotencyRecord existing =
                idempotencyRecordMapper.findByClientRequestId(tenantId, storageKey);
        if (existing == null) {
            throw new IdempotentException(
                    "Idempotency request changed while being claimed; retry the request");
        }
        assertMatchingIntent(existing, normalizedOperation, requestHash);
        if (StatusConstants.COMPLETED.equals(existing.getStatus())) {
            log.debug("Scoped idempotent replay found after claim wait for operation={} clientRequestId={}",
                    normalizedOperation, clientRequestId);
            return parseScopedOutcome(existing.getOutcome());
        }
        throw new IdempotentException("An identical request is already being processed");
    }

    @Override
    public void recordOutcome(String clientRequestId, String commandCode,
                              Map<String, Object> payload, Map<String, Object> result,
                              Long tenantId) {
        if (clientRequestId == null || clientRequestId.isEmpty()) {
            return;
        }

        try {
            IdempotencyRecord record = new IdempotencyRecord();
            record.setTenantId(tenantId);
            record.setClientRequestId(clientRequestId);
            record.setRequestHash(computeHash(payload));
            record.setCommandCode(commandCode);
            record.setOutcome(objectMapper.writeValueAsString(result));
            record.setStatus(StatusConstants.COMPLETED);
            record.setExpiresAt(Instant.now().plusSeconds(DEFAULT_EXPIRY_SECONDS));
            record.setCreatedAt(Instant.now());

            idempotencyRecordMapper.insertIdempotent(record);
            log.debug("Recorded idempotency outcome for clientRequestId={}, command={}", clientRequestId, commandCode);
        } catch (Exception e) {
            log.warn("Failed to save idempotency record for clientRequestId={}: {}", clientRequestId, e.getMessage());
        }
    }

    @Override
    public void recordScopedOutcome(String clientRequestId, String operationCode,
                                    Map<String, Object> payload, Map<String, Object> result,
                                    Long tenantId) {
        if (clientRequestId == null || clientRequestId.isBlank()
                || operationCode == null || operationCode.isBlank()) {
            return;
        }
        requireActiveTransaction();
        if (tenantId == null) {
            throw new IllegalStateException("Scoped idempotency requires a tenant");
        }

        String normalizedOperation = operationCode.trim();
        String storageKey = scopedKey(normalizedOperation, clientRequestId);
        String requestHash = computeHash(payload);
        String outcome;
        try {
            outcome = objectMapper.writeValueAsString(result == null ? Map.of() : result);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize scoped idempotency outcome", e);
        }

        int completed = idempotencyRecordMapper.completeClaim(
                tenantId, storageKey, normalizedOperation, requestHash, outcome);
        if (completed != 1) {
            throw new IdempotentException(
                    "Scoped idempotency claim is missing or does not match the completed request");
        }
        log.debug("Completed scoped idempotency claim for operation={} clientRequestId={}",
                normalizedOperation, clientRequestId);
    }

    private static String scopedKey(String operationCode, String clientRequestId) {
        if (operationCode == null || operationCode.isBlank()
                || clientRequestId == null || clientRequestId.isBlank()) {
            return clientRequestId;
        }
        return operationCode.trim() + ":" + clientRequestId.trim();
    }

    /**
     * Scheduled via DatabaseSchedulerEngine (sys-idempotency-cleanup, interval 1h).
     */
    @Override
    public int cleanupExpired() {
        try {
            int deleted = idempotencyRecordMapper.deleteExpired();
            if (deleted > 0) {
                log.info("Cleaned up {} expired idempotency records", deleted);
            }
            return deleted;
        } catch (Exception e) {
            log.warn("Failed to cleanup expired idempotency records: {}", e.getMessage());
            return 0;
        }
    }

    // ==================== Private Helpers ====================

    private String computeHash(Map<String, Object> payload) {
        try {
            String json = objectMapper.writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(payload == null ? Map.of() : payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to canonicalize idempotency request", e);
        }
    }

    private void assertMatchingIntent(
            IdempotencyRecord record, String operationCode, String requestHash) {
        if (!operationCode.equals(record.getCommandCode())
                || !requestHash.equals(record.getRequestHash())) {
            throw new IdempotentException(
                    "Idempotency key was already used with a different request");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseScopedOutcome(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("Completed idempotency record has no outcome");
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse scoped idempotency outcome", e);
        }
    }

    private void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Scoped idempotency claim and completion require an active transaction");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonToMap(String json) {
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse idempotency outcome JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
