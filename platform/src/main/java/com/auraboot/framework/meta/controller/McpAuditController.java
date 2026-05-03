package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.service.CommandAuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Audit endpoint for MCP tool invocations issued by external clients
 * such as the {@code aura mcp serve} CLI. Each call lands as a row in
 * {@code ab_command_audit_log} with {@code command_code = "mcp.{toolName}"},
 * sharing the same audit query UI / retention policy as regular commands.
 *
 * <p>The MCP server (CLI) is fire-and-forget — it must not block tool
 * execution on this endpoint. The endpoint therefore always returns 200
 * with {@code ApiResponse.success()} once the row is queued; backend
 * serialization failures are swallowed in the service layer (see
 * {@link CommandAuditLogService#recordMcpToolInvocation}).
 *
 * @author AuraBoot Team
 * @since 2.6.0 (GAP-300 Layer 1)
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/audit")
@RequiredArgsConstructor
@Tag(name = "MCP Audit", description = "Audit ingest for aura mcp serve tool invocations")
public class McpAuditController {

    private final CommandAuditLogService commandAuditLogService;

    @PostMapping("/mcp-tool")
    @Operation(summary = "Record an MCP tool invocation",
            description = "Writes one row to ab_command_audit_log with command_code=mcp.{toolName}")
    public ApiResponse<Void> recordToolInvocation(
            @Valid @RequestBody McpToolAuditRequest request) {
        commandAuditLogService.recordMcpToolInvocation(
                request.getToolName(),
                request.getInput(),
                request.getOutput(),
                request.isSuccess(),
                request.getErrorMessage(),
                request.getDurationMs());
        return ApiResponse.success();
    }

    /**
     * Audit payload sent by {@code aura mcp serve}'s {@code withAudit()}
     * wrapper after every tool invocation (success or failure).
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class McpToolAuditRequest {
        @NotBlank(message = "toolName is required")
        private String toolName;

        private Map<String, Object> input;
        private Map<String, Object> output;

        private boolean success;
        private String errorMessage;
        private long durationMs;
    }
}
