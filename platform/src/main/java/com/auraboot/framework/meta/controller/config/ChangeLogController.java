package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.ChangeLogQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.entity.DataChangeLog;
import com.auraboot.framework.meta.service.ChangeLogService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for querying data change history.
 *
 * @since 5.1.0
 */
@RestController
@RequestMapping("/api/meta/change-logs")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.META_CHANGELOG_READ)
public class ChangeLogController {

    private final ChangeLogService changeLogService;

    /**
     * Get change history for a specific record.
     * GET /api/meta/change-logs/history?modelCode=xxx&recordId=xxx
     */
    @GetMapping("/history")
    public ApiResponse<List<DataChangeLog>> getHistory(
            @RequestParam String modelCode,
            @RequestParam String recordId) {
        List<DataChangeLog> history = changeLogService.getHistory(modelCode, recordId);
        return ApiResponse.success(history);
    }

    /**
     * Get change logs for current user with pagination.
     * GET /api/meta/change-logs/my
     */
    @GetMapping("/my")
    public ApiResponse<PaginationResult<DataChangeLog>> getMyChanges(ChangeLogQueryRequest request) {
        Long userId = MetaContext.getCurrentUserId();
        PaginationResult<DataChangeLog> result = changeLogService.getByUser(userId, request);
        return ApiResponse.success(result);
    }

    /**
     * Get a single change log entry.
     * GET /api/meta/change-logs/{id}
     */
    @GetMapping("/{id}")
    public ApiResponse<DataChangeLog> getById(@PathVariable Long id) {
        DataChangeLog log = changeLogService.getById(id);
        if (log == null) {
            return ApiResponse.error("Change log not found: " + id);
        }
        return ApiResponse.success(log);
    }
}
