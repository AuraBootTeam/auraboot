package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.entity.BpmSignatureRecord;
import com.auraboot.framework.bpm.service.BpmSignatureService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for BpmSignatureService covering HMAC-SHA256 signing,
 * verification, signature type persistence, JSONB position data, and
 * multi-signature / process-instance queries.
 */
@Slf4j
@DisplayName("BPM Signature Service Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BpmSignatureServiceTest extends BaseIntegrationTest {

    @Autowired
    private BpmSignatureService signatureService;

    // ==================== Helper Methods ====================

    private Map<String, Object> buildSignRequest(String documentId, String processInstanceId,
                                                  String taskId, Long signerUserId,
                                                  String signatureType, Map<String, Object> signPosition) {
        Map<String, Object> request = new HashMap<>();
        request.put("documentId", documentId);
        request.put("processInstanceId", processInstanceId);
        request.put("taskId", taskId);
        request.put("signerUserId", signerUserId);
        if (signatureType != null) {
            request.put("signatureType", signatureType);
        }
        if (signPosition != null) {
            request.put("signPosition", signPosition);
        }
        return request;
    }

    // ==================== Test Cases ====================

    @Test
    @Order(1)
    @DisplayName("SIG-01: sign creates record with HMAC-SHA256 digest")
    void sig01_signCreatesRecordWithDigest() {
        Map<String, Object> request = buildSignRequest(
                "doc-sig01", "proc-sig01", "task-sig01", getTestUser().getId(), null, null);

        BpmSignatureRecord record = signatureService.sign(request);

        assertNotNull(record, "Signed record should not be null");
        assertNotNull(record.getPid(), "PID should be auto-generated");
        assertNotNull(record.getCertificateSn(), "certificateSn (HMAC digest) should not be null");
        assertFalse(record.getCertificateSn().isEmpty(), "certificateSn should not be empty");
        assertEquals("doc-sig01", record.getDocumentId());
        assertNotNull(record.getSignedAt(), "signedAt should be auto-set");

        log.info("SIG-01 PASSED: sign creates record with digest, certificateSn={}", record.getCertificateSn());
    }

    @Test
    @Order(2)
    @DisplayName("SIG-02: verify valid signature returns valid=true")
    void sig02_verifyValidSignature() {
        String documentId = "doc-sig02-" + System.nanoTime();
        Map<String, Object> request = buildSignRequest(
                documentId, "proc-sig02", "task-sig02", getTestUser().getId(), null, null);

        signatureService.sign(request);

        Map<String, Object> result = signatureService.verify(documentId);

        assertEquals(true, result.get("valid"), "Verification should return valid=true");
        assertEquals(documentId, result.get("documentId"));
        assertEquals(1, result.get("signatureCount"));

        log.info("SIG-02 PASSED: verify valid signature returns valid=true");
    }

    @Test
    @Order(3)
    @DisplayName("SIG-03: verify unknown document returns valid=false, signatureCount=0")
    void sig03_verifyNoSignatures() {
        String unknownDocId = "doc-nonexistent-" + System.nanoTime();

        Map<String, Object> result = signatureService.verify(unknownDocId);

        assertEquals(false, result.get("valid"), "No signatures should return valid=false");
        assertEquals(0, result.get("signatureCount"), "signatureCount should be 0");

        log.info("SIG-03 PASSED: verify unknown document returns valid=false, signatureCount=0");
    }

    @Test
    @Order(4)
    @DisplayName("SIG-04: DIGITAL signature type persists in record")
    void sig04_digitalTypePersists() {
        String documentId = "doc-sig04-" + System.nanoTime();
        Map<String, Object> request = buildSignRequest(
                documentId, "proc-sig04", "task-sig04", getTestUser().getId(), "digital", null);

        BpmSignatureRecord record = signatureService.sign(request);

        assertEquals("digital", record.getSignatureType(), "signatureType should be DIGITAL");

        log.info("SIG-04 PASSED: DIGITAL type persists in record");
    }

    @Test
    @Order(5)
    @DisplayName("SIG-05: HANDWRITTEN signature type persists in record")
    void sig05_handwrittenTypePersists() {
        String documentId = "doc-sig05-" + System.nanoTime();
        Map<String, Object> request = buildSignRequest(
                documentId, "proc-sig05", "task-sig05", getTestUser().getId(), "handwritten", null);

        BpmSignatureRecord record = signatureService.sign(request);

        assertEquals("handwritten", record.getSignatureType(), "signatureType should be HANDWRITTEN");

        log.info("SIG-05 PASSED: HANDWRITTEN type persists in record");
    }

    @Test
    @Order(6)
    @DisplayName("SIG-06: signPosition JSONB data persists correctly")
    void sig06_positionDataJsonb() {
        String documentId = "doc-sig06-" + System.nanoTime();
        Map<String, Object> position = Map.of("x", 100, "y", 200, "page", 1);
        Map<String, Object> request = buildSignRequest(
                documentId, "proc-sig06", "task-sig06", getTestUser().getId(), "digital", position);

        BpmSignatureRecord record = signatureService.sign(request);

        assertNotNull(record.getSignPosition(), "signPosition should not be null");
        assertEquals(100, ((Number) record.getSignPosition().get("x")).intValue());
        assertEquals(200, ((Number) record.getSignPosition().get("y")).intValue());
        assertEquals(1, ((Number) record.getSignPosition().get("page")).intValue());

        log.info("SIG-06 PASSED: signPosition JSONB data persists correctly");
    }

    @Test
    @Order(7)
    @DisplayName("SIG-07: Multiple signatures on same document verified together")
    void sig07_multipleSignaturesVerified() {
        String documentId = "doc-sig07-" + System.nanoTime();

        // Sign with user 1
        Map<String, Object> request1 = buildSignRequest(
                documentId, "proc-sig07", "task-sig07-a", getTestUser().getId(), "digital", null);
        signatureService.sign(request1);

        // Sign with user 2 (different signerUserId)
        Map<String, Object> request2 = buildSignRequest(
                documentId, "proc-sig07", "task-sig07-b", getTestUser().getId() + 1, "digital", null);
        signatureService.sign(request2);

        Map<String, Object> result = signatureService.verify(documentId);

        assertEquals(true, result.get("valid"), "All signatures should be valid");
        assertEquals(2, result.get("signatureCount"), "signatureCount should be 2");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> signatures = (List<Map<String, Object>>) result.get("signatures");
        assertEquals(2, signatures.size(), "Should have 2 signature details");

        log.info("SIG-07 PASSED: Multiple signatures verified, count={}", result.get("signatureCount"));
    }

    @Test
    @Order(8)
    @DisplayName("SIG-08: Query by processInstanceId returns correct records")
    void sig08_queryByProcessInstanceId() {
        String processInstanceId = "proc-sig08-" + System.nanoTime();

        // Sign 2 records with same processInstanceId
        Map<String, Object> request1 = buildSignRequest(
                "doc-sig08-a", processInstanceId, "task-sig08-a", getTestUser().getId(), "digital", null);
        signatureService.sign(request1);

        Map<String, Object> request2 = buildSignRequest(
                "doc-sig08-b", processInstanceId, "task-sig08-b", getTestUser().getId(), "digital", null);
        signatureService.sign(request2);

        List<BpmSignatureRecord> records = signatureService.getRecordsByProcess(processInstanceId);

        assertEquals(2, records.size(), "Should find 2 records for the same processInstanceId");

        log.info("SIG-08 PASSED: Query by processInstanceId returns {} records", records.size());
    }
}
