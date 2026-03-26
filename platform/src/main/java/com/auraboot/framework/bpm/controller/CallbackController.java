package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.bpm.dto.CallbackResult;
import com.auraboot.framework.bpm.dto.PendingCallbackDTO;
import com.auraboot.framework.bpm.service.CallbackService;
import com.auraboot.framework.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Callback controller.
 * Provides REST API for handling async callbacks from serviceTask/receiveTask nodes.
 */
@Slf4j
@RestController
@RequestMapping("/api/bpm/callbacks")
@RequiredArgsConstructor
@Tag(name = "Process Callbacks", description = "Async callback handling for orchestrated processes")
public class CallbackController {

    private final CallbackService callbackService;

    @PostMapping("/{executionId}/{nodeId}")
    @Operation(summary = "Handle callback", description = "Process an external callback for a waiting node")
    public ApiResponse<Void> handleCallback(
            @PathVariable String executionId,
            @PathVariable String nodeId,
            @RequestBody CallbackResult result) {
        log.info("Callback received: executionId={}, nodeId={}", executionId, nodeId);
        callbackService.handleCallback(executionId, nodeId, result);
        return ApiResponse.success();
    }

    @GetMapping("/{executionId}/pending")
    @Operation(summary = "Get pending callbacks", description = "Query nodes waiting for callbacks")
    public ApiResponse<List<PendingCallbackDTO>> getPendingCallbacks(@PathVariable String executionId) {
        List<PendingCallbackDTO> pending = callbackService.getPendingCallbacks(executionId);
        return ApiResponse.success(pending);
    }

    @PostMapping("/{executionId}/{nodeId}/timeout")
    @Operation(summary = "Handle timeout", description = "Mark a callback as timed out")
    public ApiResponse<Void> handleTimeout(
            @PathVariable String executionId,
            @PathVariable String nodeId) {
        callbackService.handleTimeout(executionId, nodeId);
        return ApiResponse.success();
    }
}
