package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.IdempotencyRecord;
import com.auraboot.framework.meta.mapper.IdempotencyRecordMapper;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.auraboot.framework.common.constant.StatusConstants;

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
            String json = objectMapper.writeValueAsString(payload);
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
            return UUID.randomUUID().toString();
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
