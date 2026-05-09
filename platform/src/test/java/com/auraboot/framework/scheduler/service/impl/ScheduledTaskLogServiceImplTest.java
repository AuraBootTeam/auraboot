package com.auraboot.framework.scheduler.service.impl;

import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.scheduler.dto.TaskLogQueryRequest;
import com.auraboot.framework.scheduler.entity.ScheduledTaskLog;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledTaskLogServiceImplTest {

    @Mock
    private ScheduledTaskLogMapper logMapper;

    @InjectMocks
    private ScheduledTaskLogServiceImpl service;

    @Test
    void getByTaskPid_clampsLimitToMax100() {
        when(logMapper.findByTaskPid("p1", 100)).thenReturn(List.of(new ScheduledTaskLog()));
        List<ScheduledTaskLog> out = service.getByTaskPid("p1", 9999);
        assertThat(out).hasSize(1);
        verify(logMapper).findByTaskPid("p1", 100);
    }

    @Test
    void getByTaskPid_smallLimitPassedThrough() {
        when(logMapper.findByTaskPid("p1", 5)).thenReturn(List.of());
        service.getByTaskPid("p1", 5);
        verify(logMapper).findByTaskPid("p1", 5);
    }

    @Test
    void getLatest_delegates() {
        ScheduledTaskLog log = new ScheduledTaskLog();
        when(logMapper.findLatest("p1")).thenReturn(log);
        assertThat(service.getLatest("p1")).isSameAs(log);
    }

    @Test
    void query_byTaskPid_usesByPidQueries() {
        TaskLogQueryRequest req = new TaskLogQueryRequest();
        req.setTaskPid("pid-x");
        req.setPageNum(2);
        req.setPageSize(10);

        when(logMapper.findByTaskPidPaged("pid-x", 10, 10)).thenReturn(List.of(new ScheduledTaskLog()));
        when(logMapper.countByTaskPid("pid-x")).thenReturn(100L);

        PaginationResult<ScheduledTaskLog> result = service.query(req);
        assertThat(result.getTotal()).isEqualTo(100L);
        assertThat(result.getRecords()).hasSize(1);
    }

    @Test
    void query_blankTaskPid_usesFindAll() {
        TaskLogQueryRequest req = new TaskLogQueryRequest();
        req.setTaskPid("   ");
        req.setPageNum(1);
        req.setPageSize(20);
        when(logMapper.findAll(20, 0)).thenReturn(List.of());
        when(logMapper.countAll()).thenReturn(0L);

        service.query(req);
        verify(logMapper).findAll(20, 0);
        verify(logMapper).countAll();
    }

    @Test
    void query_clampsPageNumAndPageSize() {
        TaskLogQueryRequest req = new TaskLogQueryRequest();
        req.setPageNum(0);
        req.setPageSize(500);
        when(logMapper.findAll(eq(100), eq(0))).thenReturn(List.of());
        when(logMapper.countAll()).thenReturn(0L);
        service.query(req);
        verify(logMapper).findAll(100, 0);
    }
}
