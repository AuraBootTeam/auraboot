package com.auraboot.framework.scheduler.service;

import com.auraboot.framework.scheduler.dto.ScheduledTaskCreateRequest;
import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.scheduler.entity.ScheduledTaskLog;

import java.util.List;

/**
 * Service for managing scheduled task definitions.
 *
 * @since 5.1.0
 */
public interface ScheduledTaskService {

    ScheduledTask create(ScheduledTaskCreateRequest request);

    ScheduledTask getByPid(String pid);

    List<ScheduledTask> listAll();

    ScheduledTask update(String pid, ScheduledTaskCreateRequest request);

    void delete(String pid);

    void enable(String pid);

    void disable(String pid);

    /**
     * Manually trigger a task execution.
     */
    ScheduledTaskLog triggerManually(String pid);
}
