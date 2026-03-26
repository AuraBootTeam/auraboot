package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.dto.ExecutionLogEntry;
import com.auraboot.framework.bpm.dto.ExecutionSummaryDTO;
import com.auraboot.framework.bpm.dto.NodeExecutionDetail;
import com.auraboot.framework.bpm.entity.BpmExecutionLog;
import com.auraboot.framework.bpm.mapper.BpmExecutionLogMapper;
import com.auraboot.framework.bpm.service.ExecutionLogService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ExecutionLogService covering node event logging,
 * timeline queries, node detail aggregation, failure queries,
 * execution summary statistics, and stack trace truncation.
 */
@Slf4j
@DisplayName("Execution Log Service Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExecutionLogServiceTest extends BaseIntegrationTest {

    @Autowired
    private ExecutionLogService executionLogService;

    @Autowired
    private BpmExecutionLogMapper executionLogMapper;

    // ==================== Test Cases ====================

    @Test
    @Order(1)
    @DisplayName("EXECLOG-01: logNodeStart records NODE_START event with input data")
    void execlog01_logNodeStart() {
        String executionId = "exec-01-" + System.nanoTime();
        Map<String, Object> input = Map.of("param1", "value1", "param2", 42);

        executionLogService.logNodeStart(executionId, "node-01", "user_task", input);

        List<ExecutionLogEntry> timeline = executionLogService.getTimeline(executionId);
        assertEquals(1, timeline.size(), "Timeline should have 1 entry");

        ExecutionLogEntry entry = timeline.getFirst();
        assertEquals("node_start", entry.eventType());
        assertEquals("user_task", entry.nodeType());
        assertEquals("node-01", entry.nodeId());
        assertNotNull(entry.inputData(), "inputData should be present");
        assertEquals("value1", entry.inputData().get("param1"));

        log.info("EXECLOG-01 PASSED: NODE_START recorded with nodeType={}, inputData present", entry.nodeType());
    }

    @Test
    @Order(2)
    @DisplayName("EXECLOG-02: logNodeComplete records output data and duration")
    void execlog02_logNodeComplete() {
        String executionId = "exec-02-" + System.nanoTime();
        Map<String, Object> output = Map.of("result", "success", "count", 5);

        executionLogService.logNodeComplete(executionId, "node-02", output, 150L);

        List<ExecutionLogEntry> timeline = executionLogService.getTimeline(executionId);
        assertEquals(1, timeline.size());

        ExecutionLogEntry entry = timeline.getFirst();
        assertEquals("node_complete", entry.eventType());
        assertNotNull(entry.outputData(), "outputData should be present");
        assertEquals("success", entry.outputData().get("result"));
        assertEquals(150L, entry.durationMs(), "durationMs should be 150");

        log.info("EXECLOG-02 PASSED: NODE_COMPLETE recorded with durationMs={}", entry.durationMs());
    }

    @Test
    @Order(3)
    @DisplayName("EXECLOG-03: logNodeFailure records error message")
    void execlog03_logNodeFailure() {
        String executionId = "exec-03-" + System.nanoTime();
        RuntimeException error = new RuntimeException("Test error");
        Map<String, Object> context = Map.of("attemptNumber", 1);

        executionLogService.logNodeFailure(executionId, "node-03", error, context);

        List<ExecutionLogEntry> timeline = executionLogService.getTimeline(executionId);
        assertEquals(1, timeline.size());

        ExecutionLogEntry entry = timeline.getFirst();
        assertEquals("node_failure", entry.eventType());
        assertNotNull(entry.errorMessage(), "errorMessage should be present");
        assertTrue(entry.errorMessage().contains("Test error"), "errorMessage should contain 'Test error'");

        log.info("EXECLOG-03 PASSED: NODE_FAILURE recorded with errorMessage={}", entry.errorMessage());
    }

    @Test
    @Order(4)
    @DisplayName("EXECLOG-04: logStateChange records fromState/toState in inputData")
    void execlog04_logStateChange() {
        String executionId = "exec-04-" + System.nanoTime();

        executionLogService.logStateChange(executionId, "running", "paused", "test reason");

        List<ExecutionLogEntry> timeline = executionLogService.getTimeline(executionId);
        assertEquals(1, timeline.size());

        ExecutionLogEntry entry = timeline.getFirst();
        assertEquals("state_change", entry.eventType());
        assertNotNull(entry.inputData(), "inputData should contain state change info");
        assertEquals("running", entry.inputData().get("fromState"));
        assertEquals("paused", entry.inputData().get("toState"));
        assertEquals("test reason", entry.inputData().get("reason"));

        log.info("EXECLOG-04 PASSED: STATE_CHANGE recorded with fromState={}, toState={}",
                entry.inputData().get("fromState"), entry.inputData().get("toState"));
    }

    @Test
    @Order(5)
    @DisplayName("EXECLOG-05: getTimeline returns ordered events for same execution")
    void execlog05_timelineOrdered() {
        String executionId = "exec-05-" + System.nanoTime();

        executionLogService.logNodeStart(executionId, "node-a", "user_task", Map.of());
        executionLogService.logNodeComplete(executionId, "node-a", Map.of(), 100L);
        executionLogService.logNodeStart(executionId, "node-b", "service_task", Map.of());

        List<ExecutionLogEntry> timeline = executionLogService.getTimeline(executionId);

        assertEquals(3, timeline.size(), "Timeline should have 3 entries");
        // Verify ordering by createdAt ASC
        assertEquals("node_start", timeline.get(0).eventType());
        assertEquals("node-a", timeline.get(0).nodeId());
        assertEquals("node_complete", timeline.get(1).eventType());
        assertEquals("node_start", timeline.get(2).eventType());
        assertEquals("node-b", timeline.get(2).nodeId());

        log.info("EXECLOG-05 PASSED: Timeline returned {} entries in order", timeline.size());
    }

    @Test
    @Order(6)
    @DisplayName("EXECLOG-06: getNodeDetail aggregates events for a single node")
    void execlog06_nodeDetailAggregated() {
        String executionId = "exec-06-" + System.nanoTime();
        String nodeId = "node-06";

        executionLogService.logNodeStart(executionId, nodeId, "user_task", Map.of());
        executionLogService.logNodeComplete(executionId, nodeId, Map.of("done", true), 250L);

        NodeExecutionDetail detail = executionLogService.getNodeDetail(executionId, nodeId);

        assertNotNull(detail, "NodeExecutionDetail should not be null");
        assertEquals(nodeId, detail.nodeId());
        assertEquals("user_task", detail.nodeType());
        assertEquals("node_complete", detail.latestStatus(), "latestStatus should be NODE_COMPLETE");
        assertEquals(250L, detail.totalDurationMs(), "totalDurationMs should match");
        assertEquals(2, detail.events().size(), "Should have 2 events (start + complete)");

        log.info("EXECLOG-06 PASSED: NodeDetail aggregated, latestStatus={}, totalDurationMs={}",
                detail.latestStatus(), detail.totalDurationMs());
    }

    @Test
    @Order(7)
    @DisplayName("EXECLOG-07: getFailedNodes returns only NODE_FAILURE entries")
    void execlog07_failedNodesQuery() {
        String executionId = "exec-07-" + System.nanoTime();

        executionLogService.logNodeStart(executionId, "node-ok", "user_task", Map.of());
        executionLogService.logNodeFailure(executionId, "node-fail", new RuntimeException("Fail"), Map.of());
        executionLogService.logNodeComplete(executionId, "node-ok", Map.of(), 100L);

        List<ExecutionLogEntry> failed = executionLogService.getFailedNodes(executionId);

        assertEquals(1, failed.size(), "Should find exactly 1 failed node");
        assertEquals("node_failure", failed.getFirst().eventType());
        assertEquals("node-fail", failed.getFirst().nodeId());

        log.info("EXECLOG-07 PASSED: getFailedNodes returned {} entries", failed.size());
    }

    @Test
    @Order(8)
    @DisplayName("EXECLOG-08: getExecutionSummary returns correct counts")
    void execlog08_executionSummaryCounts() {
        String executionId = "exec-08-" + System.nanoTime();

        // 2 NODE_START events (2 distinct nodes started)
        executionLogService.logNodeStart(executionId, "node-a", "user_task", Map.of());
        executionLogService.logNodeStart(executionId, "node-b", "service_task", Map.of());
        // 1 NODE_COMPLETE
        executionLogService.logNodeComplete(executionId, "node-a", Map.of(), 100L);
        // 1 NODE_FAILURE
        executionLogService.logNodeFailure(executionId, "node-b", new RuntimeException("error"), Map.of());

        ExecutionSummaryDTO summary = executionLogService.getExecutionSummary(executionId);

        assertNotNull(summary, "ExecutionSummaryDTO should not be null");
        assertEquals(executionId, summary.executionId());
        assertEquals(2, summary.totalNodes(), "totalNodes should be 2 (NODE_START count)");
        assertEquals(1, summary.completedNodes(), "completedNodes should be 1");
        assertEquals(1, summary.failedNodes(), "failedNodes should be 1");
        assertNotNull(summary.startedAt());
        assertNotNull(summary.completedAt());

        log.info("EXECLOG-08 PASSED: Summary total={}, completed={}, failed={}",
                summary.totalNodes(), summary.completedNodes(), summary.failedNodes());
    }

    @Test
    @Order(9)
    @DisplayName("EXECLOG-09: getNodeDetail returns null for non-existent node")
    void execlog09_nodeDetailNotFound() {
        String executionId = "exec-09-" + System.nanoTime();

        NodeExecutionDetail detail = executionLogService.getNodeDetail(executionId, "nonexistent-node");

        assertNull(detail, "getNodeDetail should return null for non-existent node");

        log.info("EXECLOG-09 PASSED: getNodeDetail returns null for non-existent node");
    }

    @Test
    @Order(10)
    @DisplayName("EXECLOG-10: Stack trace truncated to 4000 chars in DB")
    void execlog10_stackTraceTruncation() {
        String executionId = "exec-10-" + System.nanoTime();
        String nodeId = "node-10";

        // Build deeply nested exception chain to generate >4000 char stack trace
        Exception innermost = new RuntimeException("innermost error");
        Exception current = innermost;
        for (int i = 0; i < 50; i++) {
            current = new RuntimeException("wrapper-level-" + i + "-with-extra-padding-to-increase-length", current);
        }

        executionLogService.logNodeFailure(executionId, nodeId, current, Map.of());

        // Query directly via mapper to check errorStack field
        List<BpmExecutionLog> logs = executionLogMapper.findByExecutionIdAndNodeId(executionId, nodeId);
        assertEquals(1, logs.size(), "Should find 1 log entry");

        BpmExecutionLog logEntry = logs.getFirst();
        assertNotNull(logEntry.getErrorStack(), "errorStack should not be null");
        assertTrue(logEntry.getErrorStack().length() <= 4000,
                "errorStack should be truncated to <=4000 chars, actual=" + logEntry.getErrorStack().length());

        log.info("EXECLOG-10 PASSED: errorStack length={} (<=4000)", logEntry.getErrorStack().length());
    }
}
