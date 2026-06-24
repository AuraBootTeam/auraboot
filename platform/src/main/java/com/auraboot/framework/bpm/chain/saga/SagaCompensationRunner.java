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
        if (step.getRecordPid() != null) {
            payload.put("recordPid", step.getRecordPid());
            payload.put("targetRecordPid", step.getRecordPid());
        }

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("delete"); // Compensation usually deletes/reverts
        request.setPayload(payload);
        if (step.getRecordPid() != null) {
            request.setTargetRecordId(step.getRecordPid());
        }

        commandExecutor.execute(step.getCompensationCommand(), request);
    }
}
