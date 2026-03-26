package com.auraboot.framework.scheduler.service;

import com.auraboot.framework.scheduler.entity.ScheduledTask;

/**
 * Interface for executing a scheduled task.
 *
 * @since 5.1.0
 */
public interface TaskExecutor {

    /**
     * Execute the given task.
     */
    void execute(ScheduledTask task);
}
