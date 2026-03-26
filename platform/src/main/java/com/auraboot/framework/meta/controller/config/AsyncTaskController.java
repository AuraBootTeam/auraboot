package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.AsyncTaskDTO;
import com.auraboot.framework.meta.dto.AsyncTaskSubmitRequest;
import com.auraboot.framework.meta.service.impl.AsyncTaskServiceImpl;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for the unified async task framework.
 * Provides endpoints to submit, query, cancel, and delete background tasks.
 */
@RestController
@RequestMapping("/api/async-tasks")
@RequiredArgsConstructor
public class AsyncTaskController {

    private final AsyncTaskServiceImpl asyncTaskService;

    /**
     * Submit a new async task.
     * POST /api/async-tasks
     */
    @PostMapping
    public ApiResponse<AsyncTaskDTO> submitTask(@Valid @RequestBody AsyncTaskSubmitRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        AsyncTaskDTO dto = asyncTaskService.submitTask(request, tenantId, userId);
        return ApiResponse.success(dto);
    }

    /**
     * Get task status and progress by task code.
     * GET /api/async-tasks/{taskCode}
     */
    @GetMapping("/{taskCode}")
    public ApiResponse<AsyncTaskDTO> getTask(@PathVariable String taskCode) {
        AsyncTaskDTO dto = asyncTaskService.getTask(taskCode);
        return ApiResponse.success(dto);
    }

    /**
     * List tasks with optional filtering and pagination.
     * GET /api/async-tasks?status=RUNNING&taskType=EXPORT&page=1&size=20
     */
    @GetMapping
    public ApiResponse<IPage<AsyncTaskDTO>> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String taskType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long tenantId = MetaContext.getCurrentTenantId();
        IPage<AsyncTaskDTO> result = asyncTaskService.listTasks(tenantId, status, taskType, page, size);
        return ApiResponse.success(result);
    }

    /**
     * Cancel a pending or running task.
     * POST /api/async-tasks/{taskCode}/cancel
     */
    @PostMapping("/{taskCode}/cancel")
    public ApiResponse<AsyncTaskDTO> cancelTask(@PathVariable String taskCode) {
        AsyncTaskDTO dto = asyncTaskService.cancelTask(taskCode);
        return ApiResponse.success(dto);
    }

    /**
     * Delete a completed/failed/cancelled task record.
     * DELETE /api/async-tasks/{taskCode}
     */
    @DeleteMapping("/{taskCode}")
    public ApiResponse<Void> deleteTask(@PathVariable String taskCode) {
        asyncTaskService.deleteTask(taskCode);
        return ApiResponse.success(null);
    }
}
