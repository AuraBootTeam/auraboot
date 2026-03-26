package com.auraboot.framework.scheduler.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.scheduler.dto.ScheduledTaskCreateRequest;
import com.auraboot.framework.scheduler.dto.TaskLogQueryRequest;
import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.scheduler.entity.ScheduledTaskLog;
import com.auraboot.framework.scheduler.service.ScheduledTaskLogService;
import com.auraboot.framework.scheduler.service.ScheduledTaskService;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for scheduled task management.
 *
 * @since 5.1.0
 */
@RestController
@RequestMapping("/api/scheduled-tasks")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.SYS_SCHEDULER_MANAGE)
@Tag(name = "Scheduled Tasks", description = "Scheduled task management")
public class ScheduledTaskController {

    private final ScheduledTaskService taskService;
    private final ScheduledTaskLogService logService;
    private final SchedulerEngine schedulerEngine;

    @PostMapping
    public ApiResponse<ScheduledTask> create(@Valid @RequestBody ScheduledTaskCreateRequest request) {
        return ApiResponse.success(taskService.create(request));
    }

    @GetMapping
    public ApiResponse<List<ScheduledTask>> list() {
        return ApiResponse.success(taskService.listAll());
    }

    @GetMapping("/{pid}")
    public ApiResponse<ScheduledTask> getByPid(@PathVariable String pid) {
        ScheduledTask task = taskService.getByPid(pid);
        if (task == null) {
            return ApiResponse.error("Task not found: " + pid);
        }
        return ApiResponse.success(task);
    }

    @PutMapping("/{pid}")
    public ApiResponse<ScheduledTask> update(@PathVariable String pid,
                                              @Valid @RequestBody ScheduledTaskCreateRequest request) {
        return ApiResponse.success(taskService.update(pid, request));
    }

    @DeleteMapping("/{pid}")
    public ApiResponse<Void> delete(@PathVariable String pid) {
        taskService.delete(pid);
        return ApiResponse.success();
    }

    @PutMapping("/{pid}/enable")
    public ApiResponse<Void> enable(@PathVariable String pid) {
        taskService.enable(pid);
        return ApiResponse.success();
    }

    @PutMapping("/{pid}/disable")
    public ApiResponse<Void> disable(@PathVariable String pid) {
        taskService.disable(pid);
        return ApiResponse.success();
    }

    @PostMapping("/{pid}/trigger")
    public ApiResponse<Void> trigger(@PathVariable String pid) {
        taskService.triggerManually(pid);
        return ApiResponse.success();
    }

    @PostMapping("/reload")
    public ApiResponse<Void> reload() {
        schedulerEngine.reload();
        return ApiResponse.success();
    }

    /**
     * Get execution logs for a task.
     * GET /api/scheduled-tasks/{pid}/logs
     */
    @GetMapping("/{pid}/logs")
    public ApiResponse<List<ScheduledTaskLog>> getLogs(@PathVariable String pid,
                                                        @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(logService.getByTaskPid(pid, limit));
    }

    /**
     * Query all logs with pagination.
     * GET /api/scheduled-tasks/logs
     */
    @GetMapping("/logs")
    public ApiResponse<PaginationResult<ScheduledTaskLog>> queryLogs(TaskLogQueryRequest request) {
        return ApiResponse.success(logService.query(request));
    }
}
