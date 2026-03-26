package com.auraboot.framework.scheduler.service;

import com.auraboot.framework.scheduler.entity.ScheduledTask;

/**
 * Internal scheduling engine that manages task registration with the scheduler.
 *
 * @since 5.1.0
 */
public interface SchedulerEngine {

    void start();

    void stop();

    void reload();

    void scheduleTask(ScheduledTask task);

    void unscheduleTask(String taskPid);
}
