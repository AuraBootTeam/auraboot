package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.CommandAuditLogDTO;
import com.auraboot.framework.meta.entity.CommandAuditLog;
import com.auraboot.framework.meta.mapper.CommandAuditLogMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Service for querying command execution audit logs and recording
 * out-of-band invocations (e.g. MCP tool calls) into the same table.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommandAuditLogService {

    private final CommandAuditLogMapper commandAuditLogMapper;
    private final ObjectMapper objectMapper;

    /**
     * Query audit logs with optional filters. Returns a paginated result.
     *
     * @param commandCode filter by command code (null = all)
     * @param success     filter by success flag (null = all)
     * @param startDate   ISO-8601 start date (null = unbounded)
     * @param endDate     ISO-8601 end date (null = unbounded)
     * @param pageNum     1-based page number
     * @param pageSize    items per page (max 200)
     */
    public PaginationResult<CommandAuditLogDTO> queryLogs(
            String commandCode, Boolean success,
            String startDate, String endDate,
            int pageNum, int pageSize) {

        Long tenantId = MetaContext.getCurrentTenantId();
        int clampedPageSize = Math.min(pageSize, 200);
        int offset = (pageNum - 1) * clampedPageSize;

        List<CommandAuditLog> rows = commandAuditLogMapper.queryLogs(
                tenantId, commandCode, success, startDate, endDate,
                clampedPageSize, offset);
        long total = commandAuditLogMapper.countLogs(
                tenantId, commandCode, success, startDate, endDate);

        List<CommandAuditLogDTO> dtos = rows.stream()
                .map(CommandAuditLogDTO::from)
                .toList();

        return PaginationResult.of(dtos, total, pageNum, clampedPageSize);
    }

    /**
     * Get a single audit log entry by id.
     */
    public CommandAuditLogDTO findById(Long id) {
        Long tenantId = MetaContext.getCurrentTenantId();
        CommandAuditLog entity = commandAuditLogMapper.findById(tenantId, id);
        return entity != null ? CommandAuditLogDTO.from(entity) : null;
    }

    /**
     * Record an MCP tool invocation in the same {@code ab_command_audit_log}
     * table used by the regular Command Pipeline. The {@code commandCode}
     * column is prefixed with {@code mcp.} (e.g. {@code mcp.create_model})
     * so the existing audit query UI can filter by the prefix.
     *
     * <p>This is best-effort: a write failure is logged at warn level but
     * never thrown, mirroring the after-commit audit semantics in
     * {@code CommandEffectExecutor.saveAuditLog} (audit must not mask the
     * caller's actual outcome).
     *
     * @param toolName     MCP tool name without the {@code mcp.} prefix (e.g. {@code create_model})
     * @param input        tool arguments (will be JSON-serialized into request_payload)
     * @param output       tool result (will be JSON-serialized into execution_result; may be null)
     * @param success      true if the tool returned without {@code isError: true}
     * @param errorMessage error text from the tool result; may be null on success
     * @param durationMs   wall-clock time spent inside the tool handler
     */
    public void recordMcpToolInvocation(
            String toolName,
            Map<String, Object> input,
            Map<String, Object> output,
            boolean success,
            String errorMessage,
            long durationMs) {

        try {
            CommandAuditLog row = new CommandAuditLog();
            row.setTenantId(MetaContext.getCurrentTenantId());
            row.setUserId(MetaContext.getCurrentUserId());
            row.setCommandCode("mcp." + toolName);
            row.setRequestPayload(input != null ? objectMapper.writeValueAsString(input) : null);
            row.setExecutionResult(output != null ? objectMapper.writeValueAsString(output) : null);
            row.setSuccess(success);
            row.setErrorMessage(errorMessage);
            row.setExecutionTimeMs(durationMs);
            row.setPhaseReached("mcp_tool");
            row.setCreatedAt(Instant.now());
            commandAuditLogMapper.insertLog(row);
        } catch (JsonProcessingException e) {
            log.warn("Failed to JSON-serialize MCP audit payload for tool {}: {}",
                    toolName, e.getMessage());
        } catch (Exception e) {
            // Catch-all: audit write failure must not propagate to the caller.
            log.warn("Failed to insert MCP audit row for tool {}: {}",
                    toolName, e.getMessage());
        }
    }
}
