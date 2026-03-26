package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IdempotencyService Integration Test
 *
 * Covers P1-3 requirements:
 * 1. Check idempotency for new requests
 * 2. Record outcomes for executed requests
 * 3. Replay previously executed requests
 * 4. Cleanup expired records
 * 
 * Each test is self-contained and creates its own test data.
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("IdempotencyService Integration Test - P1-3")
class IdempotencyServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IdempotencyService idempotencyService;

    // ==================== Check Idempotency Tests ====================

    @Test
    @Order(1)
    @DisplayName("P1-3.1: New request returns null (not previously processed)")
    void test01_checkIdempotency_newRequest() {
        String clientRequestId = "new_req_" + UUID.randomUUID();
        Long tenantId = getTestTenant().getId();

        Map<String, Object> result = idempotencyService.checkIdempotency(clientRequestId, tenantId);

        assertNull(result, "New request should not have existing outcome");
    }

    @Test
    @Order(2)
    @DisplayName("P1-3.1: Null clientRequestId should not throw")
    void test02_checkIdempotency_nullId() {
        Long tenantId = getTestTenant().getId();
        
        Map<String, Object> result = idempotencyService.checkIdempotency(null, tenantId);

        assertNull(result);
    }

    // ==================== Record Outcome Tests ====================

    @Test
    @Order(10)
    @DisplayName("P1-3.2: Record outcome for a request")
    void test10_recordOutcome() {
        String clientRequestId = "record_req_" + UUID.randomUUID();
        String commandCode = "test_command";
        Map<String, Object> payload = Map.of("name", "test", "value", 123);
        Map<String, Object> outcome = Map.of("id", "rec_001", "status", "created");
        Long tenantId = getTestTenant().getId();

        assertDoesNotThrow(() -> {
            idempotencyService.recordOutcome(clientRequestId, commandCode, payload, outcome, tenantId);
        });

        log.info("Recorded outcome for clientRequestId={}", clientRequestId);
    }

    @Test
    @Order(11)
    @DisplayName("P1-3.2: Recorded outcome can be retrieved via checkIdempotency")
    void test11_recordAndCheck() {
        String clientRequestId = "check_req_" + UUID.randomUUID();
        String commandCode = "test_command";
        Map<String, Object> payload = Map.of("field", "value");
        Map<String, Object> outcome = Map.of("recordId", "rec_002", "status", "success");
        Long tenantId = getTestTenant().getId();

        idempotencyService.recordOutcome(clientRequestId, commandCode, payload, outcome, tenantId);

        Map<String, Object> retrieved = idempotencyService.checkIdempotency(clientRequestId, tenantId);

        assertNotNull(retrieved, "Should retrieve previously recorded outcome");
    }

    @Test
    @Order(12)
    @DisplayName("P1-3.2: Multiple outcomes for different requests are independent")
    void test12_multipleOutcomes() {
        String req1 = "multi_req1_" + UUID.randomUUID();
        String req2 = "multi_req2_" + UUID.randomUUID();
        Long tenantId = getTestTenant().getId();

        Map<String, Object> outcome1 = Map.of("id", "A");
        Map<String, Object> outcome2 = Map.of("id", "B");

        idempotencyService.recordOutcome(req1, "cmd1", Map.of(), outcome1, tenantId);
        idempotencyService.recordOutcome(req2, "cmd2", Map.of(), outcome2, tenantId);

        Map<String, Object> result1 = idempotencyService.checkIdempotency(req1, tenantId);
        Map<String, Object> result2 = idempotencyService.checkIdempotency(req2, tenantId);

        assertNotNull(result1);
        assertNotNull(result2);
    }

    @Test
    @Order(13)
    @DisplayName("P1-3.2: Same clientRequestId with different tenant should be independent")
    void test13_tenantIsolation() {
        String clientRequestId = "tenant_iso_" + UUID.randomUUID();
        Long tenant1 = getTestTenant().getId();
        Long tenant2 = tenant1 + 999999L; // Different tenant

        Map<String, Object> outcome = Map.of("data", "test");
        idempotencyService.recordOutcome(clientRequestId, "cmd", Map.of(), outcome, tenant1);

        Map<String, Object> result1 = idempotencyService.checkIdempotency(clientRequestId, tenant1);
        Map<String, Object> result2 = idempotencyService.checkIdempotency(clientRequestId, tenant2);

        assertNotNull(result1, "Should find outcome for tenant1");
        assertNull(result2, "Should not find outcome for different tenant");
    }

    // ==================== Cleanup Tests ====================

    @Test
    @Order(20)
    @DisplayName("P1-3.3: Cleanup expired records")
    void test20_cleanupExpired() {
        int cleaned = idempotencyService.cleanupExpired();

        assertTrue(cleaned >= 0, "Cleanup should return non-negative count");
        log.info("Cleaned up {} expired idempotency records", cleaned);
    }

    @Test
    @Order(21)
    @DisplayName("P1-3.3: Cleanup is idempotent (running twice is safe)")
    void test21_cleanupIdempotent() {
        int first = idempotencyService.cleanupExpired();
        int second = idempotencyService.cleanupExpired();

        assertTrue(first >= 0);
        assertTrue(second >= 0);
        // Second cleanup should clean equal or fewer records
        assertTrue(second <= first || second == 0);
    }

    // ==================== Edge Cases ====================

    @Test
    @Order(30)
    @DisplayName("P1-3: Record outcome with empty payload and result")
    void test30_emptyPayloadAndResult() {
        String clientRequestId = "empty_req_" + UUID.randomUUID();
        Long tenantId = getTestTenant().getId();

        assertDoesNotThrow(() -> {
            idempotencyService.recordOutcome(clientRequestId, "cmd", Map.of(), Map.of(), tenantId);
        });
    }

    @Test
    @Order(31)
    @DisplayName("P1-3: Record outcome with large payload")
    void test31_largePayload() {
        String clientRequestId = "large_req_" + UUID.randomUUID();
        Long tenantId = getTestTenant().getId();
        
        Map<String, Object> largePayload = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            largePayload.put("field_" + i, "value_" + i);
        }

        assertDoesNotThrow(() -> {
            idempotencyService.recordOutcome(clientRequestId, "cmd", largePayload, Map.of("status", "ok"), tenantId);
        });

        Map<String, Object> result = idempotencyService.checkIdempotency(clientRequestId, tenantId);
        assertNotNull(result);
    }
}
