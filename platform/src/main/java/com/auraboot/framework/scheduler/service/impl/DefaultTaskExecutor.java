package com.auraboot.framework.scheduler.service.impl;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.scheduler.entity.ScheduledTaskLog;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskLogMapper;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskMapper;
import com.auraboot.framework.scheduler.service.TaskExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.concurrent.*;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Default task executor that uses reflection to invoke handler beans.
 *
 * @since 5.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultTaskExecutor implements TaskExecutor {

    private final ApplicationContext applicationContext;
    private final ScheduledTaskLogMapper logMapper;
    private final ScheduledTaskMapper taskMapper;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @Override
    public void execute(ScheduledTask task) {
        ScheduledTaskLog logEntry = new ScheduledTaskLog();
        logEntry.setTenantId(task.getTenantId());
        logEntry.setTaskPid(task.getPid());
        logEntry.setStatus(StatusConstants.RUNNING);
        logEntry.setStartedAt(Instant.now());
        logEntry.setTriggerType("scheduled");
        logEntry.setRetryCount(0);
        logMapper.insert(logEntry);

        int retries = 0;
        int maxRetries = task.getMaxRetries() != null ? task.getMaxRetries() : 0;

        while (retries <= maxRetries) {
            try {
                long timeout = task.getTimeoutMs() != null ? task.getTimeoutMs() : 300000L;
                Future<?> future = executorService.submit(() -> invokeHandler(task));
                future.get(timeout, TimeUnit.MILLISECONDS);

                // Success
                Instant now = Instant.now();
                logEntry.setStatus(StatusConstants.SUCCESS);
                logEntry.setFinishedAt(now);
                logEntry.setDurationMs(now.toEpochMilli() - logEntry.getStartedAt().toEpochMilli());
                logEntry.setRetryCount(retries);
                logMapper.updateById(logEntry);

                taskMapper.updateRunTimes(task.getPid(), now, null);
                return;

            } catch (TimeoutException e) {
                logEntry.setStatus("timeout");
                logEntry.setErrorMessage("Task timed out after " + task.getTimeoutMs() + "ms");
                break;
            } catch (Exception e) {
                retries++;
                String errorMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                if (retries > maxRetries) {
                    logEntry.setStatus(StatusConstants.FAILED);
                    logEntry.setErrorMessage(errorMsg);
                    break;
                }
                log.warn("Task {} failed (retry {}/{}): {}", task.getPid(), retries, maxRetries, errorMsg);
            }
        }

        // Update log entry on failure
        Instant now = Instant.now();
        logEntry.setFinishedAt(now);
        logEntry.setDurationMs(now.toEpochMilli() - logEntry.getStartedAt().toEpochMilli());
        logEntry.setRetryCount(retries);
        logMapper.updateById(logEntry);

        taskMapper.updateRunTimes(task.getPid(), now, null);
    }

    /**
     * Allowed handler bean name prefix. Only beans whose name starts with one of these
     * prefixes can be invoked as scheduled task handlers — prevents arbitrary bean invocation.
     */
    private static final java.util.Set<String> ALLOWED_HANDLER_PREFIXES = java.util.Set.of(
            "scheduledTask", "taskHandler", "jobHandler", "cronHandler",
            "idempotent", "cleanup", "sync", "digest", "sla", "decisionAlarm", "invariantAlarm"
    );

    private void invokeHandler(ScheduledTask task) {
        String beanName = task.getHandlerBean();
        String methodName = task.getHandlerMethod() != null ? task.getHandlerMethod() : "execute";

        // Security: only allow whitelisted handler bean prefixes to prevent arbitrary bean invocation
        boolean allowed = ALLOWED_HANDLER_PREFIXES.stream()
                .anyMatch(prefix -> beanName.startsWith(prefix));
        if (!allowed) {
            throw new BusinessException("Scheduled task handler not in allowlist: " + beanName
                    + ". Handler bean names must start with one of: " + ALLOWED_HANDLER_PREFIXES);
        }

        Object bean = applicationContext.getBean(beanName);
        try {
            Method method = bean.getClass().getMethod(methodName);
            method.invoke(bean);
        } catch (NoSuchMethodException e) {
            // Try with task parameter
            try {
                Method method = bean.getClass().getMethod(methodName, ScheduledTask.class);
                method.invoke(bean, task);
            } catch (Exception ex) {
                throw new BusinessException("Failed to invoke handler: " + beanName + "." + methodName, ex);
            }
        } catch (Exception e) {
            throw new BusinessException("Failed to invoke handler: " + beanName + "." + methodName, e);
        }
    }
}
