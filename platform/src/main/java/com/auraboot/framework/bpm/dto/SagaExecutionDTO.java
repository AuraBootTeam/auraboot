package com.auraboot.framework.bpm.dto;

import com.auraboot.framework.bpm.chain.saga.SagaExecution;
import com.auraboot.framework.bpm.chain.saga.SagaStep;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class SagaExecutionDTO {
    private String id;
    private String chainCode;
    private String businessKey;
    private String status;
    private String currentStep;
    private int totalSteps;
    private int completedSteps;
    private String errorMessage;
    private Instant startedAt;
    private Instant completedAt;
    private List<SagaStepDTO> steps;

    public static SagaExecutionDTO fromEntity(SagaExecution exec, List<SagaStep> steps) {
        return SagaExecutionDTO.builder()
                .id(exec.getId())
                .chainCode(exec.getChainCode())
                .businessKey(exec.getBusinessKey())
                .status(exec.getStatus())
                .currentStep(exec.getCurrentStep())
                .totalSteps(exec.getTotalSteps())
                .completedSteps(exec.getCompletedSteps())
                .errorMessage(exec.getErrorMessage())
                .startedAt(exec.getStartedAt())
                .completedAt(exec.getCompletedAt())
                .steps(steps != null ? steps.stream().map(SagaStepDTO::fromEntity).toList() : List.of())
                .build();
    }
}
