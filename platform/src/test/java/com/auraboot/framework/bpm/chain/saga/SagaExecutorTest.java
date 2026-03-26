package com.auraboot.framework.bpm.chain.saga;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.chain.CommandChainDefinition;
import com.auraboot.framework.bpm.chain.CommandChainDefinition.*;
import com.auraboot.framework.bpm.chain.CommandChainResult;
import com.auraboot.framework.bpm.service.ExecutionLogService;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SagaExecutorTest {

    @Mock private SagaStateManager stateManager;
    @Mock private SagaStepRunner stepRunner;
    @Mock private SagaCompensator compensator;
    @Mock private ExecutionLogService executionLogService;

    private SagaExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SagaExecutor(stateManager, stepRunner, compensator, executionLogService);
    }

    private CommandChainDefinition buildThreeStepChain() {
        CommandChainDefinition chain = new CommandChainDefinition();
        chain.setProcessKey("test_saga");
        chain.setChainMode(ChainMode.SAGA);

        ChainNode start = new ChainNode();
        start.setId("start"); start.setType("startEvent");

        ChainNode st1 = new ChainNode();
        st1.setId("step_1"); st1.setType("serviceTask");
        ChainNodeData d1 = new ChainNodeData();
        d1.setCommandCode("test:create"); d1.setOperationType("create");
        d1.setParams(Map.of("name", "Test")); d1.setCompensationCommand("test:delete_create");
        st1.setData(d1);

        ChainNode st2 = new ChainNode();
        st2.setId("step_2"); st2.setType("serviceTask");
        ChainNodeData d2 = new ChainNodeData();
        d2.setCommandCode("test:update"); d2.setOperationType("update");
        d2.setParams(Map.of("status", "active")); d2.setCompensationCommand("test:revert_update");
        st2.setData(d2);

        ChainNode st3 = new ChainNode();
        st3.setId("step_3"); st3.setType("serviceTask");
        ChainNodeData d3 = new ChainNodeData();
        d3.setCommandCode("test:notify"); d3.setOperationType("create");
        d3.setParams(Map.of("msg", "done"));
        st3.setData(d3);

        ChainNode end = new ChainNode();
        end.setId("end"); end.setType("endEvent");

        chain.setNodes(List.of(start, st1, st2, st3, end));
        chain.setEdges(List.of()); // Not used in saga mode
        return chain;
    }

    @Test
    void execute_allStepsComplete_sagaCompleted() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(10L);

            SagaExecution saga = SagaExecution.builder()
                    .id("SAGA-001").tenantId(1L).chainCode("test_saga")
                    .businessKey("bk:001").status("running")
                    .totalSteps(3).completedSteps(0).build();
            when(stateManager.createExecution(any(), anyString(), anyMap())).thenReturn(saga);

            List<SagaStep> steps = List.of(
                    SagaStep.builder().id("S1").nodeId("step_1").commandCode("test:create")
                            .stepOrder(1).status("pending").retryCount(0).build(),
                    SagaStep.builder().id("S2").nodeId("step_2").commandCode("test:update")
                            .stepOrder(2).status("pending").retryCount(0).build(),
                    SagaStep.builder().id("S3").nodeId("step_3").commandCode("test:notify")
                            .stepOrder(3).status("pending").retryCount(0).build()
            );
            when(stateManager.createSteps(any(), any())).thenReturn(steps);

            CommandChainResult result = executor.execute(buildThreeStepChain(), "bk:001", Map.of());

            assertTrue(result.isSuccess());
            assertEquals("completed", result.getStatus());
            assertEquals("SAGA-001", result.getSagaExecutionId());
            verify(stepRunner, times(3)).executeStep(any(), anyMap());
            verify(stateManager, times(3)).markStepCompleted(any());
            verify(stateManager).markSagaCompleted(saga);
            verify(compensator, never()).compensate(any(), any(), any());
        }
    }

    @Test
    void execute_step2Fails_compensationTriggered() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(10L);

            SagaExecution saga = SagaExecution.builder()
                    .id("SAGA-002").tenantId(1L).chainCode("test_saga")
                    .businessKey("bk:002").status("running")
                    .totalSteps(3).completedSteps(0).build();
            when(stateManager.createExecution(any(), anyString(), anyMap())).thenReturn(saga);

            List<SagaStep> steps = new ArrayList<>(List.of(
                    SagaStep.builder().id("S1").nodeId("step_1").commandCode("test:create")
                            .stepOrder(1).status("pending").retryCount(0)
                            .compensationCommand("test:delete_create").build(),
                    SagaStep.builder().id("S2").nodeId("step_2").commandCode("test:update")
                            .stepOrder(2).status("pending").retryCount(0).build(),
                    SagaStep.builder().id("S3").nodeId("step_3").commandCode("test:notify")
                            .stepOrder(3).status("pending").retryCount(0).build()
            ));
            when(stateManager.createSteps(any(), any())).thenReturn(steps);

            // Step 1 succeeds, step 2 fails
            doNothing().doThrow(new RuntimeException("DB constraint violation"))
                    .when(stepRunner).executeStep(any(), anyMap());

            CommandChainResult result = executor.execute(buildThreeStepChain(), "bk:002", Map.of());

            assertFalse(result.isSuccess());
            assertEquals("step_2", result.getFailedNodeId());
            assertTrue(result.getErrorMessage().contains("DB constraint violation"));
            verify(compensator).compensate(eq(saga), eq(steps), anyMap());
            verify(stateManager).markSagaFailed(eq(saga), eq("step_2"), anyString());
        }
    }

    @Test
    void retryFromFailed_sagaNotFailed_throws() {
        SagaExecution saga = SagaExecution.builder()
                .id("SAGA-003").status("running").build();
        when(stateManager.getSagaExecution("SAGA-003")).thenReturn(saga);

        assertThrows(IllegalStateException.class, () -> executor.retryFromFailed("SAGA-003"));
    }

    @Test
    void retryFromFailed_sagaNotFound_throws() {
        when(stateManager.getSagaExecution("SAGA-999")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> executor.retryFromFailed("SAGA-999"));
    }

    @Test
    void retryFromFailed_retriesFromFailedStep() {
        SagaExecution saga = SagaExecution.builder()
                .id("SAGA-004").tenantId(1L).chainCode("test_saga")
                .businessKey("bk:004").status("failed")
                .totalSteps(3).completedSteps(1)
                .payload(Map.of("key", "value"))
                .build();
        when(stateManager.getSagaExecution("SAGA-004")).thenReturn(saga);

        SagaStep failedStep = SagaStep.builder()
                .id("S2").nodeId("step_2").commandCode("test:update")
                .stepOrder(2).status("failed").retryCount(0).build();
        when(stateManager.getFailedStep("SAGA-004")).thenReturn(failedStep);

        List<SagaStep> steps = new ArrayList<>(List.of(
                SagaStep.builder().id("S1").nodeId("step_1").commandCode("test:create")
                        .stepOrder(1).status("completed").retryCount(0)
                        .outputData(Map.of("recordId", "R1")).recordId("R1").build(),
                failedStep,
                SagaStep.builder().id("S3").nodeId("step_3").commandCode("test:notify")
                        .stepOrder(3).status("pending").retryCount(0).build()
        ));
        when(stateManager.getSteps("SAGA-004")).thenReturn(steps);

        CommandChainResult result = executor.retryFromFailed("SAGA-004");

        assertTrue(result.isSuccess());
        assertEquals("completed", result.getStatus());
        verify(stateManager).markSagaRunning(saga);
        // Step 1 is COMPLETED so skipped, steps 2 and 3 are executed
        verify(stepRunner, times(2)).executeStep(any(), anyMap());
    }

    // ==================== Supplementary tests ====================

    @Test
    void execute_singleStep_completesSuccessfully() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(10L);

            // Build minimal 1-step chain
            CommandChainDefinition chain = new CommandChainDefinition();
            chain.setProcessKey("single_saga");
            chain.setChainMode(ChainMode.SAGA);
            ChainNode start = new ChainNode(); start.setId("start"); start.setType("startEvent");
            ChainNode st1 = new ChainNode(); st1.setId("s1"); st1.setType("serviceTask");
            ChainNodeData d1 = new ChainNodeData();
            d1.setCommandCode("test:simple"); d1.setOperationType("create");
            d1.setParams(Map.of("name", "test"));
            st1.setData(d1);
            ChainNode end = new ChainNode(); end.setId("end"); end.setType("endEvent");
            chain.setNodes(List.of(start, st1, end));
            chain.setEdges(List.of());

            SagaExecution saga = SagaExecution.builder()
                    .id("SAGA-S1").tenantId(1L).chainCode("single_saga")
                    .businessKey("s:001").status("running")
                    .totalSteps(1).completedSteps(0).build();
            when(stateManager.createExecution(any(), anyString(), anyMap())).thenReturn(saga);
            when(stateManager.createSteps(any(), any())).thenReturn(List.of(
                    SagaStep.builder().id("ss1").nodeId("s1").commandCode("test:simple")
                            .stepOrder(1).status("pending").retryCount(0).build()
            ));

            CommandChainResult result = executor.execute(chain, "s:001", Map.of());

            assertTrue(result.isSuccess());
            assertEquals("completed", result.getStatus());
            assertEquals("SAGA-S1", result.getSagaExecutionId());
            verify(stepRunner, times(1)).executeStep(any(), anyMap());
        }
    }

    @Test
    void execute_firstStepFails_noCompletedStepsToCompensate() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(10L);

            SagaExecution saga = SagaExecution.builder()
                    .id("SAGA-F1").tenantId(1L).chainCode("test_saga")
                    .businessKey("f:001").status("running")
                    .totalSteps(2).completedSteps(0).build();
            when(stateManager.createExecution(any(), anyString(), anyMap())).thenReturn(saga);

            List<SagaStep> steps = List.of(
                    SagaStep.builder().id("fs1").nodeId("step_1").commandCode("test:create")
                            .stepOrder(1).status("pending").retryCount(0).build(),
                    SagaStep.builder().id("fs2").nodeId("step_2").commandCode("test:update")
                            .stepOrder(2).status("pending").retryCount(0).build()
            );
            when(stateManager.createSteps(any(), any())).thenReturn(steps);

            doThrow(new RuntimeException("Connection refused"))
                    .when(stepRunner).executeStep(any(), anyMap());

            CommandChainResult result = executor.execute(buildThreeStepChain(), "f:001", Map.of());

            assertFalse(result.isSuccess());
            assertEquals("step_1", result.getFailedNodeId());
            // Compensator still called but should have no completed steps to compensate
            verify(compensator).compensate(eq(saga), eq(steps), anyMap());
        }
    }

    @Test
    void execute_resultContainsProcessKeyAndBusinessKey() {
        try (MockedStatic<MetaContext> mc = mockStatic(MetaContext.class)) {
            mc.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            mc.when(MetaContext::getCurrentUserId).thenReturn(10L);

            SagaExecution saga = SagaExecution.builder()
                    .id("SAGA-PK").tenantId(1L).chainCode("test_saga")
                    .businessKey("pk:001").status("running")
                    .totalSteps(1).completedSteps(0).build();
            when(stateManager.createExecution(any(), anyString(), anyMap())).thenReturn(saga);
            when(stateManager.createSteps(any(), any())).thenReturn(List.of(
                    SagaStep.builder().id("pk1").nodeId("step_1").commandCode("test:create")
                            .stepOrder(1).status("pending").retryCount(0).build()
            ));

            CommandChainResult result = executor.execute(buildThreeStepChain(), "pk:001", Map.of());

            assertEquals("test_saga", result.getProcessKey());
            assertEquals("pk:001", result.getBusinessKey());
            assertEquals(ChainMode.SAGA, result.getChainMode());
        }
    }

    @Test
    void retryFromFailed_noFailedStep_throws() {
        SagaExecution saga = SagaExecution.builder()
                .id("SAGA-NF").status("failed").build();
        when(stateManager.getSagaExecution("SAGA-NF")).thenReturn(saga);
        when(stateManager.getFailedStep("SAGA-NF")).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> executor.retryFromFailed("SAGA-NF"));
    }
}
