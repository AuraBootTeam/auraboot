package com.auraboot.framework.bpm.chain.saga;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SagaCompensatorTest {

    @Mock private SagaStateManager stateManager;
    @Mock private SagaCompensationRunner compensationRunner;

    private SagaCompensator compensator;

    @BeforeEach
    void setUp() {
        compensator = new SagaCompensator(stateManager, compensationRunner);
    }

    @Test
    void compensate_allStepsCompensated_marksSagaCompensated() {
        SagaExecution saga = SagaExecution.builder().id("S1").status("failed").build();
        List<SagaStep> steps = List.of(
                SagaStep.builder().id("st1").nodeId("n1").stepOrder(1).status("completed")
                        .compensationCommand("test:undo_1").build(),
                SagaStep.builder().id("st2").nodeId("n2").stepOrder(2).status("completed")
                        .compensationCommand("test:undo_2").build(),
                SagaStep.builder().id("st3").nodeId("n3").stepOrder(3).status("failed").build()
        );

        compensator.compensate(saga, steps, Map.of());

        // Compensation runs in reverse order for COMPLETED steps only
        verify(compensationRunner, times(2)).compensateStep(any(), anyMap());
        verify(stateManager, times(2)).markStepCompensated(any());
        verify(stateManager).markSagaCompensated(saga);
        verify(stateManager, never()).markSagaCompensationFailed(any());
    }

    @Test
    void compensate_compensationFails_marksSagaCompensationFailed() {
        SagaExecution saga = SagaExecution.builder().id("S2").status("failed").build();
        List<SagaStep> steps = List.of(
                SagaStep.builder().id("st1").nodeId("n1").stepOrder(1).status("completed")
                        .compensationCommand("test:undo_1").build(),
                SagaStep.builder().id("st2").nodeId("n2").stepOrder(2).status("completed")
                        .compensationCommand("test:undo_2").build(),
                SagaStep.builder().id("st3").nodeId("n3").stepOrder(3).status("failed").build()
        );

        // Step 2 compensation succeeds, step 1 compensation fails
        doNothing().doThrow(new RuntimeException("Compensation DB error"))
                .when(compensationRunner).compensateStep(any(), anyMap());

        compensator.compensate(saga, steps, Map.of());

        verify(stateManager).markStepCompensated(any()); // step 2
        verify(stateManager).markStepCompensationFailed(any(), eq("Compensation DB error")); // step 1
        verify(stateManager).markSagaCompensationFailed(saga);
    }

    @Test
    void compensate_noCompensationCommand_skipsStep() {
        SagaExecution saga = SagaExecution.builder().id("S3").status("failed").build();
        List<SagaStep> steps = List.of(
                SagaStep.builder().id("st1").nodeId("n1").stepOrder(1).status("completed")
                        .compensationCommand(null).build(), // No compensation
                SagaStep.builder().id("st2").nodeId("n2").stepOrder(2).status("failed").build()
        );

        compensator.compensate(saga, steps, Map.of());

        verify(compensationRunner, never()).compensateStep(any(), anyMap());
        verify(stateManager).markSagaCompensated(saga); // All "compensated" (none needed)
    }

    @Test
    void compensate_noCompletedSteps_marksSagaCompensated() {
        SagaExecution saga = SagaExecution.builder().id("S4").status("failed").build();
        List<SagaStep> steps = List.of(
                SagaStep.builder().id("st1").nodeId("n1").stepOrder(1).status("failed").build(),
                SagaStep.builder().id("st2").nodeId("n2").stepOrder(2).status("pending").build()
        );

        compensator.compensate(saga, steps, Map.of());

        verify(compensationRunner, never()).compensateStep(any(), anyMap());
        verify(stateManager).markSagaCompensated(saga);
    }

    @Test
    void compensate_partialCompensationFailure_continuesBestEffort() {
        SagaExecution saga = SagaExecution.builder().id("S5").status("failed").build();
        List<SagaStep> steps = List.of(
                SagaStep.builder().id("st1").nodeId("n1").stepOrder(1).status("completed")
                        .compensationCommand("test:undo_1").build(),
                SagaStep.builder().id("st2").nodeId("n2").stepOrder(2).status("completed")
                        .compensationCommand("test:undo_2").build(),
                SagaStep.builder().id("st3").nodeId("n3").stepOrder(3).status("completed")
                        .compensationCommand("test:undo_3").build(),
                SagaStep.builder().id("st4").nodeId("n4").stepOrder(4).status("failed").build()
        );

        // Reverse order: step 3 OK, step 2 FAIL, step 1 OK — should still try all
        doNothing()
                .doThrow(new RuntimeException("Step 2 comp failed"))
                .doNothing()
                .when(compensationRunner).compensateStep(any(), anyMap());

        compensator.compensate(saga, steps, Map.of());

        // All 3 completed steps attempted
        verify(compensationRunner, times(3)).compensateStep(any(), anyMap());
        verify(stateManager).markSagaCompensationFailed(saga);
    }
}
