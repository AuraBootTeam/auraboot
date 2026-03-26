package com.auraboot.framework.bpm.chain.saga;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.chain.CommandChainDefinition;
import com.auraboot.framework.bpm.chain.CommandChainDefinition.ChainNode;
import com.auraboot.framework.bpm.mapper.SagaExecutionMapper;
import com.auraboot.framework.bpm.mapper.SagaStepMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persistence layer for saga state management.
 * All mutations use REQUIRES_NEW to commit independently of the outer transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaStateManager {

    private final SagaExecutionMapper executionMapper;
    private final SagaStepMapper stepMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SagaExecution createExecution(CommandChainDefinition chain, String businessKey,
                                          Map<String, Object> payload) {
        int totalSteps = (int) chain.getNodes().stream()
                .filter(n -> "serviceTask".equals(n.getType()))
                .count();

        SagaExecution saga = SagaExecution.builder()
                .id(UlidGenerator.generate())
                .tenantId(MetaContext.getCurrentTenantId())
                .chainCode(chain.getProcessKey())
                .businessKey(businessKey)
                .status(SagaStatus.RUNNING.name())
                .totalSteps(totalSteps)
                .completedSteps(0)
                .payload(payload)
                .startedAt(Instant.now())
                .createdBy(MetaContext.getCurrentUserId())
                .updatedAt(Instant.now())
                .build();
        executionMapper.insert(saga);
        return saga;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<SagaStep> createSteps(SagaExecution saga, CommandChainDefinition chain) {
        List<SagaStep> steps = new ArrayList<>();
        int order = 1;
        for (ChainNode node : chain.getNodes()) {
            if (!"serviceTask".equals(node.getType())) continue;
            var data = node.getData();
            if (data == null) continue;

            SagaStep step = SagaStep.builder()
                    .id(UlidGenerator.generate())
                    .sagaExecutionId(saga.getId())
                    .tenantId(saga.getTenantId())
                    .stepOrder(order++)
                    .nodeId(node.getId())
                    .commandCode(data.getCommandCode())
                    .compensationCommand(data.getCompensationCommand())
                    .status(SagaStatus.PENDING.name())
                    .inputParams(data.getParams())
                    .retryCount(0)
                    .build();
            stepMapper.insert(step);
            steps.add(step);
        }
        return steps;
    }

    // Step-level mutations

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStepRunning(SagaStep step) {
        step.setStatus(SagaStatus.RUNNING.name());
        step.setStartedAt(Instant.now());
        stepMapper.updateById(step);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStepCompleted(SagaStep step) {
        step.setStatus(SagaStatus.COMPLETED.name());
        step.setCompletedAt(Instant.now());
        stepMapper.updateById(step);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStepFailed(SagaStep step, String errorMessage) {
        step.setStatus(SagaStatus.FAILED.name());
        step.setErrorMessage(errorMessage);
        step.setCompletedAt(Instant.now());
        stepMapper.updateById(step);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStepCompensating(SagaStep step) {
        step.setStatus(SagaStatus.COMPENSATING.name());
        stepMapper.updateById(step);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStepCompensated(SagaStep step) {
        step.setStatus(SagaStatus.COMPENSATED.name());
        stepMapper.updateById(step);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStepCompensationFailed(SagaStep step, String errorMessage) {
        step.setStatus(SagaStatus.COMPENSATION_FAILED.name());
        step.setErrorMessage(errorMessage);
        stepMapper.updateById(step);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStepOutput(SagaStep step) {
        stepMapper.updateById(step);
    }

    // Saga-level mutations

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(SagaExecution saga, String currentStepNodeId) {
        saga.setCompletedSteps(saga.getCompletedSteps() + 1);
        saga.setCurrentStep(currentStepNodeId);
        saga.setUpdatedAt(Instant.now());
        executionMapper.updateById(saga);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSagaCompleted(SagaExecution saga) {
        saga.setStatus(SagaStatus.COMPLETED.name());
        saga.setCompletedAt(Instant.now());
        saga.setUpdatedAt(Instant.now());
        executionMapper.updateById(saga);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSagaFailed(SagaExecution saga, String nodeId, String error) {
        saga.setStatus(SagaStatus.FAILED.name());
        saga.setCurrentStep(nodeId);
        saga.setErrorMessage(error);
        saga.setUpdatedAt(Instant.now());
        executionMapper.updateById(saga);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSagaCompensating(SagaExecution saga) {
        saga.setStatus(SagaStatus.COMPENSATING.name());
        saga.setUpdatedAt(Instant.now());
        executionMapper.updateById(saga);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSagaCompensated(SagaExecution saga) {
        saga.setStatus(SagaStatus.COMPENSATED.name());
        saga.setCompletedAt(Instant.now());
        saga.setUpdatedAt(Instant.now());
        executionMapper.updateById(saga);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSagaCompensationFailed(SagaExecution saga) {
        saga.setStatus(SagaStatus.COMPENSATION_FAILED.name());
        saga.setUpdatedAt(Instant.now());
        executionMapper.updateById(saga);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSagaRunning(SagaExecution saga) {
        saga.setStatus(SagaStatus.RUNNING.name());
        saga.setErrorMessage(null);
        saga.setUpdatedAt(Instant.now());
        executionMapper.updateById(saga);
    }

    // Query methods

    public SagaExecution getSagaExecution(String sagaId) {
        return executionMapper.selectById(sagaId);
    }

    public List<SagaStep> getSteps(String sagaId) {
        return stepMapper.selectList(
                new QueryWrapper<SagaStep>()
                        .eq("saga_execution_id", sagaId)
                        .orderByAsc("step_order"));
    }

    public SagaStep getFailedStep(String sagaId) {
        return stepMapper.selectOne(
                new QueryWrapper<SagaStep>()
                        .eq("saga_execution_id", sagaId)
                        .eq("status", SagaStatus.FAILED.name())
                        .last("LIMIT 1"));
    }
}
