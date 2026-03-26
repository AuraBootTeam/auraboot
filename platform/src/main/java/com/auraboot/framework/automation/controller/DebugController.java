package com.auraboot.framework.automation.controller;

import com.auraboot.framework.automation.dto.DebugSessionCreateRequest;
import com.auraboot.framework.automation.dto.DebugSessionDTO;
import com.auraboot.framework.automation.service.DebugSessionService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * Debug Controller for automation step-through debugging.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@RestController
@RequestMapping("/api/automation")
@RequiredArgsConstructor
@Validated
@Tag(name = "Automation Debug", description = "Step-through debugging for automation workflows")
public class DebugController {

    private final DebugSessionService debugSessionService;

    @PostMapping("/{automationId}/debug/sessions")
    @Operation(summary = "Create debug session", description = "Start a new debug session for an automation")
    @RequirePermission(MetaPermission.AUTOMATION_MANAGE)
    public ApiResponse<DebugSessionDTO> createSession(
            @Parameter(description = "Automation PID") @PathVariable @NotBlank String automationId,
            @Valid @RequestBody DebugSessionCreateRequest request) {
        log.info("Creating debug session for automation: {}", automationId);
        DebugSessionDTO session = debugSessionService.createSession(automationId, request);
        return ApiResponse.success("Debug session created", session);
    }

    @GetMapping("/debug/sessions/{sessionId}")
    @Operation(summary = "Get debug session", description = "Get debug session state")
    @RequirePermission(MetaPermission.AUTOMATION_READ)
    public ApiResponse<DebugSessionDTO> getSession(
            @Parameter(description = "Session PID") @PathVariable @NotBlank String sessionId) {
        DebugSessionDTO session = debugSessionService.getSession(sessionId);
        return ApiResponse.success(session);
    }

    @PostMapping("/debug/sessions/{sessionId}/step")
    @Operation(summary = "Step execution", description = "Execute the next action and pause")
    @RequirePermission(MetaPermission.AUTOMATION_MANAGE)
    public ApiResponse<DebugSessionDTO> step(
            @Parameter(description = "Session PID") @PathVariable @NotBlank String sessionId) {
        log.info("Stepping debug session: {}", sessionId);
        DebugSessionDTO session = debugSessionService.step(sessionId);
        return ApiResponse.success(session);
    }

    @PostMapping("/debug/sessions/{sessionId}/continue")
    @Operation(summary = "Continue execution", description = "Continue until next breakpoint or completion")
    @RequirePermission(MetaPermission.AUTOMATION_MANAGE)
    public ApiResponse<DebugSessionDTO> continueExecution(
            @Parameter(description = "Session PID") @PathVariable @NotBlank String sessionId) {
        log.info("Continuing debug session: {}", sessionId);
        DebugSessionDTO session = debugSessionService.continueExecution(sessionId);
        return ApiResponse.success(session);
    }

    @PostMapping("/debug/sessions/{sessionId}/stop")
    @Operation(summary = "Stop debug session", description = "Stop the debug session")
    @RequirePermission(MetaPermission.AUTOMATION_MANAGE)
    public ApiResponse<DebugSessionDTO> stop(
            @Parameter(description = "Session PID") @PathVariable @NotBlank String sessionId) {
        log.info("Stopping debug session: {}", sessionId);
        DebugSessionDTO session = debugSessionService.stop(sessionId);
        return ApiResponse.success(session);
    }

    @PostMapping("/debug/sessions/{sessionId}/restart")
    @Operation(summary = "Restart debug session", description = "Restart session from the beginning")
    @RequirePermission(MetaPermission.AUTOMATION_MANAGE)
    public ApiResponse<DebugSessionDTO> restart(
            @Parameter(description = "Session PID") @PathVariable @NotBlank String sessionId) {
        log.info("Restarting debug session: {}", sessionId);
        DebugSessionDTO session = debugSessionService.restart(sessionId);
        return ApiResponse.success(session);
    }

    @GetMapping("/debug/sessions/{sessionId}/context")
    @Operation(summary = "Get execution context", description = "Get current execution context variables")
    @RequirePermission(MetaPermission.AUTOMATION_READ)
    public ApiResponse<Map<String, Object>> getContext(
            @Parameter(description = "Session PID") @PathVariable @NotBlank String sessionId) {
        Map<String, Object> context = debugSessionService.getContext(sessionId);
        return ApiResponse.success(context);
    }

    @PutMapping("/debug/sessions/{sessionId}/breakpoints")
    @Operation(summary = "Update breakpoints", description = "Set breakpoints for the debug session")
    @RequirePermission(MetaPermission.AUTOMATION_MANAGE)
    public ApiResponse<DebugSessionDTO> updateBreakpoints(
            @Parameter(description = "Session PID") @PathVariable @NotBlank String sessionId,
            @RequestBody List<Integer> breakpoints) {
        log.info("Updating breakpoints for session {}: {}", sessionId, breakpoints);
        DebugSessionDTO session = debugSessionService.updateBreakpoints(sessionId, breakpoints);
        return ApiResponse.success(session);
    }

    @GetMapping(value = "/debug/sessions/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to debug events", description = "SSE stream of real-time debug events")
    @RequirePermission(MetaPermission.AUTOMATION_READ)
    public SseEmitter subscribeEvents(
            @Parameter(description = "Session PID") @PathVariable @NotBlank String sessionId) {
        log.info("SSE subscription for debug session: {}", sessionId);
        return debugSessionService.subscribeEvents(sessionId);
    }
}
