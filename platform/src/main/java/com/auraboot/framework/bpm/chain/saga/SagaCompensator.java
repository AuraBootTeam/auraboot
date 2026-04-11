package com.auraboot.framework.bpm.chain.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Compensates completed saga steps in reverse order after a failure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaCompensator {

    private final SagaStateManager stateManager;
    private final SagaCompensationRunner compensationRunner;

    public void compensate(SagaExecution saga, List<SagaStep> steps, Map<String, Object> processVars) {
        stateManager.markSagaCompensating(saga);

        // Get completed steps in reverse order
        List<SagaStep> completedSteps = steps.stream()
                .filter(s -> SagaStatus.COMPLETED.name().toLowerCase().equals(s.getStatus()))
                .sorted(Comparator.comparingInt(SagaStep::getStepOrder).reversed())
                .toList();

        boolean allCompensated = true;

        for (SagaStep step : completedSteps) {
            if (step.getCompensationCommand() == null || step.getCompensationCommand().isBlank()) {
                log.warn("Step {} ({}) has no compensation command, skipping",
                        step.getNodeId(), step.getCommandCode());
                continue;
            }

            try {
                compensationRunner.compensateStep(step, processVars);
                stateManager.markStepCompensated(step);
                log.info("Compensated step {} ({})", step.getNodeId(), step.getCompensationCommand());
            } catch (Exception e) {
                log.error("Compensation failed for step {} ({}): {}",
                        step.getNodeId(), step.getCompensationCommand(), e.getMessage(), e);
                stateManager.markStepCompensationFailed(step, e.getMessage());
                allCompensated = false;
                // Continue — best-effort compensation
            }
        }

        if (allCompensated) {
            stateManager.markSagaCompensated(saga);
        } else {
            stateManager.markSagaCompensationFailed(saga);
        }
    }
}
