package com.auraboot.framework.scheduler.service.impl;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.scheduler.dto.ScheduledTaskCreateRequest;
import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.scheduler.entity.ScheduledTaskLog;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskMapper;
import com.auraboot.framework.scheduler.service.ScheduledTaskService;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import com.auraboot.framework.scheduler.service.TaskExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Implementation of ScheduledTaskService.
 *
 * @since 5.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledTaskServiceImpl implements ScheduledTaskService {

    private final ScheduledTaskMapper taskMapper;
    private final SchedulerEngine schedulerEngine;
    private final TaskExecutor taskExecutor;

    @Override
    @Transactional
    public ScheduledTask create(ScheduledTaskCreateRequest request) {
        ScheduledTask entity = new ScheduledTask();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setTaskType(request.getTaskType());
        entity.setCronExpression(request.getCronExpression());
        entity.setTimezone(request.getTimezone());
        entity.setIntervalMs(request.getIntervalMs());
        entity.setHandlerBean(request.getHandlerBean());
        entity.setHandlerMethod(request.getHandlerMethod());
        entity.setParams(request.getParams());
        entity.setMaxRetries(request.getMaxRetries());
        entity.setTimeoutMs(request.getTimeoutMs());
        entity.setEnabled(request.isEnabled());

        taskMapper.insert(entity);

        if (entity.getEnabled()) {
            schedulerEngine.scheduleTask(entity);
        }

        log.info("Created scheduled task: pid={}, name={}, type={}", entity.getPid(), entity.getName(), entity.getTaskType());
        return entity;
    }

    @Override
    public ScheduledTask getByPid(String pid) {
        return taskMapper.findByPid(pid);
    }

    @Override
    public List<ScheduledTask> listAll() {
        return taskMapper.findAll();
    }

    @Override
    @Transactional
    public ScheduledTask update(String pid, ScheduledTaskCreateRequest request) {
        ScheduledTask existing = taskMapper.findByPid(pid);
        if (existing == null) {
            throw new IllegalArgumentException("Scheduled task not found: " + pid);
        }

        existing.setName(request.getName());
        existing.setDescription(request.getDescription());
        existing.setTaskType(request.getTaskType());
        existing.setCronExpression(request.getCronExpression());
        existing.setTimezone(request.getTimezone());
        existing.setIntervalMs(request.getIntervalMs());
        existing.setHandlerBean(request.getHandlerBean());
        existing.setHandlerMethod(request.getHandlerMethod());
        existing.setParams(request.getParams());
        existing.setMaxRetries(request.getMaxRetries());
        existing.setTimeoutMs(request.getTimeoutMs());
        existing.setEnabled(request.isEnabled());

        taskMapper.updateById(existing);

        // Re-schedule
        schedulerEngine.unscheduleTask(pid);
        if (existing.getEnabled()) {
            schedulerEngine.scheduleTask(existing);
        }

        log.info("Updated scheduled task: pid={}", pid);
        return existing;
    }

    @Override
    @Transactional
    public void delete(String pid) {
        schedulerEngine.unscheduleTask(pid);
        taskMapper.deleteByPid(pid);
        log.info("Deleted scheduled task: pid={}", pid);
    }

    @Override
    @Transactional
    public void enable(String pid) {
        taskMapper.updateEnabled(pid, true);
        ScheduledTask task = taskMapper.findByPid(pid);
        if (task != null) {
            schedulerEngine.scheduleTask(task);
        }
        log.info("Enabled scheduled task: pid={}", pid);
    }

    @Override
    @Transactional
    public void disable(String pid) {
        taskMapper.updateEnabled(pid, false);
        schedulerEngine.unscheduleTask(pid);
        log.info("Disabled scheduled task: pid={}", pid);
    }

    @Override
    public ScheduledTaskLog triggerManually(String pid) {
        ScheduledTask task = taskMapper.findByPid(pid);
        if (task == null) {
            throw new IllegalArgumentException("Scheduled task not found: " + pid);
        }
        log.info("Manually triggering task: pid={}, name={}", pid, task.getName());
        taskExecutor.execute(task);
        return null; // Log is created within the executor
    }
}
