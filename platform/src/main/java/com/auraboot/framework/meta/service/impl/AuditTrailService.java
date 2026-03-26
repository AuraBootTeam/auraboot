package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.AuditChainVerificationResult;
import com.auraboot.framework.meta.dto.AuditComplianceReport;
import com.auraboot.framework.meta.dto.AuditTrailEvent;
import com.auraboot.framework.meta.entity.AuditTrail;
import com.auraboot.framework.meta.mapper.AuditTrailMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tamper-proof audit trail service with SHA-256 chain hashing.
 *
 * Each audit record includes a SHA-256 hash computed from its content fields
 * plus the hash of the previous record in the chain. This creates a
 * blockchain-like integrity chain per tenant — any tampering with historical
 * records will break the chain and be detectable via verifyChainIntegrity().
 *
 * Thread safety: concurrent writes are handled via the unique constraint on
 * (tenant_id, sequence_no) with retry on collision.
 *
 * @since 6.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditTrailService {

    private static final String GENESIS_HASH = "genesis";
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final AuditTrailMapper auditTrailMapper;

    /**
     * Record an audit trail entry with SHA-256 chain hashing.
     *
     * Retries on DuplicateKeyException (sequence_no collision from concurrent writes)
     * up to MAX_RETRY_ATTEMPTS times with exponential backoff.
     */
    public AuditTrail recordAudit(AuditTrailEvent event) {
        DuplicateKeyException lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                return doRecordAudit(event);
            } catch (DuplicateKeyException e) {
                lastException = e;
                log.warn("Audit trail sequence collision (attempt {}/{}), retrying...",
                        attempt, MAX_RETRY_ATTEMPTS);
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    try {
                        Thread.sleep(50L * (1L << (attempt - 1)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during audit trail retry", ie);
                    }
                }
            }
        }
        throw new RuntimeException("Failed to record audit trail after " +
                MAX_RETRY_ATTEMPTS + " attempts due to sequence collisions", lastException);
    }

    /**
     * Internal method that performs the actual audit record insertion.
     * Uses REQUIRES_NEW propagation so each retry attempt gets a fresh transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditTrail doRecordAudit(AuditTrailEvent event) {
        Long tenantId = event.getTenantId();

        // 1. Get current max sequence_no (atomic read under the new transaction)
        Long maxSeq = auditTrailMapper.getMaxSequenceNo(tenantId);
        long nextSeq = (maxSeq == null) ? 1L : maxSeq + 1L;

        // 2. Get previous record's hash (GENESIS for first record)
        String previousHash;
        if (nextSeq == 1L) {
            previousHash = GENESIS_HASH;
        } else {
            AuditTrail latest = auditTrailMapper.getLatestByTenant(tenantId);
            previousHash = (latest != null) ? latest.getRecordHash() : GENESIS_HASH;
        }

        // 3. Build the audit trail record
        Instant now = Instant.now();
        AuditTrail record = new AuditTrail();
        record.setTenantId(tenantId);
        record.setSequenceNo(nextSeq);
        record.setEventType(event.getEventType());
        record.setEntityType(event.getEntityType());
        record.setEntityId(event.getEntityId());
        record.setCommandCode(event.getCommandCode());
        record.setOperationType(event.getOperationType());
        record.setActorId(event.getActorId());
        record.setActorName(event.getActorName());
        record.setActorIp(event.getActorIp());
        record.setTimestamp(now);
        record.setBeforeSnapshot(event.getBeforeSnapshot());
        record.setAfterSnapshot(event.getAfterSnapshot());
        record.setChangedFields(event.getChangedFields());
        record.setMetadata(event.getMetadata());
        record.setPreviousHash(previousHash);

        // 4. Compute SHA-256 hash: canonical_string + previous_hash
        String canonicalString = buildCanonicalString(record);
        String recordHash = computeSha256(canonicalString + previousHash);
        record.setRecordHash(recordHash);

        // 5. Insert (unique constraint on tenant_id + sequence_no prevents duplicates)
        auditTrailMapper.insert(record);

        log.debug("Audit trail recorded: tenant={}, seq={}, type={}, entity={}/{}",
                tenantId, nextSeq, event.getEventType(),
                event.getEntityType(), event.getEntityId());

        return record;
    }

    /**
     * Verify the integrity of the hash chain for a tenant within a sequence range.
     * Recomputes each record's hash and verifies it matches the stored hash,
     * and that each record's previous_hash matches the prior record's record_hash.
     */
    public AuditChainVerificationResult verifyChainIntegrity(Long tenantId, Long fromSeq, Long toSeq) {
        List<AuditTrail> records = auditTrailMapper.getBySequenceRange(tenantId, fromSeq, toSeq);

        if (records.isEmpty()) {
            return AuditChainVerificationResult.ok(0);
        }

        // For the first record in range, get its expected previous hash
        String expectedPreviousHash;
        if (fromSeq == 1L) {
            expectedPreviousHash = GENESIS_HASH;
        } else {
            AuditTrail prevRecord = auditTrailMapper.getPreviousRecord(tenantId, fromSeq);
            expectedPreviousHash = (prevRecord != null) ? prevRecord.getRecordHash() : null;
        }

        long verified = 0;
        for (AuditTrail record : records) {
            // Verify previous_hash linkage
            if (expectedPreviousHash != null && !expectedPreviousHash.equals(record.getPreviousHash())) {
                return AuditChainVerificationResult.broken(
                        verified, record.getSequenceNo(),
                        expectedPreviousHash, record.getPreviousHash());
            }

            // Recompute hash and verify
            String canonicalString = buildCanonicalString(record);
            String expectedHash = computeSha256(canonicalString + record.getPreviousHash());

            if (!expectedHash.equals(record.getRecordHash())) {
                return AuditChainVerificationResult.broken(
                        verified, record.getSequenceNo(),
                        expectedHash, record.getRecordHash());
            }

            expectedPreviousHash = record.getRecordHash();
            verified++;
        }

        return AuditChainVerificationResult.ok(verified);
    }

    /**
     * Get the latest audit record for a tenant (used by controller for default toSeq).
     */
    public AuditTrail getLatestRecord(Long tenantId) {
        return auditTrailMapper.getLatestByTenant(tenantId);
    }

    /**
     * Get the full audit trail for a specific entity.
     */
    public List<AuditTrail> getAuditTrail(Long tenantId, String entityType, Long entityId) {
        return auditTrailMapper.getByEntity(tenantId, entityType, entityId);
    }

    /**
     * Get audit records for a specific actor within a time range.
     */
    public List<AuditTrail> getAuditByActor(Long tenantId, Long actorId,
                                              Instant startTime, Instant endTime) {
        return auditTrailMapper.getByActor(tenantId, actorId, startTime, endTime);
    }

    /**
     * Get audit records for a specific command.
     */
    public List<AuditTrail> getAuditByCommand(Long tenantId, String commandCode) {
        return auditTrailMapper.getByCommand(tenantId, commandCode);
    }

    /**
     * Generate a compliance report summarizing audit trail activity within a time window.
     * Includes chain integrity verification for the period.
     */
    public AuditComplianceReport generateComplianceReport(Long tenantId,
                                                           Instant startTime,
                                                           Instant endTime) {
        List<AuditTrail> records = auditTrailMapper.getByTimeRange(tenantId, startTime, endTime);

        // Compute breakdowns
        Map<String, Long> byEventType = records.stream()
                .filter(r -> r.getEventType() != null)
                .collect(Collectors.groupingBy(AuditTrail::getEventType, Collectors.counting()));

        Map<String, Long> byOperationType = records.stream()
                .filter(r -> r.getOperationType() != null)
                .collect(Collectors.groupingBy(AuditTrail::getOperationType, Collectors.counting()));

        long uniqueActors = records.stream()
                .map(AuditTrail::getActorId)
                .distinct()
                .count();

        long uniqueEntities = records.stream()
                .filter(r -> r.getEntityType() != null && r.getEntityId() != null)
                .map(r -> r.getEntityType() + ":" + r.getEntityId())
                .distinct()
                .count();

        // Verify chain integrity for the range
        AuditChainVerificationResult chainResult;
        if (!records.isEmpty()) {
            Long minSeq = records.stream().mapToLong(AuditTrail::getSequenceNo).min().orElse(1L);
            Long maxSeq = records.stream().mapToLong(AuditTrail::getSequenceNo).max().orElse(1L);
            chainResult = verifyChainIntegrity(tenantId, minSeq, maxSeq);
        } else {
            chainResult = AuditChainVerificationResult.ok(0);
        }

        return AuditComplianceReport.builder()
                .tenantId(tenantId)
                .startTime(startTime)
                .endTime(endTime)
                .totalRecords(records.size())
                .recordsByEventType(byEventType)
                .recordsByOperationType(byOperationType)
                .uniqueActors(uniqueActors)
                .uniqueEntities(uniqueEntities)
                .chainVerification(chainResult)
                .generatedAt(Instant.now())
                .build();
    }

    // ==================== Internal helpers ====================

    /**
     * Build a canonical string from an audit record's content fields.
     * The order and format are fixed to ensure deterministic hash computation.
     * Null values are represented as empty strings.
     */
    String buildCanonicalString(AuditTrail record) {
        StringBuilder sb = new StringBuilder();
        sb.append(nullSafe(record.getTenantId()));
        sb.append('|');
        sb.append(nullSafe(record.getSequenceNo()));
        sb.append('|');
        sb.append(nullSafe(record.getEventType()));
        sb.append('|');
        sb.append(nullSafe(record.getEntityType()));
        sb.append('|');
        sb.append(nullSafe(record.getEntityId()));
        sb.append('|');
        sb.append(nullSafe(record.getCommandCode()));
        sb.append('|');
        sb.append(nullSafe(record.getOperationType()));
        sb.append('|');
        sb.append(nullSafe(record.getActorId()));
        sb.append('|');
        sb.append(nullSafe(record.getActorIp()));
        sb.append('|');
        sb.append(nullSafe(record.getTimestamp()));
        sb.append('|');
        sb.append(jsonToString(record.getBeforeSnapshot()));
        sb.append('|');
        sb.append(jsonToString(record.getAfterSnapshot()));
        sb.append('|');
        sb.append(stringArrayToString(record.getChangedFields()));
        sb.append('|');
        sb.append(jsonToString(record.getMetadata()));
        return sb.toString();
    }

    /**
     * Compute SHA-256 hash of a string, returning lowercase hex.
     */
    String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all Java implementations
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private String nullSafe(Object value) {
        return value != null ? value.toString() : "";
    }

    private String jsonToString(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.toString();
    }

    private String stringArrayToString(String[] arr) {
        if (arr == null || arr.length == 0) {
            return "";
        }
        return String.join(",", arr);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
