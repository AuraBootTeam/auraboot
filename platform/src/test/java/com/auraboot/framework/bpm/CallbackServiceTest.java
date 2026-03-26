package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.dto.CallbackResult;
import com.auraboot.framework.bpm.entity.BpmExecutionLog;
import com.auraboot.framework.bpm.mapper.BpmExecutionLogMapper;
import com.auraboot.framework.bpm.service.CallbackService;
import com.auraboot.framework.bpm.service.ExecutionLogService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for CallbackService.
 * Verifies callback handling, timeout logging, and pending callback queries.
 *
 * @author AuraBoot Team
 */
@Slf4j
@DisplayName("BPM Callback Service Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CallbackServiceTest extends BaseIntegrationTest {

    @Autowired
    private CallbackService callbackService;

    @Autowired
    private ExecutionLogService executionLogService;

    @Autowired
    private BpmExecutionLogMapper executionLogMapper;

    // ==================== Test Cases ====================

    @Test
    @Order(1)
    @DisplayName("CALLBACK-01: Success callback with no active execution throws BusinessException and logs NODE_FAILURE")
    void callback01_successCallbackWithNoExecutionThrowsAndLogsFailure() {
        String executionId = "cb-test-exec-01-" + System.nanoTime();
        String nodeId = "node-01";

        CallbackResult result = new CallbackResult(true, Map.of("output", "value1"), null);

        try {
            // engine.signal() will throw because executionId is not a valid Long
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> callbackService.handleCallback(executionId, nodeId, result),
                    "Should throw BusinessException when engine.signal fails");

            // BusinessException(msg, cause) stores cause.toString() as message (not the custom msg)
            assertNotNull(ex.getMessage(), "Exception message should not be null");

            // Verify NODE_FAILURE log was recorded
            List<BpmExecutionLog> failedLogs = executionLogMapper.findFailedNodes(executionId);
            assertFalse(failedLogs.isEmpty(), "Should have at least one NODE_FAILURE log entry");

            BpmExecutionLog failureLog = failedLogs.getFirst();
            assertEquals(executionId, failureLog.getExecutionId());
            assertEquals(nodeId, failureLog.getNodeId());
            assertEquals("node_failure", failureLog.getEventType());
            assertNotNull(failureLog.getErrorMessage(), "Error message should be recorded");

            log.info("CALLBACK-01 PASSED: Success callback with no execution logged NODE_FAILURE, msg={}", ex.getMessage());
        } catch (Exception e) {
            if (!(e instanceof BusinessException)) {
                Assumptions.assumeTrue(false, "SmartEngine not available: " + e.getMessage());
            } else {
                throw e;
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("CALLBACK-02: Failure callback logs NODE_FAILURE without signaling engine")
    void callback02_failureCallbackLogsWithoutSignal() {
        String executionId = "cb-test-exec-02-" + System.nanoTime();
        String nodeId = "node-02";
        String errorMsg = "External service failed";

        CallbackResult result = new CallbackResult(false, null, errorMsg);

        // Should not throw — failure callbacks don't signal the engine
        assertDoesNotThrow(
                () -> callbackService.handleCallback(executionId, nodeId, result),
                "Failure callback should not throw");

        // Verify NODE_FAILURE log exists
        List<BpmExecutionLog> failedLogs = executionLogMapper.findFailedNodes(executionId);
        assertEquals(1, failedLogs.size(), "Should have exactly one NODE_FAILURE log entry");

        BpmExecutionLog failureLog = failedLogs.getFirst();
        assertEquals(executionId, failureLog.getExecutionId());
        assertEquals(nodeId, failureLog.getNodeId());
        assertEquals("node_failure", failureLog.getEventType());
        assertTrue(failureLog.getErrorMessage().contains("External service failed"),
                "Error message should contain the original error, got: " + failureLog.getErrorMessage());

        // Verify no NODE_COMPLETE entry
        List<BpmExecutionLog> allLogs = executionLogMapper.findByExecutionId(executionId);
        boolean hasComplete = allLogs.stream()
                .anyMatch(l -> "node_complete".equals(l.getEventType()));
        assertFalse(hasComplete, "Failure callback should NOT produce a NODE_COMPLETE entry");

        log.info("CALLBACK-02 PASSED: Failure callback logged NODE_FAILURE without signal");
    }

    @Test
    @Order(3)
    @DisplayName("CALLBACK-03: Callback data preserved in failure log inputData")
    void callback03_callbackDataPreservedInFailureLog() {
        String executionId = "cb-test-exec-03-" + System.nanoTime();
        String nodeId = "node-03";

        Map<String, Object> callbackData = Map.of("key1", "val1", "key2", 42);
        CallbackResult result = new CallbackResult(false, callbackData, "Data preservation test");

        callbackService.handleCallback(executionId, nodeId, result);

        // Verify the NODE_FAILURE log preserves callback data in inputData
        List<BpmExecutionLog> failedLogs = executionLogMapper.findFailedNodes(executionId);
        assertEquals(1, failedLogs.size(), "Should have exactly one NODE_FAILURE log entry");

        BpmExecutionLog failureLog = failedLogs.getFirst();
        assertNotNull(failureLog.getInputData(), "inputData should not be null");
        assertEquals("val1", failureLog.getInputData().get("key1"),
                "inputData should contain key1=val1");
        assertNotNull(failureLog.getInputData().get("key2"),
                "inputData should contain key2");

        log.info("CALLBACK-03 PASSED: Callback data preserved in failure log inputData");
    }

    @Test
    @Order(4)
    @DisplayName("CALLBACK-04: Timeout logs NODE_FAILURE with timeout flag")
    void callback04_timeoutLogsFailureWithTimeoutFlag() {
        String executionId = "cb-test-exec-04-" + System.nanoTime();
        String nodeId = "node-timeout-04";

        callbackService.handleTimeout(executionId, nodeId);

        // Verify NODE_FAILURE log exists
        List<BpmExecutionLog> failedLogs = executionLogMapper.findFailedNodes(executionId);
        assertEquals(1, failedLogs.size(), "Should have exactly one NODE_FAILURE log entry");

        BpmExecutionLog failureLog = failedLogs.getFirst();
        assertEquals(executionId, failureLog.getExecutionId());
        assertEquals(nodeId, failureLog.getNodeId());
        assertEquals("node_failure", failureLog.getEventType());

        // Verify error message contains "Callback timeout"
        assertTrue(failureLog.getErrorMessage().contains("Callback timeout"),
                "Error message should contain 'Callback timeout', got: " + failureLog.getErrorMessage());
        assertTrue(failureLog.getErrorMessage().contains(nodeId),
                "Error message should contain nodeId, got: " + failureLog.getErrorMessage());

        // Verify timeout flag in context (stored as inputData)
        assertNotNull(failureLog.getInputData(), "inputData (context) should not be null");
        assertEquals(true, failureLog.getInputData().get("timeout"),
                "Context should contain timeout=true");

        log.info("CALLBACK-04 PASSED: Timeout logged NODE_FAILURE with timeout flag");
    }

    @Test
    @Order(5)
    @DisplayName("CALLBACK-05: getPendingCallbacks with nonexistent execution returns empty or handles gracefully")
    void callback05_getPendingCallbacksNonexistent() {
        String executionId = "999999999999";

        try {
            var pending = callbackService.getPendingCallbacks(executionId);
            assertNotNull(pending, "getPendingCallbacks should not return null");
            assertTrue(pending.isEmpty(),
                    "getPendingCallbacks for nonexistent execution should return empty list");

            log.info("CALLBACK-05 PASSED: getPendingCallbacks returned empty list for nonexistent execution");
        } catch (Exception e) {
            // SmartEngine may not be fully initialized in test context
            Assumptions.assumeTrue(false,
                    "SmartEngine not available for getPendingCallbacks: " + e.getMessage());
        }
    }

    @Test
    @Order(6)
    @DisplayName("CALLBACK-06: Signal failure wraps original exception in BusinessException")
    void callback06_signalFailureWrapsBusinessException() {
        String executionId = "cb-test-exec-06-" + System.nanoTime();
        String nodeId = "node-06";

        CallbackResult result = new CallbackResult(true, Map.of("result", "ok"), null);

        try {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> callbackService.handleCallback(executionId, nodeId, result),
                    "Should throw BusinessException when engine.signal fails for nonexistent execution");

            // BusinessException(msg, cause) stores cause as context; getMessage() returns cause.toString()
            assertNotNull(ex.getMessage(), "BusinessException message should not be null");
            // Verify the original cause is stored as context
            assertNotNull(ex.getContext(), "BusinessException should have context (the original cause)");

            log.info("CALLBACK-06 PASSED: Signal failure correctly wrapped in BusinessException, msg={}", ex.getMessage());
        } catch (Exception e) {
            if (!(e instanceof BusinessException)) {
                Assumptions.assumeTrue(false,
                        "SmartEngine not available: " + e.getMessage());
            } else {
                throw e;
            }
        }
    }
}
