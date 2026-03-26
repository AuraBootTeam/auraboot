package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.bpm.dto.*;
import com.auraboot.framework.bpm.service.ExecutionLogService;
import com.auraboot.framework.bpm.service.ProcessOrchestrationService;
import com.auraboot.framework.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Orchestration controller.
 * Provides REST API for process orchestration (execution management, timeline, node control).
 */
@Slf4j
@RestController
@RequestMapping("/api/bpm/orchestration")
@RequiredArgsConstructor
@Tag(name = "Process Orchestration", description = "Orchestrated process execution management")
public class OrchestrationController {

    private final ProcessOrchestrationService orchestrationService;
    private final ExecutionLogService executionLogService;

    // ==================== Execution Management ====================

    @PostMapping("/executions")
    @Operation(summary = "Start execution", description = "Start a new orchestrated process execution")
    public ApiResponse<ExecutionResult> startExecution(@RequestBody StartExecutionRequest request) {
        log.info("Starting orchestrated execution: processKey={}", request.processKey());

        ExecutionResult result = orchestrationService.startExecution(
                request.processKey(), request.businessKey(), request.payload());

        return ApiResponse.success(result);
    }

    @GetMapping("/executions/{id}")
    @Operation(summary = "Get execution status", description = "Query the current status of an execution")
    public ApiResponse<ExecutionStatusDTO> getExecutionStatus(@PathVariable String id) {
        ExecutionStatusDTO status = orchestrationService.getExecutionStatus(id);
        if (status == null) {
            return ApiResponse.error("Execution not found: " + id);
        }
        return ApiResponse.success(status);
    }

    @PostMapping("/executions/{id}/pause")
    @Operation(summary = "Pause execution", description = "Pause execution at the current node")
    public ApiResponse<Void> pauseExecution(@PathVariable String id, @RequestBody(required = false) ReasonRequest request) {
        String reason = request != null ? request.reason() : null;
        orchestrationService.pauseExecution(id, reason);
        return ApiResponse.success();
    }

    @PostMapping("/executions/{id}/resume")
    @Operation(summary = "Resume execution", description = "Resume a paused execution")
    public ApiResponse<Void> resumeExecution(@PathVariable String id) {
        orchestrationService.resumeExecution(id);
        return ApiResponse.success();
    }

    @PostMapping("/executions/{id}/cancel")
    @Operation(summary = "Cancel execution", description = "Cancel an execution")
    public ApiResponse<Void> cancelExecution(@PathVariable String id, @RequestBody(required = false) ReasonRequest request) {
        String reason = request != null ? request.reason() : null;
        orchestrationService.cancelExecution(id, reason);
        return ApiResponse.success();
    }

    // ==================== Node-Level Control ====================

    @PostMapping("/executions/{id}/retry/{nodeId}")
    @Operation(summary = "Retry from node", description = "Retry execution from a specific node")
    public ApiResponse<Void> retryFromNode(
            @PathVariable String id,
            @PathVariable String nodeId,
            @RequestBody(required = false) Map<String, Object> overrideVariables) {
        orchestrationService.retryFromNode(id, nodeId, overrideVariables);
        return ApiResponse.success();
    }

    @PostMapping("/executions/{id}/skip/{nodeId}")
    @Operation(summary = "Skip node", description = "Skip a failed node and continue execution")
    public ApiResponse<Void> skipNode(
            @PathVariable String id,
            @PathVariable String nodeId,
            @RequestBody(required = false) Map<String, Object> outputVariables) {
        orchestrationService.skipNode(id, nodeId, outputVariables);
        return ApiResponse.success();
    }

    // ==================== Timeline & Details ====================

    @GetMapping("/executions/{id}/timeline")
    @Operation(summary = "Get execution timeline", description = "Get the full execution timeline with all node events")
    public ApiResponse<List<ExecutionLogEntry>> getTimeline(@PathVariable String id) {
        List<ExecutionLogEntry> timeline = executionLogService.getTimeline(id);
        return ApiResponse.success(timeline);
    }

    @GetMapping("/executions/{id}/nodes/{nodeId}")
    @Operation(summary = "Get node execution detail", description = "Get detailed execution history for a specific node")
    public ApiResponse<NodeExecutionDetail> getNodeDetail(
            @PathVariable String id,
            @PathVariable String nodeId) {
        NodeExecutionDetail detail = executionLogService.getNodeDetail(id, nodeId);
        if (detail == null) {
            return ApiResponse.error("Node not found in execution: " + nodeId);
        }
        return ApiResponse.success(detail);
    }

    @GetMapping("/executions/{id}/summary")
    @Operation(summary = "Get execution summary", description = "Get execution statistics summary")
    public ApiResponse<ExecutionSummaryDTO> getExecutionSummary(@PathVariable String id) {
        ExecutionSummaryDTO summary = executionLogService.getExecutionSummary(id);
        if (summary == null) {
            return ApiResponse.error("Execution not found: " + id);
        }
        return ApiResponse.success(summary);
    }

    @GetMapping("/executions/{id}/failures")
    @Operation(summary = "Get failed nodes", description = "Get all failed node entries for an execution")
    public ApiResponse<List<ExecutionLogEntry>> getFailedNodes(@PathVariable String id) {
        List<ExecutionLogEntry> failures = executionLogService.getFailedNodes(id);
        return ApiResponse.success(failures);
    }

    // ==================== Request Records ====================

    public record StartExecutionRequest(
            String processKey,
            String businessKey,
            Map<String, Object> payload
    ) {}

    public record ReasonRequest(
            String reason
    ) {}
}
