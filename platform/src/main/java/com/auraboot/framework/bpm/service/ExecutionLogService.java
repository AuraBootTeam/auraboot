package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.dto.ExecutionLogEntry;
import com.auraboot.framework.bpm.dto.ExecutionSummaryDTO;
import com.auraboot.framework.bpm.dto.NodeExecutionDetail;
import com.auraboot.framework.bpm.entity.BpmExecutionLog;
import com.auraboot.framework.bpm.enums.ExecutionEventType;
import com.auraboot.framework.bpm.mapper.BpmExecutionLogMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Execution log service for orchestrated processes.
 * Records node-level execution events and provides query/replay capabilities.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionLogService {

    private final BpmExecutionLogMapper executionLogMapper;

    // ==================== Log Recording ====================

    /**
     * Record a node start event.
     */
    public void logNodeStart(String executionId, String nodeId, String nodeType, Map<String, Object> input) {
        insertLog(executionId, nodeId, nodeType, ExecutionEventType.NODE_START, input, null, null, null, null);
    }

    /**
     * Record a node completion event.
     */
    public void logNodeComplete(String executionId, String nodeId, Map<String, Object> output, long durationMs) {
        insertLog(executionId, nodeId, null, ExecutionEventType.NODE_COMPLETE, null, output, null, null, durationMs);
    }

    /**
     * Record a node failure event.
     */
    public void logNodeFailure(String executionId, String nodeId, Throwable error, Map<String, Object> context) {
        String errorMessage = error.getMessage();
        String errorStack = getStackTrace(error);
        insertLog(executionId, nodeId, null, ExecutionEventType.NODE_FAILURE, context, null, errorMessage, errorStack, null);
    }

    /**
     * Record an execution state change (pause/resume/cancel).
     */
    public void logStateChange(String executionId, String fromState, String toState, String reason) {
        Map<String, Object> data = new HashMap<>();
        if (fromState != null) {
            data.put("fromState", fromState);
        }
        data.put("toState", toState);
        data.put("reason", reason != null ? reason : "");
        insertLog(executionId, null, null, ExecutionEventType.STATE_CHANGE, data, null, null, null, null);
    }

    // ==================== Query & Replay ====================

    /**
     * Get the full execution timeline ordered by time.
     */
    public List<ExecutionLogEntry> getTimeline(String executionId) {
        List<BpmExecutionLog> logs = executionLogMapper.findByExecutionId(executionId);
        return logs.stream().map(this::toEntry).toList();
    }

    /**
     * Get detailed execution history for a specific node.
     */
    public NodeExecutionDetail getNodeDetail(String executionId, String nodeId) {
        List<BpmExecutionLog> logs = executionLogMapper.findByExecutionIdAndNodeId(executionId, nodeId);
        if (logs.isEmpty()) {
            return null;
        }

        List<ExecutionLogEntry> events = logs.stream().map(this::toEntry).toList();

        String nodeType = logs.stream()
                .map(BpmExecutionLog::getNodeType)
                .filter(t -> t != null)
                .findFirst()
                .orElse("unknown");

        String latestStatus = logs.getLast().getEventType();

        long totalDuration = logs.stream()
                .filter(l -> l.getDurationMs() != null)
                .mapToLong(BpmExecutionLog::getDurationMs)
                .sum();

        return new NodeExecutionDetail(nodeId, nodeType, events, latestStatus, totalDuration);
    }

    /**
     * Get all failed node entries for an execution.
     */
    public List<ExecutionLogEntry> getFailedNodes(String executionId) {
        return executionLogMapper.findFailedNodes(executionId)
                .stream().map(this::toEntry).toList();
    }

    /**
     * Generate an execution summary with statistics.
     */
    public ExecutionSummaryDTO getExecutionSummary(String executionId) {
        List<BpmExecutionLog> logs = executionLogMapper.findByExecutionId(executionId);
        if (logs.isEmpty()) {
            return null;
        }

        int completedNodes = executionLogMapper.countByEventType(executionId, ExecutionEventType.NODE_COMPLETE.name());
        int failedNodes = executionLogMapper.countByEventType(executionId, ExecutionEventType.NODE_FAILURE.name());
        int totalNodes = executionLogMapper.countByEventType(executionId, ExecutionEventType.NODE_START.name());

        long totalDuration = logs.stream()
                .filter(l -> l.getDurationMs() != null)
                .mapToLong(BpmExecutionLog::getDurationMs)
                .sum();

        Instant startedAt = logs.getFirst().getCreatedAt();
        Instant completedAt = logs.getLast().getCreatedAt();

        return new ExecutionSummaryDTO(
                executionId, totalNodes, completedNodes, failedNodes,
                totalDuration, startedAt, completedAt
        );
    }

    // ==================== Internal ====================

    private void insertLog(String executionId, String nodeId, String nodeType,
                           ExecutionEventType eventType, Map<String, Object> inputData,
                           Map<String, Object> outputData, String errorMessage,
                           String errorStack, Long durationMs) {
        Long tenantId = MetaContext.getCurrentTenantId();

        BpmExecutionLog logEntry = BpmExecutionLog.builder()
                .pid(UlidGenerator.generate())
                .tenantId(tenantId)
                .executionId(executionId)
                .nodeId(nodeId)
                .nodeType(nodeType)
                .eventType(eventType.name())
                .inputData(inputData)
                .outputData(outputData)
                .errorMessage(errorMessage)
                .errorStack(errorStack)
                .durationMs(durationMs)
                .createdAt(Instant.now())
                .build();

        executionLogMapper.insert(logEntry);
        log.debug("Execution log recorded: executionId={}, nodeId={}, eventType={}",
                executionId, nodeId, eventType);
    }

    private ExecutionLogEntry toEntry(BpmExecutionLog entity) {
        return new ExecutionLogEntry(
                entity.getPid(),
                entity.getExecutionId(),
                entity.getNodeId(),
                entity.getNodeType(),
                entity.getEventType(),
                entity.getInputData(),
                entity.getOutputData(),
                entity.getErrorMessage(),
                entity.getDurationMs(),
                entity.getCreatedAt()
        );
    }

    private String getStackTrace(Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        String stack = sw.toString();
        // Truncate to avoid storing massive stack traces
        return stack.length() > 4000 ? stack.substring(0, 4000) : stack;
    }
}
