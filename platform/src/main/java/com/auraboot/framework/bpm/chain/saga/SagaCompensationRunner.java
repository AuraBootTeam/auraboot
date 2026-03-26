package com.auraboot.framework.bpm.chain.saga;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.CommandExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Executes a compensation command in its own REQUIRES_NEW transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaCompensationRunner {

    private final CommandExecutor commandExecutor;
    private final SagaStateManager stateManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void compensateStep(SagaStep step, Map<String, Object> processVars) {
        stateManager.markStepCompensating(step);

        // Build payload from forward step's output
        Map<String, Object> payload = new HashMap<>();
        if (step.getOutputData() != null) {
            payload.putAll(step.getOutputData());
        }
        if (step.getRecordId() != null) {
            payload.put("recordId", step.getRecordId());
            payload.put("targetRecordId", step.getRecordId());
        }

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("delete"); // Compensation usually deletes/reverts
        request.setPayload(payload);
        if (step.getRecordId() != null) {
            request.setTargetRecordId(step.getRecordId());
        }

        commandExecutor.execute(step.getCompensationCommand(), request);
    }
}
