package com.auraboot.framework.scheduler.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.scheduler.dto.TaskLogQueryRequest;
import com.auraboot.framework.scheduler.entity.ScheduledTaskLog;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * ScheduledTaskLogService integration tests.
 *
 * <p>Covers:
 * <ul>
 *   <li>SL-01: getByTaskPid returns logs for the task</li>
 *   <li>SL-02: getLatest returns most recent log</li>
 *   <li>SL-03: query paginated returns results</li>
 *   <li>SL-04: query filtered by taskPid returns correct subset</li>
 *   <li>SL-05: getByTaskPid for unknown taskPid returns empty list</li>
 * </ul>
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScheduledTaskLogServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ScheduledTaskLogService scheduledTaskLogService;

    @Autowired
    private ScheduledTaskLogMapper scheduledTaskLogMapper;

    private final String testTaskPid = "test-task-" + System.currentTimeMillis();

    @BeforeAll
    public void insertTestLogs() {
        // Insert two test log entries via mapper (not going through service creation)
        insertLog(testTaskPid, "success", 100L, null);
        insertLog(testTaskPid, "failure", 200L, "DB timeout");
        log.info("Inserted test logs for task={}", testTaskPid);
    }

    // ==================== SL-01: getByTaskPid ====================

    @Test
    @Order(1)
    @DisplayName("SL-01: getByTaskPid returns logs for the task")
    void getByTaskPid_returnsLogs() {
        List<ScheduledTaskLog> logs = scheduledTaskLogService.getByTaskPid(testTaskPid, 10);

        assertThat(logs).isNotNull().hasSize(2);
        logs.forEach(l -> assertThat(l.getTaskPid()).isEqualTo(testTaskPid));
    }

    @Test
    @Order(2)
    @DisplayName("SL-02: getLatest returns the most recent log")
    void getLatest_returnsMostRecentLog() {
        ScheduledTaskLog latest = scheduledTaskLogService.getLatest(testTaskPid);

        assertThat(latest).isNotNull();
        assertThat(latest.getTaskPid()).isEqualTo(testTaskPid);
    }

    @Test
    @Order(3)
    @DisplayName("SL-03: query paginated returns results")
    void query_paginated_returnsResults() {
        TaskLogQueryRequest request = new TaskLogQueryRequest();
        request.setPageNum(1);
        request.setPageSize(10);

        PaginationResult<ScheduledTaskLog> result = scheduledTaskLogService.query(request);

        assertThat(result).isNotNull();
        assertThat(result.getTotal()).isGreaterThan(0);
        assertThat(result.getRecords()).isNotEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("SL-04: query filtered by taskPid returns correct subset")
    void query_filteredByTaskPid_returnsSubset() {
        TaskLogQueryRequest request = new TaskLogQueryRequest();
        request.setPageNum(1);
        request.setPageSize(10);
        request.setTaskPid(testTaskPid);

        PaginationResult<ScheduledTaskLog> result = scheduledTaskLogService.query(request);

        assertThat(result).isNotNull();
        assertThat(result.getTotal()).isEqualTo(2);
        result.getRecords().forEach(l -> assertThat(l.getTaskPid()).isEqualTo(testTaskPid));
    }

    @Test
    @Order(5)
    @DisplayName("SL-05: getByTaskPid for unknown taskPid returns empty list")
    void getByTaskPid_unknownTask_returnsEmpty() {
        List<ScheduledTaskLog> logs = scheduledTaskLogService.getByTaskPid(
                "nonexistent-task-xyz-999", 10);

        assertThat(logs).isNotNull().isEmpty();
    }

    // ==================== helper ====================

    private void insertLog(String taskPid, String status, long durationMs, String errorMessage) {
        ScheduledTaskLog logEntry = new ScheduledTaskLog();
        logEntry.setTaskPid(taskPid);
        logEntry.setStatus(status);
        logEntry.setStartedAt(Instant.now().minusMillis(durationMs));
        logEntry.setFinishedAt(Instant.now());
        logEntry.setDurationMs(durationMs);
        logEntry.setErrorMessage(errorMessage);
        logEntry.setRetryCount(0);
        logEntry.setTriggerType("scheduled");
        scheduledTaskLogMapper.insert(logEntry);
    }
}
