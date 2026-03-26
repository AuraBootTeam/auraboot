package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.meta.dto.AsyncTaskDTO;
import com.auraboot.framework.meta.dto.AsyncTaskSubmitRequest;
import com.auraboot.framework.meta.entity.AsyncTask;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.AsyncTaskMapper;
import com.auraboot.framework.meta.service.AsyncTaskExecutor;
import com.auraboot.framework.meta.service.AsyncTaskResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * Core async task service.
 * Manages the lifecycle of background tasks: submit, execute, track, cancel, cleanup.
 *
 * <p>Task executors are auto-discovered from the Spring context and registered
 * by their {@code getTaskType()} value.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncTaskServiceImpl {

    private final AsyncTaskMapper asyncTaskMapper;
    private final List<AsyncTaskExecutor> executors;

    /** Self-reference for @Async proxy to work on internal calls */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private AsyncTaskServiceImpl self;

    /** Registered executors keyed by task type */
    private final Map<String, AsyncTaskExecutor> executorRegistry = new ConcurrentHashMap<>();

    /** Running task futures for cancellation support */
    private final ConcurrentHashMap<String, Thread> runningTaskThreads = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        for (AsyncTaskExecutor executor : executors) {
            executorRegistry.put(executor.getTaskType(), executor);
            log.info("Registered async task executor: type={}, class={}",
                    executor.getTaskType(), executor.getClass().getSimpleName());
        }
        log.info("Async task framework initialized with {} executor(s)", executorRegistry.size());
    }

    // ==================== Public API ====================

    /**
     * Submit a new async task.
     *
     * @param request    task submission request
     * @param tenantId   current tenant ID
     * @param userId     current user ID
     * @return DTO with the task code for tracking
     */
    public AsyncTaskDTO submitTask(AsyncTaskSubmitRequest request, Long tenantId, Long userId) {
        // Validate executor exists
        if (!executorRegistry.containsKey(request.getTaskType())) {
            throw new MetaServiceException("No executor registered for task type: " + request.getTaskType());
        }

        // Create task record
        AsyncTask task = new AsyncTask();
        task.setTenantId(tenantId);
        task.setTaskCode(UlidGenerator.generate());
        task.setTaskType(request.getTaskType());
        task.setTaskName(request.getTaskName());
        task.setStatus(AsyncTask.STATUS_PENDING);
        task.setPriority(request.getPriority() != null ? request.getPriority() : 5);
        task.setProgress(0);
        task.setInputParams(request.getInputParams());
        task.setRetryCount(0);
        task.setMaxRetries(request.getMaxRetries() != null ? request.getMaxRetries() : 3);
        task.setCreatedBy(userId);
        task.setCreatedAt(Instant.now());
        task.setTimeoutSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 3600);

        asyncTaskMapper.insert(task);

        log.info("Async task submitted: code={}, type={}, name={}",
                task.getTaskCode(), task.getTaskType(), task.getTaskName());

        // Kick off async execution via self-proxy (required for @Async to work)
        self.executeTaskAsync(task.getId(), tenantId);

        return toDTO(task);
    }

    /**
     * Get a single task by its task code.
     */
    public AsyncTaskDTO getTask(String taskCode) {
        AsyncTask task = asyncTaskMapper.findByTaskCode(taskCode);
        if (task == null) {
            throw new MetaServiceException("Async task not found: " + taskCode);
        }
        return toDTO(task);
    }

    /**
     * List tasks with filtering and pagination.
     */
    public IPage<AsyncTaskDTO> listTasks(Long tenantId, String status, String taskType,
                                          int pageNum, int pageSize) {
        LambdaQueryWrapper<AsyncTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AsyncTask::getTenantId, tenantId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(AsyncTask::getStatus, status);
        }
        if (taskType != null && !taskType.isBlank()) {
            wrapper.eq(AsyncTask::getTaskType, taskType);
        }
        wrapper.orderByDesc(AsyncTask::getCreatedAt);

        Page<AsyncTask> page = new Page<>(pageNum, pageSize);
        IPage<AsyncTask> result = asyncTaskMapper.selectPage(page, wrapper);

        return result.convert(this::toDTO);
    }

    /**
     * Cancel a pending or running task.
     */
    public AsyncTaskDTO cancelTask(String taskCode) {
        AsyncTask task = asyncTaskMapper.findByTaskCode(taskCode);
        if (task == null) {
            throw new MetaServiceException("Async task not found: " + taskCode);
        }
        if (!task.isCancellable()) {
            throw new MetaServiceException("Task cannot be cancelled in status: " + task.getStatus());
        }

        task.setStatus(AsyncTask.STATUS_CANCELLED);
        task.setCancelledAt(Instant.now());
        asyncTaskMapper.updateById(task);

        // Interrupt the running thread if applicable
        Thread taskThread = runningTaskThreads.remove(taskCode);
        if (taskThread != null) {
            taskThread.interrupt();
            log.info("Interrupted running task thread: code={}", taskCode);
        }

        log.info("Async task cancelled: code={}", taskCode);
        return toDTO(task);
    }

    /**
     * Delete a completed/failed/cancelled task record.
     */
    public void deleteTask(String taskCode) {
        AsyncTask task = asyncTaskMapper.findByTaskCode(taskCode);
        if (task == null) {
            throw new MetaServiceException("Async task not found: " + taskCode);
        }
        if (!task.isTerminal()) {
            throw new MetaServiceException("Only terminal tasks can be deleted (current: " + task.getStatus() + ")");
        }
        asyncTaskMapper.deleteById(task.getId());
        log.info("Async task deleted: code={}", taskCode);
    }

    // ==================== Async Execution ====================

    /**
     * Execute a task asynchronously in the thread pool.
     * Note: tenantId is captured at submission time since @Async threads lack MetaContext.
     */
    @Async("asyncTaskExecutor")
    public void executeTaskAsync(Long taskId, Long tenantId) {
        AsyncTask task = asyncTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("Task not found for execution: taskId={}", taskId);
            return;
        }

        // Skip if already cancelled before execution started
        if (AsyncTask.STATUS_CANCELLED.equals(task.getStatus())) {
            log.info("Task already cancelled, skipping execution: code={}", task.getTaskCode());
            return;
        }

        // Mark as running
        task.setStatus(AsyncTask.STATUS_RUNNING);
        task.setStartedAt(Instant.now());
        asyncTaskMapper.updateById(task);

        // Register thread for cancellation support
        runningTaskThreads.put(task.getTaskCode(), Thread.currentThread());

        AsyncTaskExecutor executor = executorRegistry.get(task.getTaskType());
        if (executor == null) {
            failTask(task, "No executor registered for task type: " + task.getTaskType());
            runningTaskThreads.remove(task.getTaskCode());
            return;
        }

        try {
            // Create a progress callback that persists to DB
            AsyncTaskExecutor.ProgressCallback callback = (percentage, message) -> {
                asyncTaskMapper.updateProgress(task.getId(), Math.min(percentage, 99), message);
            };

            // Execute with timeout awareness
            AsyncTaskResult result = executor.execute(task.getInputParams(), callback);

            if (Thread.currentThread().isInterrupted()) {
                // Task was cancelled during execution
                task.setStatus(AsyncTask.STATUS_CANCELLED);
                task.setCancelledAt(Instant.now());
                asyncTaskMapper.updateById(task);
                log.info("Task execution interrupted (cancelled): code={}", task.getTaskCode());
                return;
            }

            if (result.isSuccess()) {
                task.setStatus(AsyncTask.STATUS_COMPLETED);
                task.setProgress(100);
                task.setProgressMessage("Completed");
                task.setResultData(result.getData());
                task.setCompletedAt(Instant.now());
                asyncTaskMapper.updateById(task);
                log.info("Async task completed: code={}, type={}", task.getTaskCode(), task.getTaskType());
            } else {
                handleFailure(task, result.getErrorMessage());
            }

        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                task.setStatus(AsyncTask.STATUS_CANCELLED);
                task.setCancelledAt(Instant.now());
                asyncTaskMapper.updateById(task);
                log.info("Task execution interrupted by cancellation: code={}", task.getTaskCode());
            } else {
                log.error("Async task execution failed: code={}, type={}",
                        task.getTaskCode(), task.getTaskType(), e);
                handleFailure(task, e.getMessage());
            }
        } finally {
            runningTaskThreads.remove(task.getTaskCode());
        }
    }

    // ==================== Scheduled Cleanup ====================

    /**
     * Clean up old completed/failed tasks (retention: 7 days).
     * Runs every 6 hours.
     */
    @Scheduled(fixedDelay = 21600000) // 6 hours
    public void cleanupOldTasks() {
        Instant cutoff = Instant.now().minusSeconds(7 * 24 * 3600);
        LambdaQueryWrapper<AsyncTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(AsyncTask::getStatus,
                AsyncTask.STATUS_COMPLETED, AsyncTask.STATUS_FAILED, AsyncTask.STATUS_CANCELLED);
        wrapper.lt(AsyncTask::getCreatedAt, cutoff);

        int deleted = asyncTaskMapper.delete(wrapper);
        if (deleted > 0) {
            log.info("Cleaned up {} old async tasks (older than 7 days)", deleted);
        }
    }

    // ==================== Private Helpers ====================

    /**
     * Handle task failure with optional retry.
     */
    private void handleFailure(AsyncTask task, String errorMessage) {
        task.setRetryCount(task.getRetryCount() + 1);

        if (task.getRetryCount() < task.getMaxRetries()) {
            // Retry: reset to PENDING for re-execution
            log.warn("Async task failed, scheduling retry {}/{}: code={}, error={}",
                    task.getRetryCount(), task.getMaxRetries(), task.getTaskCode(), errorMessage);
            task.setStatus(AsyncTask.STATUS_PENDING);
            task.setProgressMessage("Retry " + task.getRetryCount() + "/" + task.getMaxRetries());
            asyncTaskMapper.updateById(task);

            // Re-submit for execution via self-proxy
            self.executeTaskAsync(task.getId(), task.getTenantId());
        } else {
            failTask(task, errorMessage);
        }
    }

    private void failTask(AsyncTask task, String errorMessage) {
        task.setStatus(AsyncTask.STATUS_FAILED);
        task.setErrorMessage(errorMessage);
        task.setCompletedAt(Instant.now());
        asyncTaskMapper.updateById(task);
        log.error("Async task failed permanently: code={}, error={}", task.getTaskCode(), errorMessage);
    }

    private AsyncTaskDTO toDTO(AsyncTask entity) {
        AsyncTaskDTO dto = new AsyncTaskDTO();
        dto.setTaskCode(entity.getTaskCode());
        dto.setTaskType(entity.getTaskType());
        dto.setTaskName(entity.getTaskName());
        dto.setStatus(entity.getStatus());
        dto.setPriority(entity.getPriority());
        dto.setProgress(entity.getProgress());
        dto.setProgressMessage(entity.getProgressMessage());
        dto.setInputParams(entity.getInputParams());
        dto.setResultData(entity.getResultData());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setRetryCount(entity.getRetryCount());
        dto.setMaxRetries(entity.getMaxRetries());
        dto.setCreatedAt(toLocalDateTime(entity.getCreatedAt()));
        dto.setStartedAt(toLocalDateTime(entity.getStartedAt()));
        dto.setCompletedAt(toLocalDateTime(entity.getCompletedAt()));
        dto.setCancelledAt(toLocalDateTime(entity.getCancelledAt()));
        dto.setTimeoutSeconds(entity.getTimeoutSeconds());
        return dto;
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) return null;
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
