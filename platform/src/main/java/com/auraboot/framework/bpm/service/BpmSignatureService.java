package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmSignatureRecord;
import com.auraboot.framework.bpm.mapper.BpmSignatureRecordMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.auraboot.framework.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * BPM signature service.
 * Uses HMAC-SHA256 for local digital signature generation and verification.
 * In production, can be extended to integrate with external providers (DocuSign, e-SignPro).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BpmSignatureService {

    private static final String DEFAULT_KEY_MARKER = "aura-bpm-default-signature-key";

    private final BpmSignatureRecordMapper signatureRecordMapper;
    private final ObjectMapper objectMapper;

    @Value("${bpm.signature.secret-key:" + DEFAULT_KEY_MARKER + "}")
    private String secretKey;

    @PostConstruct
    void validateSecretKey() {
        if (DEFAULT_KEY_MARKER.equals(secretKey) || secretKey == null || secretKey.length() < 32) {
            log.warn("BPM signature secret key is not configured or too short (min 32 chars). "
                    + "Set 'bpm.signature.secret-key' in application properties for production use.");
        }
    }

    /**
     * Create a signed record with HMAC-SHA256 digest.
     * The digest covers: documentId + processInstanceId + taskId + signerUserId + signedAt.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public BpmSignatureRecord sign(Map<String, Object> request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Instant signedAt = Instant.now();

        String documentId = (String) request.get("documentId");
        String processInstanceId = (String) request.get("processInstanceId");
        String taskId = (String) request.get("taskId");
        Long signerUserId = request.get("signerUserId") != null
                ? Long.parseLong(request.get("signerUserId").toString()) : null;
        String signatureType = (String) request.getOrDefault("signatureType", "digital");

        // Compute HMAC-SHA256 digest
        String payload = buildSignPayload(documentId, processInstanceId, taskId, signerUserId, signedAt);
        String digest = computeHmac(payload);

        BpmSignatureRecord record = BpmSignatureRecord.builder()
                .pid(UlidGenerator.generate())
                .tenantId(tenantId)
                .documentId(documentId)
                .processInstanceId(processInstanceId)
                .taskId(taskId)
                .signerUserId(signerUserId)
                .signatureType(signatureType)
                .signPosition((Map<String, Object>) request.get("signPosition"))
                .certificateSn(digest)
                .signedAt(signedAt)
                .createdAt(Instant.now())
                .build();

        signatureRecordMapper.insert(record);
        log.info("Signature record created: pid={}, document={}, type={}", record.getPid(), documentId, signatureType);
        return record;
    }

    /**
     * Verify all signatures on a document by re-computing HMAC digests.
     */
    public Map<String, Object> verify(String documentId) {
        List<BpmSignatureRecord> records = signatureRecordMapper.findByDocument(documentId);
        if (records.isEmpty()) {
            return Map.of("valid", false, "documentId", documentId, "signatureCount", 0, "message", "No signatures found");
        }

        List<Map<String, Object>> details = new ArrayList<>();
        boolean allValid = true;

        for (BpmSignatureRecord record : records) {
            String payload = buildSignPayload(
                    record.getDocumentId(), record.getProcessInstanceId(),
                    record.getTaskId(), record.getSignerUserId(), record.getSignedAt());
            String expectedDigest = computeHmac(payload);
            boolean valid = expectedDigest.equals(record.getCertificateSn());

            if (!valid) {
                allValid = false;
            }

            details.add(Map.of(
                    "pid", record.getPid(),
                    "signerUserId", record.getSignerUserId() != null ? record.getSignerUserId() : 0L,
                    "signedAt", record.getSignedAt() != null ? record.getSignedAt().toString() : "",
                    "valid", valid
            ));
        }

        return Map.of(
                "valid", allValid,
                "documentId", documentId,
                "signatureCount", records.size(),
                "signatures", details
        );
    }

    /**
     * Get signature records for a process instance.
     */
    public List<BpmSignatureRecord> getRecordsByProcess(String processInstanceId) {
        return signatureRecordMapper.findByProcessInstance(processInstanceId);
    }

    private String buildSignPayload(String documentId, String processInstanceId,
                                     String taskId, Long signerUserId, Instant signedAt) {
        // Use JSON serialization to avoid delimiter ambiguity (SIG-2 fix)
        try {
            Map<String, String> payloadMap = new LinkedHashMap<>();
            payloadMap.put("documentId", documentId != null ? documentId : "");
            payloadMap.put("processInstanceId", processInstanceId != null ? processInstanceId : "");
            payloadMap.put("taskId", taskId != null ? taskId : "");
            payloadMap.put("signerUserId", signerUserId != null ? signerUserId.toString() : "");
            payloadMap.put("signedAt", signedAt != null ? signedAt.toString() : "");
            return objectMapper.writeValueAsString(payloadMap);
        } catch (Exception e) {
            throw new BusinessException("Failed to build signature payload", e);
        }
    }

    private String computeHmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            log.error("Failed to compute HMAC signature", e);
            throw new BusinessException("Signature computation failed", e);
        }
    }
}
