package com.auraboot.framework.bpm.dto;

import com.auraboot.framework.bpm.chain.saga.SagaStep;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class SagaStepDTO {
    private String id;
    private int stepOrder;
    private String nodeId;
    private String commandCode;
    private String compensationCommand;
    private String status;
    private String recordId;
    private String errorMessage;
    private int retryCount;
    private Instant startedAt;
    private Instant completedAt;

    public static SagaStepDTO fromEntity(SagaStep step) {
        return SagaStepDTO.builder()
                .id(step.getId())
                .stepOrder(step.getStepOrder())
                .nodeId(step.getNodeId())
                .commandCode(step.getCommandCode())
                .compensationCommand(step.getCompensationCommand())
                .status(step.getStatus())
                .recordId(step.getRecordId())
                .errorMessage(step.getErrorMessage())
                .retryCount(step.getRetryCount())
                .startedAt(step.getStartedAt())
                .completedAt(step.getCompletedAt())
                .build();
    }
}
