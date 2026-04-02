package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.chain.CommandChainDefinition.*;
import com.auraboot.framework.bpm.entity.ApprovalTask;
import com.auraboot.framework.bpm.entity.ChainExecution;
import com.auraboot.framework.bpm.mapper.ApprovalTaskMapper;
import com.auraboot.framework.bpm.mapper.ChainExecutionMapper;
import com.auraboot.framework.bpm.service.AssigneeResolverService;
import com.auraboot.framework.bpm.service.ExecutionLogService;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("deprecation")
class ApprovalChainExecutorTest {

    @Mock private ChainExecutionMapper chainExecutionMapper;
    @Mock private ApprovalTaskMapper approvalTaskMapper;
    @Mock private AssigneeResolverService assigneeResolverService;
    @Mock private CommandExecutor commandExecutor;
    @Mock private ExecutionLogService executionLogService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ApprovalChainExecutor executor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        executor = new ApprovalChainExecutor(
                chainExecutionMapper, approvalTaskMapper, assigneeResolverService,
                commandExecutor, executionLogService, eventPublisher, objectMapper);
    }

    @SuppressWarnings("unchecked")
    private QueryWrapper<ApprovalTask> anyApprovalTaskQuery() {
        return any(QueryWrapper.class);
    }

    @SuppressWarnings("unchecked")
    private UpdateWrapper<ApprovalTask> anyApprovalTaskUpdate() {
        return any(UpdateWrapper.class);
    }

    @SuppressWarnings("unchecked")
    private QueryWrapper<ChainExecution> anyChainExecutionQuery() {
        return any(QueryWrapper.class);
    }

    @SuppressWarnings("unchecked")
    private UpdateWrapper<ChainExecution> anyChainExecutionUpdate() {
        return any(UpdateWrapper.class);
    }

    // ==================== Helper methods ====================

    private CommandChainDefinition buildSimpleChainWithUserTask() {
        CommandChainDefinition chain = new CommandChainDefinition();
        chain.setProcessKey("test_approval");
        chain.setName("Test Approval");
        chain.setChainMode(ChainMode.APPROVAL);

        ChainNode start = new ChainNode();
        start.setId("start");
        start.setType("startEvent");

        ChainNode userTask = new ChainNode();
        userTask.setId("approval_1");
        userTask.setType("userTask");
        ChainNodeData utData = new ChainNodeData();
        utData.setLabel("Manager Approval");
        utData.setAssigneeRuleType("specific_user");
        utData.setAssigneeRuleConfig(Map.of("userIds", List.of("100")));
        utData.setAssigneeStrategy("any");
        utData.setTaskTitle("Approve: Test");
        userTask.setData(utData);

        ChainNode end = new ChainNode();
        end.setId("end");
        end.setType("endEvent");

        chain.setNodes(List.of(start, userTask, end));

        ChainEdge e1 = new ChainEdge();
        e1.setId("e1"); e1.setSource("start"); e1.setTarget("approval_1");
        ChainEdge e2 = new ChainEdge();
        e2.setId("e2"); e2.setSource("approval_1"); e2.setTarget("end");
        chain.setEdges(List.of(e1, e2));

        return chain;
    }

    private CommandChainDefinition buildChainWithServiceTaskAndUserTask() {
        CommandChainDefinition chain = buildSimpleChainWithUserTask();

        // Insert serviceTask between start and userTask
        ChainNode serviceTask = new ChainNode();
        serviceTask.setId("step_1");
        serviceTask.setType("serviceTask");
        ChainNodeData stData = new ChainNodeData();
        stData.setLabel("Create Record");
        stData.setCommandCode("test:create_record");
        stData.setOperationType("create");
        stData.setParams(Map.of("name", "Test Record"));
        serviceTask.setData(stData);

        List<ChainNode> nodes = new ArrayList<>(chain.getNodes());
        nodes.add(1, serviceTask); // Insert after start
        chain.setNodes(nodes);

        // Update edges
        ChainEdge e1 = new ChainEdge();
        e1.setId("e1"); e1.setSource("start"); e1.setTarget("step_1");
        ChainEdge e2 = new ChainEdge();
        e2.setId("e2"); e2.setSource("step_1"); e2.setTarget("approval_1");
        ChainEdge e3 = new ChainEdge();
        e3.setId("e3"); e3.setSource("approval_1"); e3.setTarget("end");
        chain.setEdges(List.of(e1, e2, e3));

        return chain;
    }

    private CommandChainDefinition buildChainWithGateway() {
        CommandChainDefinition chain = new CommandChainDefinition();
        chain.setProcessKey("gateway_test");
        chain.setChainMode(ChainMode.APPROVAL);

        ChainNode start = new ChainNode();
        start.setId("start"); start.setType("startEvent");

        ChainNode gateway = new ChainNode();
        gateway.setId("gw1"); gateway.setType("exclusiveGateway");
        ChainNodeData gwData = new ChainNodeData();
        gwData.setLabel("Amount check");
        gateway.setData(gwData);

        ChainNode userTask1 = new ChainNode();
        userTask1.setId("ut_high"); userTask1.setType("userTask");
        ChainNodeData ut1Data = new ChainNodeData();
        ut1Data.setAssigneeRuleType("specific_user");
        ut1Data.setAssigneeRuleConfig(Map.of("userIds", List.of("200")));
        ut1Data.setAssigneeStrategy("any");
        ut1Data.setTaskTitle("High value approval");
        userTask1.setData(ut1Data);

        ChainNode end = new ChainNode();
        end.setId("end"); end.setType("endEvent");

        chain.setNodes(List.of(start, gateway, userTask1, end));

        // Edges with condition
        ChainEdge e1 = new ChainEdge();
        e1.setId("e1"); e1.setSource("start"); e1.setTarget("gw1");

        ChainEdge e2 = new ChainEdge();
        e2.setId("e2"); e2.setSource("gw1"); e2.setTarget("ut_high");
        ChainEdgeCondition cond = new ChainEdgeCondition();
        cond.setType("expression"); cond.setContent("totalAmount > 50000");
        e2.setCondition(cond);

        ChainEdge e3 = new ChainEdge();
        e3.setId("e3"); e3.setSource("gw1"); e3.setTarget("end");
        // Default branch (no condition)

        ChainEdge e4 = new ChainEdge();
        e4.setId("e4"); e4.setSource("ut_high"); e4.setTarget("end");

        chain.setEdges(List.of(e1, e2, e3, e4));

        return chain;
    }

    // ==================== Tests ====================

    @Test
    void startChain_simpleLinear_suspendsAtUserTask() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(10L);
            when(assigneeResolverService.resolve(anyString(), anyMap(), anyMap()))
                    .thenReturn(List.of("100"));
            when(chainExecutionMapper.insert(any(ChainExecution.class))).thenReturn(1);
            when(approvalTaskMapper.insert(any(ApprovalTask.class))).thenReturn(1);

            CommandChainResult result = executor.startChain(
                    buildSimpleChainWithUserTask(), "test:123", Map.of("key1", "value1"));

            assertTrue(result.isSuccess());
            assertEquals("suspended", result.getStatus());
            assertNotNull(result.getApprovalTaskPid());
            assertNotNull(result.getChainExecutionPid());

            // Verify approval task was created
            ArgumentCaptor<ApprovalTask> taskCaptor = ArgumentCaptor.forClass(ApprovalTask.class);
            verify(approvalTaskMapper).insert(taskCaptor.capture());
            ApprovalTask task = taskCaptor.getValue();
            assertEquals("pending", task.getStatus());
            assertEquals(List.of(100L), task.getAssigneeUserIds());
            assertEquals("Approve: Test", task.getTaskTitle());
        }
    }

    @Test
    void startChain_withServiceTask_executesBeforeUserTask() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(10L);
            when(assigneeResolverService.resolve(anyString(), anyMap(), anyMap()))
                    .thenReturn(List.of("100"));
            when(chainExecutionMapper.insert(any(ChainExecution.class))).thenReturn(1);
            when(approvalTaskMapper.insert(any(ApprovalTask.class))).thenReturn(1);

            CommandExecuteResult cmdResult = CommandExecuteResult.builder()
                    .data(Map.of("recordId", "REC-001"))
                    .build();
            when(commandExecutor.execute(eq("test:create_record"), any())).thenReturn(cmdResult);

            CommandChainResult result = executor.startChain(
                    buildChainWithServiceTaskAndUserTask(), "test:456", Map.of("name", "Test"));

            assertTrue(result.isSuccess());
            assertEquals("suspended", result.getStatus());
            verify(commandExecutor, times(1)).execute(eq("test:create_record"), any());
        }
    }

    @Test
    void startChain_emptyAssignees_failsFast() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(10L);
            when(assigneeResolverService.resolve(anyString(), anyMap(), anyMap()))
                    .thenReturn(List.of()); // Empty assignees
            when(chainExecutionMapper.insert(any(ChainExecution.class))).thenReturn(1);

            CommandChainResult result = executor.startChain(
                    buildSimpleChainWithUserTask(), "test:789", Map.of());

            assertFalse(result.isSuccess());
            assertEquals("failed", result.getStatus());
            assertTrue(result.getErrorMessage().contains("No assignees"));
        }
    }

    @Test
    void handleApproval_approve_completesChain() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(100L);

            // Setup task
            ApprovalTask task = ApprovalTask.builder()
                    .pid("TASK-001")
                    .tenantId(1L)
                    .chainExecutionId("EXEC-001")
                    .chainNodeId("approval_1")
                    .processKey("test_approval")
                    .status("pending")
                    .assigneeUserIds(List.of(100L))
                    .assigneeStrategy("any")
                    .build();
            when(approvalTaskMapper.selectOne(anyApprovalTaskQuery())).thenReturn(task);
            when(approvalTaskMapper.update(isNull(), anyApprovalTaskUpdate())).thenReturn(1);

            // Setup chain execution
            CommandChainDefinition chain = buildSimpleChainWithUserTask();
            Map<String, Object> chainDef = objectMapper.convertValue(chain, new TypeReference<>() {});
            ChainExecution exec = ChainExecution.builder()
                    .pid("EXEC-001")
                    .tenantId(1L)
                    .processKey("test_approval")
                    .status("suspended")
                    .currentNodeId("approval_1")
                    .processVariables(Map.of("key1", "value1"))
                    .stepResults(new HashMap<>())
                    .chainDefinition(chainDef)
                    .build();
            when(chainExecutionMapper.selectOne(anyChainExecutionQuery())).thenReturn(exec);
            when(chainExecutionMapper.update(isNull(), anyChainExecutionUpdate())).thenReturn(1);

            CommandChainResult result = executor.handleApproval(
                    "TASK-001", 100L, "approved", "Looks good", null);

            assertTrue(result.isSuccess());
            assertEquals("completed", result.getStatus());
        }
    }

    @Test
    void handleApproval_reject_failsChain() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(100L);

            ApprovalTask task = ApprovalTask.builder()
                    .pid("TASK-002")
                    .tenantId(1L)
                    .chainExecutionId("EXEC-002")
                    .chainNodeId("approval_1")
                    .processKey("test_approval")
                    .status("pending")
                    .assigneeUserIds(List.of(100L))
                    .assigneeStrategy("any")
                    .build();
            when(approvalTaskMapper.selectOne(anyApprovalTaskQuery())).thenReturn(task);
            when(approvalTaskMapper.update(isNull(), anyApprovalTaskUpdate())).thenReturn(1);

            CommandChainDefinition chain = buildSimpleChainWithUserTask();
            Map<String, Object> chainDef = objectMapper.convertValue(chain, new TypeReference<>() {});
            ChainExecution exec = ChainExecution.builder()
                    .pid("EXEC-002")
                    .tenantId(1L)
                    .processKey("test_approval")
                    .status("suspended")
                    .currentNodeId("approval_1")
                    .processVariables(Map.of())
                    .stepResults(new HashMap<>())
                    .chainDefinition(chainDef)
                    .build();
            when(chainExecutionMapper.selectOne(anyChainExecutionQuery())).thenReturn(exec);
            when(chainExecutionMapper.update(isNull(), anyChainExecutionUpdate())).thenReturn(1);

            CommandChainResult result = executor.handleApproval(
                    "TASK-002", 100L, "rejected", "Not approved", null);

            assertFalse(result.isSuccess());
            assertEquals("failed", result.getStatus());
            assertTrue(result.getErrorMessage().contains("Rejected"));
        }
    }

    @Test
    void handleApproval_alreadyCompleted_throws() {
        ApprovalTask task = ApprovalTask.builder()
                .pid("TASK-003")
                .status("pending")
                .assigneeUserIds(List.of(100L))
                .build();
        when(approvalTaskMapper.selectOne(anyApprovalTaskQuery())).thenReturn(task);
        when(approvalTaskMapper.update(isNull(), anyApprovalTaskUpdate())).thenReturn(0); // Already completed

        assertThrows(IllegalStateException.class, () ->
                executor.handleApproval("TASK-003", 100L, "approved", "OK", null));
    }

    @Test
    void handleApproval_notAssignee_throwsSecurity() {
        ApprovalTask task = ApprovalTask.builder()
                .pid("TASK-004")
                .status("pending")
                .assigneeUserIds(List.of(200L)) // Different user
                .build();
        when(approvalTaskMapper.selectOne(anyApprovalTaskQuery())).thenReturn(task);
        when(approvalTaskMapper.update(isNull(), anyApprovalTaskUpdate())).thenReturn(1);

        assertThrows(SecurityException.class, () ->
                executor.handleApproval("TASK-004", 100L, "approved", "OK", null));
    }

    @Test
    void gateway_conditionTrue_takesCorrectBranch() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(10L);
            when(assigneeResolverService.resolve(anyString(), anyMap(), anyMap()))
                    .thenReturn(List.of("200"));
            when(chainExecutionMapper.insert(any(ChainExecution.class))).thenReturn(1);
            when(approvalTaskMapper.insert(any(ApprovalTask.class))).thenReturn(1);

            // Amount > 50000 → should go to ut_high userTask
            CommandChainResult result = executor.startChain(
                    buildChainWithGateway(), "gw:001", Map.of("totalAmount", 100000));

            assertTrue(result.isSuccess());
            assertEquals("suspended", result.getStatus());
            assertNotNull(result.getApprovalTaskPid());
        }
    }

    @Test
    void gateway_conditionFalse_takesDefaultBranch() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(10L);
            when(chainExecutionMapper.insert(any(ChainExecution.class))).thenReturn(1);

            // Amount <= 50000 → should go to end (default branch)
            CommandChainResult result = executor.startChain(
                    buildChainWithGateway(), "gw:002", Map.of("totalAmount", 30000));

            assertTrue(result.isSuccess());
            assertEquals("completed", result.getStatus());
        }
    }

    @Test
    void multipleUserTasks_suspendsAtEach() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(10L);
            when(assigneeResolverService.resolve(anyString(), anyMap(), anyMap()))
                    .thenReturn(List.of("100"));
            when(chainExecutionMapper.insert(any(ChainExecution.class))).thenReturn(1);
            when(approvalTaskMapper.insert(any(ApprovalTask.class))).thenReturn(1);

            // Build chain with 2 userTasks
            CommandChainDefinition chain = new CommandChainDefinition();
            chain.setProcessKey("multi_ut");
            chain.setChainMode(ChainMode.APPROVAL);

            ChainNode start = new ChainNode();
            start.setId("start"); start.setType("startEvent");
            ChainNode ut1 = new ChainNode();
            ut1.setId("ut1"); ut1.setType("userTask");
            ChainNodeData d1 = new ChainNodeData();
            d1.setAssigneeRuleType("specific_user");
            d1.setAssigneeRuleConfig(Map.of("userIds", List.of("100")));
            d1.setAssigneeStrategy("any");
            d1.setTaskTitle("Step 1");
            ut1.setData(d1);
            ChainNode ut2 = new ChainNode();
            ut2.setId("ut2"); ut2.setType("userTask");
            ChainNodeData d2 = new ChainNodeData();
            d2.setAssigneeRuleType("specific_user");
            d2.setAssigneeRuleConfig(Map.of("userIds", List.of("100")));
            d2.setAssigneeStrategy("any");
            d2.setTaskTitle("Step 2");
            ut2.setData(d2);
            ChainNode end = new ChainNode();
            end.setId("end"); end.setType("endEvent");

            chain.setNodes(List.of(start, ut1, ut2, end));
            ChainEdge e1 = new ChainEdge(); e1.setId("e1"); e1.setSource("start"); e1.setTarget("ut1");
            ChainEdge e2 = new ChainEdge(); e2.setId("e2"); e2.setSource("ut1"); e2.setTarget("ut2");
            ChainEdge e3 = new ChainEdge(); e3.setId("e3"); e3.setSource("ut2"); e3.setTarget("end");
            chain.setEdges(List.of(e1, e2, e3));

            // Start → should suspend at ut1
            CommandChainResult result = executor.startChain(chain, "multi:001", Map.of());
            assertEquals("suspended", result.getStatus());

            // Verify it suspended at first userTask
            ArgumentCaptor<ApprovalTask> captor = ArgumentCaptor.forClass(ApprovalTask.class);
            verify(approvalTaskMapper).insert(captor.capture());
            assertEquals("ut1", captor.getValue().getChainNodeId());
        }
    }

    // ==================== Supplementary tests ====================

    @Test
    void startChain_deadlineParsing_setsDeadlineAt() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(10L);
            when(assigneeResolverService.resolve(anyString(), anyMap(), anyMap()))
                    .thenReturn(List.of("100"));
            when(chainExecutionMapper.insert(any(ChainExecution.class))).thenReturn(1);
            when(approvalTaskMapper.insert(any(ApprovalTask.class))).thenReturn(1);

            // Build chain with deadline
            CommandChainDefinition chain = buildSimpleChainWithUserTask();
            chain.getNodes().get(1).getData().setDeadline("pt48h");

            executor.startChain(chain, "dl:001", Map.of());

            ArgumentCaptor<ApprovalTask> captor = ArgumentCaptor.forClass(ApprovalTask.class);
            verify(approvalTaskMapper).insert(captor.capture());
            assertNotNull(captor.getValue().getDeadlineAt());
        }
    }

    @Test
    void startChain_invalidDeadline_setsNullDeadline() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(10L);
            when(assigneeResolverService.resolve(anyString(), anyMap(), anyMap()))
                    .thenReturn(List.of("100"));
            when(chainExecutionMapper.insert(any(ChainExecution.class))).thenReturn(1);
            when(approvalTaskMapper.insert(any(ApprovalTask.class))).thenReturn(1);

            CommandChainDefinition chain = buildSimpleChainWithUserTask();
            chain.getNodes().get(1).getData().setDeadline("invalid");

            executor.startChain(chain, "dl:002", Map.of());

            ArgumentCaptor<ApprovalTask> captor = ArgumentCaptor.forClass(ApprovalTask.class);
            verify(approvalTaskMapper).insert(captor.capture());
            assertNull(captor.getValue().getDeadlineAt());
        }
    }

    @Test
    void startChain_taskTitleTemplateResolution() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(10L);
            when(assigneeResolverService.resolve(anyString(), anyMap(), anyMap()))
                    .thenReturn(List.of("100"));
            when(chainExecutionMapper.insert(any(ChainExecution.class))).thenReturn(1);
            when(approvalTaskMapper.insert(any(ApprovalTask.class))).thenReturn(1);

            CommandChainDefinition chain = buildSimpleChainWithUserTask();
            chain.getNodes().get(1).getData().setTaskTitle("Approve PO: ${poNumber} (${totalAmount})");

            executor.startChain(chain, "tpl:001",
                    Map.of("poNumber", "PO-2026-042", "totalAmount", 128000));

            ArgumentCaptor<ApprovalTask> captor = ArgumentCaptor.forClass(ApprovalTask.class);
            verify(approvalTaskMapper).insert(captor.capture());
            assertEquals("Approve PO: PO-2026-042 (128000)", captor.getValue().getTaskTitle());
        }
    }

    @Test
    void reassignTask_updatesAssignees() {
        ApprovalTask task = ApprovalTask.builder()
                .pid("TASK-R1")
                .tenantId(1L)
                .status("pending")
                .assigneeUserIds(List.of(100L))
                .processKey("test")
                .chainExecutionId("EXEC-R1")
                .chainNodeId("ut1")
                .build();
        when(approvalTaskMapper.selectOne(anyApprovalTaskQuery())).thenReturn(task);
        when(approvalTaskMapper.update(isNull(), anyApprovalTaskUpdate())).thenReturn(1);

        executor.reassignTask("TASK-R1", 100L, List.of(200L, 300L));

        verify(approvalTaskMapper).update(isNull(), anyApprovalTaskUpdate());
        verify(eventPublisher).publishEvent(any());
    }

    @Test
    void reassignTask_notPending_throws() {
        ApprovalTask task = ApprovalTask.builder()
                .pid("TASK-R2")
                .status("approved")
                .assigneeUserIds(List.of(100L))
                .build();
        when(approvalTaskMapper.selectOne(anyApprovalTaskQuery())).thenReturn(task);

        assertThrows(IllegalStateException.class, () ->
                executor.reassignTask("TASK-R2", 100L, List.of(200L)));
    }

    @Test
    void handleApproval_reject_withOnRejectCallback_executesCallback() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(100L);

            ApprovalTask task = ApprovalTask.builder()
                    .pid("TASK-REJ")
                    .tenantId(1L)
                    .chainExecutionId("EXEC-REJ")
                    .chainNodeId("approval_1")
                    .processKey("test_approval")
                    .status("pending")
                    .assigneeUserIds(List.of(100L))
                    .assigneeStrategy("any")
                    .build();
            when(approvalTaskMapper.selectOne(anyApprovalTaskQuery())).thenReturn(task);
            when(approvalTaskMapper.update(isNull(), anyApprovalTaskUpdate())).thenReturn(1);

            // Build chain with onReject callback
            CommandChainDefinition chain = buildSimpleChainWithUserTask();
            chain.getNodes().get(1).getData().setOnReject(Map.of(
                    "commandCode", "test:reject_order",
                    "operationType", "update",
                    "params", Map.of("status", "rejected")
            ));

            Map<String, Object> chainDef = objectMapper.convertValue(chain, new TypeReference<>() {});
            ChainExecution exec = ChainExecution.builder()
                    .pid("EXEC-REJ").tenantId(1L).processKey("test_approval")
                    .status("suspended").currentNodeId("approval_1")
                    .processVariables(Map.of()).stepResults(new HashMap<>())
                    .chainDefinition(chainDef).build();
            when(chainExecutionMapper.selectOne(anyChainExecutionQuery())).thenReturn(exec);
            when(chainExecutionMapper.update(isNull(), anyChainExecutionUpdate())).thenReturn(1);

            CommandExecuteResult cmdResult = CommandExecuteResult.builder().build();
            when(commandExecutor.execute(eq("test:reject_order"), any())).thenReturn(cmdResult);

            CommandChainResult result = executor.handleApproval(
                    "TASK-REJ", 100L, "rejected", "Not approved", null);

            assertFalse(result.isSuccess());
            verify(commandExecutor).execute(eq("test:reject_order"), any());
        }
    }

    @Test
    void startChain_serviceTaskConditionFalse_skipsStep() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(10L);
            when(assigneeResolverService.resolve(anyString(), anyMap(), anyMap()))
                    .thenReturn(List.of("100"));
            when(chainExecutionMapper.insert(any(ChainExecution.class))).thenReturn(1);
            when(approvalTaskMapper.insert(any(ApprovalTask.class))).thenReturn(1);

            CommandChainDefinition chain = buildChainWithServiceTaskAndUserTask();
            // Add condition that evaluates to false
            chain.getNodes().get(1).getData().setCondition("shouldRun == true");

            CommandChainResult result = executor.startChain(chain, "cond:001",
                    Map.of("shouldRun", false));

            // ServiceTask skipped, goes straight to userTask
            assertTrue(result.isSuccess());
            assertEquals("suspended", result.getStatus());
            verify(commandExecutor, never()).execute(anyString(), any());
        }
    }

    @Test
    void startChain_formSnapshotExcludesInternalVars() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(10L);
            when(assigneeResolverService.resolve(anyString(), anyMap(), anyMap()))
                    .thenReturn(List.of("100"));
            when(chainExecutionMapper.insert(any(ChainExecution.class))).thenReturn(1);
            when(approvalTaskMapper.insert(any(ApprovalTask.class))).thenReturn(1);

            Map<String, Object> payload = new HashMap<>();
            payload.put("orderNumber", "PO-001");
            payload.put("amount", 5000);

            executor.startChain(buildSimpleChainWithUserTask(), "snap:001", payload);

            ArgumentCaptor<ApprovalTask> captor = ArgumentCaptor.forClass(ApprovalTask.class);
            verify(approvalTaskMapper).insert(captor.capture());
            Map<String, Object> snapshot = captor.getValue().getFormSnapshot();
            assertNotNull(snapshot);
            assertTrue(snapshot.containsKey("orderNumber"));
            assertTrue(snapshot.containsKey("amount"));
            // Internal chain vars should be excluded
            assertFalse(snapshot.containsKey("_chain_mode"));
            assertFalse(snapshot.containsKey("_chain_execution_id"));
        }
    }
}
