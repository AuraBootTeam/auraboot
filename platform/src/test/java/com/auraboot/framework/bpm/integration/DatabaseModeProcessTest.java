package com.auraboot.framework.bpm.integration;

import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.assembly.ProcessDefinition;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import com.auraboot.smart.framework.engine.service.command.ProcessCommandService;
import com.auraboot.smart.framework.engine.service.command.RepositoryCommandService;
import com.auraboot.smart.framework.engine.service.command.TaskCommandService;
import com.auraboot.smart.framework.engine.service.query.ProcessQueryService;
import com.auraboot.smart.framework.engine.service.param.query.PendingTaskQueryParam;
import com.auraboot.smart.framework.engine.service.query.TaskQueryService;
import com.auraboot.framework.application.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for SmartEngine Database Mode.
 * Verifies that process definitions deploy, instances start,
 * tasks are created, and data persists to PostgreSQL.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@DisplayName("SmartEngine Database Mode Process Tests")
class DatabaseModeProcessTest {

    @Autowired
    private SmartEngine smartEngine;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RepositoryCommandService repositoryCommandService;
    private ProcessCommandService processCommandService;
    private ProcessQueryService processQueryService;
    private TaskCommandService taskCommandService;
    private TaskQueryService taskQueryService;

    @BeforeEach
    void setUp() {
        // Clean SmartEngine tables to avoid PK collisions from previous runs
        jdbcTemplate.execute("DELETE FROM se_task_assignee_instance");
        jdbcTemplate.execute("DELETE FROM se_task_instance");
        jdbcTemplate.execute("DELETE FROM se_execution_instance");
        jdbcTemplate.execute("DELETE FROM se_activity_instance");
        jdbcTemplate.execute("DELETE FROM se_process_instance");
        jdbcTemplate.execute("DELETE FROM se_deployment_instance");

        repositoryCommandService = smartEngine.getRepositoryCommandService();
        processCommandService = smartEngine.getProcessCommandService();
        processQueryService = smartEngine.getProcessQueryService();
        taskCommandService = smartEngine.getTaskCommandService();
        taskQueryService = smartEngine.getTaskQueryService();
    }

    @Test
    @DisplayName("Deploy a BPMN process definition")
    void shouldDeployProcessDefinition() {
        var source = repositoryCommandService.deploy(
                "smart-engine/simple-approval.bpmn20.xml");

        assertNotNull(source);
        ProcessDefinition definition = source.getFirstProcessDefinition();
        assertNotNull(definition);
        assertEquals("simple-approval", definition.getId());
    }

    @Test
    @DisplayName("Start a process instance and create user task")
    void shouldStartProcessAndCreateTask() {
        repositoryCommandService.deploy(
                "smart-engine/simple-approval.bpmn20.xml");

        Map<String, Object> variables = new HashMap<>();
        variables.put("tenantId", "test-tenant");
        variables.put("startUserId", "user1");

        ProcessInstance processInstance = processCommandService.start(
                "simple-approval", "1", variables);

        assertNotNull(processInstance);
        assertNotNull(processInstance.getInstanceId());

        ProcessInstance found = processQueryService.findById(
                processInstance.getInstanceId(), null);
        assertNotNull(found);

        PendingTaskQueryParam taskQuery = new PendingTaskQueryParam();
        taskQuery.setAssigneeUserId("testuser1");
        List<TaskInstance> tasks = taskQueryService
                .findPendingTaskList(taskQuery);
        assertFalse(tasks.isEmpty(), "Should have pending tasks");
    }

    @Test
    @DisplayName("Complete a user task and finish the process")
    void shouldCompleteTaskAndFinishProcess() {
        repositoryCommandService.deploy(
                "smart-engine/simple-approval.bpmn20.xml");

        Map<String, Object> variables = new HashMap<>();
        ProcessInstance processInstance = processCommandService.start(
                "simple-approval", "1", variables);

        PendingTaskQueryParam taskQuery = new PendingTaskQueryParam();
        taskQuery.setAssigneeUserId("testuser1");
        List<TaskInstance> tasks = taskQueryService
                .findPendingTaskList(taskQuery);
        assertFalse(tasks.isEmpty());

        TaskInstance task = tasks.get(0);

        Map<String, Object> completeVars = new HashMap<>();
        completeVars.put("approved", true);
        taskCommandService.complete(task.getInstanceId(), completeVars);

        ProcessInstance completed = processQueryService.findById(
                processInstance.getInstanceId(), null);
        assertNotNull(completed);
    }
}
