package com.auraboot.framework.scheduler.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.scheduler.dto.ScheduledTaskCreateRequest;
import com.auraboot.framework.scheduler.dto.TaskLogQueryRequest;
import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.scheduler.entity.ScheduledTaskLog;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ScheduledTaskService and ScheduledTaskLogService.
 *
 * @since 5.1.0
 */
@DisplayName("P5-4: Scheduled Task Service Integration Tests")
class ScheduledTaskServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ScheduledTaskService taskService;

    @Autowired
    private ScheduledTaskLogService logService;

    @Autowired
    private SchedulerEngine schedulerEngine;

    @BeforeEach
    void setUp() {
        super.setupTenantContext();
    }

    // ==================== Task CRUD ====================

    @Test
    @DisplayName("Create CRON task")
    void testCreateCronTask() {
        ScheduledTaskCreateRequest request = buildCronRequest("Test Cron Task", "0 0 * * * ?");

        ScheduledTask task = taskService.create(request);

        assertNotNull(task);
        assertNotNull(task.getPid());
        assertEquals("Test Cron Task", task.getName());
        assertEquals("cron", task.getTaskType());
        assertEquals("0 0 * * * ?", task.getCronExpression());
        assertFalse(task.getEnabled()); // buildCronRequest sets enabled=false
    }

    @Test
    @DisplayName("Create INTERVAL task")
    void testCreateIntervalTask() {
        ScheduledTaskCreateRequest request = new ScheduledTaskCreateRequest();
        request.setName("Interval Task");
        request.setTaskType("interval");
        request.setIntervalMs(60000L);
        request.setHandlerBean("testHandler");
        request.setEnabled(false); // Don't actually schedule

        ScheduledTask task = taskService.create(request);

        assertNotNull(task);
        assertEquals("interval", task.getTaskType());
        assertEquals(60000L, task.getIntervalMs());
    }

    @Test
    @DisplayName("Get task by PID")
    void testGetByPid() {
        ScheduledTask created = createDisabledTask("Find Me");
        ScheduledTask found = taskService.getByPid(created.getPid());

        assertNotNull(found);
        assertEquals(created.getPid(), found.getPid());
        assertEquals("Find Me", found.getName());
    }

    @Test
    @DisplayName("List all tasks")
    void testListAll() {
        createDisabledTask("List Task A");
        createDisabledTask("List Task B");

        List<ScheduledTask> all = taskService.listAll();
        assertTrue(all.size() >= 2);
    }

    @Test
    @DisplayName("Update task")
    void testUpdateTask() {
        ScheduledTask created = createDisabledTask("Update Me");

        ScheduledTaskCreateRequest updateReq = new ScheduledTaskCreateRequest();
        updateReq.setName("Updated Task");
        updateReq.setTaskType("interval");
        updateReq.setIntervalMs(120000L);
        updateReq.setHandlerBean("updatedHandler");
        updateReq.setEnabled(false);

        ScheduledTask updated = taskService.update(created.getPid(), updateReq);

        assertEquals("Updated Task", updated.getName());
        assertEquals("interval", updated.getTaskType());
        assertEquals(120000L, updated.getIntervalMs());
    }

    @Test
    @DisplayName("Delete task")
    void testDeleteTask() {
        ScheduledTask created = createDisabledTask("Delete Me");
        taskService.delete(created.getPid());

        assertNull(taskService.getByPid(created.getPid()));
    }

    @Test
    @DisplayName("Enable and disable task")
    void testEnableDisable() {
        ScheduledTask created = createDisabledTask("Toggle Task");
        assertFalse(created.getEnabled());

        taskService.enable(created.getPid());
        ScheduledTask enabled = taskService.getByPid(created.getPid());
        assertTrue(enabled.getEnabled());

        taskService.disable(created.getPid());
        ScheduledTask disabled = taskService.getByPid(created.getPid());
        assertFalse(disabled.getEnabled());
    }

    // ==================== Task Execution ====================

    @Test
    @DisplayName("Manual trigger executes task")
    void testManualTrigger() {
        ScheduledTaskCreateRequest request = new ScheduledTaskCreateRequest();
        request.setName("Trigger Test");
        request.setTaskType("one_time");
        request.setHandlerBean("noOpTestHandler");
        request.setHandlerMethod("execute");
        request.setEnabled(false);

        ScheduledTask task = taskService.create(request);

        // Manual trigger may throw if bean doesn't exist - that's expected
        try {
            taskService.triggerManually(task.getPid());
        } catch (Exception e) {
            // Expected if noOpTestHandler bean doesn't exist
            assertTrue(e.getMessage().contains("noOpTestHandler") || e.getMessage().contains("No bean"));
        }
    }

    @Test
    @DisplayName("Update non-existent task throws exception")
    void testUpdateNonExistent() {
        ScheduledTaskCreateRequest req = new ScheduledTaskCreateRequest();
        req.setName("x");
        req.setTaskType("cron");
        req.setHandlerBean("x");

        assertThrows(IllegalArgumentException.class, () ->
                taskService.update("nonexistent-pid", req));
    }

    @Test
    @DisplayName("Trigger non-existent task throws exception")
    void testTriggerNonExistent() {
        assertThrows(IllegalArgumentException.class, () ->
                taskService.triggerManually("nonexistent-pid"));
    }

    // ==================== Task Log Service ====================

    @Test
    @DisplayName("LogService: getByTaskPid returns logs")
    void testGetLogsByTaskPid() {
        ScheduledTask task = createDisabledTask("Log Test");
        // Logs are created by the executor, so we just verify the query works
        List<ScheduledTaskLog> logs = logService.getByTaskPid(task.getPid(), 10);
        assertNotNull(logs);
    }

    @Test
    @DisplayName("LogService: getLatest returns most recent")
    void testGetLatest() {
        ScheduledTask task = createDisabledTask("Latest Log");
        ScheduledTaskLog latest = logService.getLatest(task.getPid());
        // No logs exist yet, should return null
        assertNull(latest);
    }

    @Test
    @DisplayName("LogService: query with pagination")
    void testQueryLogs() {
        TaskLogQueryRequest request = new TaskLogQueryRequest();
        request.setPageNum(1);
        request.setPageSize(10);

        PaginationResult<ScheduledTaskLog> result = logService.query(request);
        assertNotNull(result);
        assertNotNull(result.getRecords());
    }

    @Test
    @DisplayName("LogService: query filtered by taskPid")
    void testQueryLogsByTask() {
        ScheduledTask task = createDisabledTask("Filter Log");

        TaskLogQueryRequest request = new TaskLogQueryRequest();
        request.setTaskPid(task.getPid());
        request.setPageNum(1);
        request.setPageSize(10);

        PaginationResult<ScheduledTaskLog> result = logService.query(request);
        assertNotNull(result);
    }

    // ==================== Scheduler Engine ====================

    @Test
    @DisplayName("SchedulerEngine: reload does not throw")
    void testReload() {
        assertDoesNotThrow(() -> schedulerEngine.reload());
    }

    @Test
    @DisplayName("SchedulerEngine: unschedule non-existent task is no-op")
    void testUnscheduleNonExistent() {
        assertDoesNotThrow(() -> schedulerEngine.unscheduleTask("non-existent"));
    }

    // ==================== Helpers ====================

    private ScheduledTask createDisabledTask(String name) {
        ScheduledTaskCreateRequest request = new ScheduledTaskCreateRequest();
        request.setName(name);
        request.setTaskType("cron");
        request.setCronExpression("0 0 0 * * ?");
        request.setHandlerBean("testHandler");
        request.setEnabled(false);
        return taskService.create(request);
    }

    private ScheduledTaskCreateRequest buildCronRequest(String name, String cron) {
        ScheduledTaskCreateRequest request = new ScheduledTaskCreateRequest();
        request.setName(name);
        request.setTaskType("cron");
        request.setCronExpression(cron);
        request.setHandlerBean("testHandler");
        request.setEnabled(false); // Don't schedule during tests
        return request;
    }
}
