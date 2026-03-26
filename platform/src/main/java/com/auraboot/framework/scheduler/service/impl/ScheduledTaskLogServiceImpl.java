package com.auraboot.framework.scheduler.service.impl;

import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.scheduler.dto.TaskLogQueryRequest;
import com.auraboot.framework.scheduler.entity.ScheduledTaskLog;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskLogMapper;
import com.auraboot.framework.scheduler.service.ScheduledTaskLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Implementation of ScheduledTaskLogService.
 *
 * @since 5.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledTaskLogServiceImpl implements ScheduledTaskLogService {

    private final ScheduledTaskLogMapper logMapper;

    @Override
    public List<ScheduledTaskLog> getByTaskPid(String taskPid, int limit) {
        return logMapper.findByTaskPid(taskPid, Math.min(limit, 100));
    }

    @Override
    public ScheduledTaskLog getLatest(String taskPid) {
        return logMapper.findLatest(taskPid);
    }

    @Override
    public PaginationResult<ScheduledTaskLog> query(TaskLogQueryRequest request) {
        int pageNum = Math.max(1, request.getPageNum());
        int pageSize = Math.min(100, Math.max(1, request.getPageSize()));
        int offset = (pageNum - 1) * pageSize;

        List<ScheduledTaskLog> records;
        long total;

        if (request.getTaskPid() != null && !request.getTaskPid().isBlank()) {
            records = logMapper.findByTaskPidPaged(request.getTaskPid(), pageSize, offset);
            total = logMapper.countByTaskPid(request.getTaskPid());
        } else {
            records = logMapper.findAll(pageSize, offset);
            total = logMapper.countAll();
        }

        return PaginationResult.of(records, total, pageNum, pageSize);
    }
}
