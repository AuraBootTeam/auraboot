package com.auraboot.framework.scheduler.service;

import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.scheduler.dto.TaskLogQueryRequest;
import com.auraboot.framework.scheduler.entity.ScheduledTaskLog;

import java.util.List;

/**
 * Service for querying task execution logs.
 *
 * @since 5.1.0
 */
public interface ScheduledTaskLogService {

    List<ScheduledTaskLog> getByTaskPid(String taskPid, int limit);

    ScheduledTaskLog getLatest(String taskPid);

    PaginationResult<ScheduledTaskLog> query(TaskLogQueryRequest request);
}
