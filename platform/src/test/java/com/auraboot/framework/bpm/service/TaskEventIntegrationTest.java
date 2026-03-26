package com.auraboot.framework.bpm.service;

import com.alibaba.smart.framework.engine.SmartEngine;
import com.alibaba.smart.framework.engine.model.instance.ProcessInstance;
import com.alibaba.smart.framework.engine.model.instance.TaskInstance;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.BpmTestHelper;
import com.auraboot.framework.bpm.event.EventBusService;
import com.auraboot.framework.bpm.entity.EventLogEntity;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the task event system.
 *
 * <p>Verifies the end-to-end flow:
 * SmartEngine task lifecycle → TaskEventPublisher SPI → AuraTaskEventPublisher
 * → EventBusService → ab_event_log table
 *
 * <p>Covered scenarios:
 * 1. Starting a process with a UserTask fires a {@code task_assigned} event
 * 2. Completing a task fires a {@code task_completed} event
 * 3. Transferring a task fires a {@code task_transferred} event with
 *    fromUserId / toUserId in the payload
 *
 * <p><b>Note on transaction strategy:</b> These tests use
 * {@code @Transactional(propagation = NOT_SUPPORTED)} per-method to suspend
 * the outer BaseIntegrationTest transaction. This is necessary because
 * SmartEngine manages its own JDBC transaction context internally.
 * Without this, events written by SmartEngine would be in a different
 * transaction and not visible when queried. Tests clean up via {@code @AfterEach}.
 */
@Slf4j
@DisplayName("Task Event System - End-to-End Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TaskEventIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private ProcessEngineService engineService;

    @Autowired
    private SmartEngine smartEngine;

    @Autowired
    private EventBusService eventBusService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Tracks instanceIds created per test for cleanup in @AfterEach. */
    private String lastProcessInstanceId;

    @AfterEach
    void cleanUpSmartEngineData() {
        // Remove SmartEngine rows and event log rows for the test's process instance.
        // This is needed because these tests use NOT_SUPPORTED transactions
        // (to allow cross-transaction visibility) so @Rollback does not apply.
        if (lastProcessInstanceId != null) {
            try {
                jdbcTemplate.update("DELETE FROM ab_event_log WHERE instance_id = ?", lastProcessInstanceId);
                jdbcTemplate.update("DELETE FROM se_task_assignee_instance WHERE process_instance_id = ?", lastProcessInstanceId);
                jdbcTemplate.update("DELETE FROM se_task_instance WHERE process_instance_id = ?", lastProcessInstanceId);
                jdbcTemplate.update("DELETE FROM se_execution_instance WHERE process_instance_id = ?", lastProcessInstanceId);
                jdbcTemplate.update("DELETE FROM se_activity_instance WHERE process_instance_id = ?", lastProcessInstanceId);
                jdbcTemplate.update("DELETE FROM se_process_instance WHERE instance_id = ?", lastProcessInstanceId);
            } catch (Exception e) {
                log.warn("Cleanup failed for instanceId={}: {}", lastProcessInstanceId, e.getMessage());
            }
            lastProcessInstanceId = null;
        }
    }

    // ==================== Test 1: TASK_ASSIGNED on process start ====================

    @Test
    @Order(1)
    @DisplayName("EVT-01: Starting a process fires task_assigned event in event_log")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void evt01_startProcessFiresTaskAssignedEvent() {
        long ts = System.currentTimeMillis();
        String assigneeId = "user-evt01-" + ts;

        // Deploy BPMN with one userTask assigned to assigneeId
        String processKey = deploySimpleProcess("evt01", assigneeId);

        // Start process — triggers UserTaskBehavior → TaskEventPublisher → EventBusService
        ProcessInstance instance = engineService.startProcess(processKey, "biz-evt01-" + ts, new HashMap<>());
        assertThat(instance).as("ProcessInstance should be created").isNotNull();
        String instanceId = instance.getInstanceId();
        lastProcessInstanceId = instanceId;

        log.info("EVT-01: processKey={}, instanceId={}", processKey, instanceId);

        // Query events persisted for this process instance
        List<EventLogEntity> events = eventBusService.getEventsByInstance(instanceId);

        assertThat(events)
                .as("At least one event should be persisted for instance %s", instanceId)
                .isNotNull()
                .isNotEmpty();

        log.info("EVT-01: events found = {}", events.stream().map(EventLogEntity::getEventType).toList());

        List<EventLogEntity> assignedEvents = events.stream()
                .filter(e -> "task_assigned".equals(e.getEventType()))
                .toList();

        assertThat(assignedEvents)
                .as("task_assigned event must be in event_log after process start")
                .isNotEmpty();

        EventLogEntity assignedEvent = assignedEvents.get(0);
        assertThat(assignedEvent.getInstanceId()).isEqualTo(instanceId);
        assertThat(assignedEvent.getProcessKey()).isNotBlank();
        assertThat(assignedEvent.getPayload())
                .as("task_assigned payload must not be null")
                .isNotNull();
        assertThat(assignedEvent.getPayload()).containsKey("taskInstanceId");
        assertThat(assignedEvent.getStatus()).isEqualTo("published");

        log.info("EVT-01 PASSED: task_assigned event fired with payload={}", assignedEvent.getPayload());
    }

    // ==================== Test 2: TASK_COMPLETED ====================

    @Test
    @Order(2)
    @DisplayName("EVT-02: Completing a task fires task_completed event in event_log")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void evt02_completeTaskFiresTaskCompletedEvent() {
        long ts = System.currentTimeMillis();
        String assigneeId = "user-evt02-" + ts;

        String processKey = deploySimpleProcess("evt02", assigneeId);
        ProcessInstance instance = engineService.startProcess(processKey, "biz-evt02-" + ts, new HashMap<>());
        assertThat(instance).as("ProcessInstance should be created").isNotNull();
        String instanceId = instance.getInstanceId();
        lastProcessInstanceId = instanceId;

        // Find the pending task
        List<TaskInstance> pendingTasks = smartEngine.getTaskQueryService()
                .findAllPendingTaskList(instanceId, getTestTenant().getId().toString());

        assertThat(pendingTasks)
                .as("There should be at least one pending task after process start")
                .isNotNull()
                .isNotEmpty();

        TaskInstance task = pendingTasks.get(0);
        String taskId = task.getInstanceId();

        log.info("EVT-02: instanceId={}, taskId={}", instanceId, taskId);

        // Complete the task using SmartEngine directly
        Map<String, Object> vars = new HashMap<>();
        vars.put("decision", "approve");
        smartEngine.getTaskCommandService().complete(taskId, vars);

        // Verify task_completed event
        List<EventLogEntity> events = eventBusService.getEventsByInstance(instanceId);
        assertThat(events).isNotNull();
        log.info("EVT-02: all events = {}", events.stream().map(EventLogEntity::getEventType).toList());

        List<EventLogEntity> completedEvents = events.stream()
                .filter(e -> "task_completed".equals(e.getEventType()))
                .toList();

        assertThat(completedEvents)
                .as("task_completed event must be in event_log after task.complete()")
                .isNotEmpty();

        EventLogEntity completedEvent = completedEvents.get(0);
        assertThat(completedEvent.getInstanceId()).isEqualTo(instanceId);
        assertThat(completedEvent.getPayload())
                .as("task_completed payload must not be null")
                .isNotNull();
        assertThat(completedEvent.getPayload()).containsKey("taskInstanceId");
        assertThat(completedEvent.getStatus()).isEqualTo("published");

        log.info("EVT-02 PASSED: task_completed event fired with payload={}", completedEvent.getPayload());
    }

    // ==================== Test 3: TASK_TRANSFERRED ====================

    @Test
    @Order(3)
    @DisplayName("EVT-03: Transferring a task fires task_transferred event with fromUserId/toUserId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void evt03_transferTaskFiresTaskTransferredEvent() {
        long ts = System.currentTimeMillis();
        String fromUserId = "user-from-evt03-" + ts;
        String toUserId   = "user-to-evt03-"   + ts;

        // Deploy with fromUserId as the initial assignee
        String processKey = deploySimpleProcess("evt03", fromUserId);
        ProcessInstance instance = engineService.startProcess(processKey, "biz-evt03-" + ts, new HashMap<>());
        assertThat(instance).as("ProcessInstance should be created").isNotNull();
        String instanceId = instance.getInstanceId();
        lastProcessInstanceId = instanceId;

        // Find the pending task
        List<TaskInstance> pendingTasks = smartEngine.getTaskQueryService()
                .findAllPendingTaskList(instanceId, getTestTenant().getId().toString());

        assertThat(pendingTasks)
                .as("There should be at least one pending task after process start")
                .isNotNull()
                .isNotEmpty();

        TaskInstance task = pendingTasks.get(0);
        String taskId = task.getInstanceId();
        String tenantId = getTestTenant().getId().toString();

        log.info("EVT-03: instanceId={}, taskId={}, from={}, to={}", instanceId, taskId, fromUserId, toUserId);

        // Transfer the task (fires TASK_TRANSFERRED via DefaultTaskCommandService)
        smartEngine.getTaskCommandService().transferWithReason(
                taskId, fromUserId, toUserId, "Integration test transfer", tenantId);

        // Verify task_transferred event
        List<EventLogEntity> events = eventBusService.getEventsByInstance(instanceId);
        assertThat(events).isNotNull();
        log.info("EVT-03: all events = {}", events.stream().map(EventLogEntity::getEventType).toList());

        List<EventLogEntity> transferredEvents = events.stream()
                .filter(e -> "task_transferred".equals(e.getEventType()))
                .toList();

        assertThat(transferredEvents)
                .as("task_transferred event must be in event_log after transferWithReason()")
                .isNotEmpty();

        EventLogEntity transferredEvent = transferredEvents.get(0);
        assertThat(transferredEvent.getInstanceId()).isEqualTo(instanceId);
        assertThat(transferredEvent.getPayload())
                .as("task_transferred payload must not be null")
                .isNotNull();

        // Verify fromUserId / toUserId are captured in the payload
        Map<String, Object> payload = transferredEvent.getPayload();
        assertThat(payload).as("Payload must contain fromUserId").containsKey("fromUserId");
        assertThat(payload).as("Payload must contain toUserId").containsKey("toUserId");
        assertThat(payload.get("fromUserId")).isEqualTo(fromUserId);
        assertThat(payload.get("toUserId")).isEqualTo(toUserId);
        assertThat(transferredEvent.getStatus()).isEqualTo("published");

        log.info("EVT-03 PASSED: task_transferred event fired with payload={}", payload);
    }

    // ==================== Helper ====================

    /**
     * Deploy a simple single-userTask BPMN process using BpmTestHelper.
     * Uses the SIMPLE_APPROVAL_BPMN_TEMPLATE which contains one userTask
     * assigned to the provided assigneeId.
     *
     * @param suffix     unique suffix for this test (used in process key)
     * @param assigneeId the user that the userTask should be assigned to
     * @return the deployed processKey
     */
    private String deploySimpleProcess(String suffix, String assigneeId) {
        // Ensure MetaContext is set for tenant-aware operations
        MetaContext.setContext(
                getTestTenant().getId(),
                getTestUser().getId(),
                getTestUser().getPid(),
                getTestUser().getUserName()
        );
        return BpmTestHelper.createAndDeploy(deploymentService, suffix + "-" + System.currentTimeMillis(), assigneeId);
    }
}
