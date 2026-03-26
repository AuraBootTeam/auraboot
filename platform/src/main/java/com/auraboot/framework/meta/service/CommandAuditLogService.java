package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.CommandAuditLogDTO;
import com.auraboot.framework.meta.entity.CommandAuditLog;
import com.auraboot.framework.meta.mapper.CommandAuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for querying command execution audit logs.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Service
@RequiredArgsConstructor
public class CommandAuditLogService {

    private final CommandAuditLogMapper commandAuditLogMapper;

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
}
