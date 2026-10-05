package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.MetaModelPublishReplayRequest;
import com.auraboot.framework.meta.dto.ModelPublishReplayResultDTO;
import com.auraboot.framework.meta.dto.ModelPublishReplayStepDTO;
import com.auraboot.framework.plugin.extension.WorkflowCapability;
import com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Platform adapter for the installed product's workflow replay operation. */
@RequiredArgsConstructor
@Slf4j
final class ModelPublishWorkflowReplaySupport {
    private static final String OPERATION = "model-publish.replay";
    private final WorkflowCapabilityRegistry capabilities;
    private final ObjectMapper objectMapper;

    ModelPublishReplayResultDTO replay(ModelPublishReplayStepDTO step, MetaModelPublishReplayRequest request) {
        if (capabilities == null || !capabilities.available(OPERATION)) {
            return ModelPublishReplayResultDTO.builder().step(step)
                    .status("WORKFLOW_PROCESS".equals(step.getConsumerType()) ? "BPM_UNAVAILABLE" : "SLA_UNAVAILABLE")
                    .automated(false).executed(false).message("Installed workflow replay capability is unavailable.")
                    .errors(List.of("WORKFLOW_REPLAY_UNAVAILABLE")).outputs(Map.of()).build();
        }
        try {
            Map<String, Object> stepPayload = objectMapper.convertValue(step, new TypeReference<>() {});
            Map<String, Object> requestPayload = request == null ? Map.of()
                    : objectMapper.convertValue(request, new TypeReference<>() {});
            WorkflowCapability.WorkflowResult result = capabilities.execute(OPERATION,
                    new WorkflowCapability.WorkflowRequest(MetaContext.getCurrentTenantId(),
                            MetaContext.getCurrentUserId(), Map.of("step", stepPayload, "request", requestPayload)));
            if (result == null) throw new IllegalStateException("WORKFLOW_REPLAY_NO_RESULT");
            Outcome outcome = objectMapper.convertValue(result.payload(), Outcome.class);
            if (!Set.of("READY", "EXECUTED", "FAILED", "NEEDS_SAMPLE_CONTEXT", "MANUAL_REQUIRED").contains(outcome.status())
                    || outcome.automated() == null || outcome.executed() == null
                    || outcome.message() == null || outcome.outputs() == null || outcome.errors() == null
                    || ("EXECUTED".equals(outcome.status()) != Boolean.TRUE.equals(outcome.executed()))) {
                throw new IllegalStateException("WORKFLOW_REPLAY_INVALID_RESULT");
            }
            return ModelPublishReplayResultDTO.builder().step(step).status(outcome.status())
                    .automated(outcome.automated()).executed(outcome.executed()).matched(outcome.matched())
                    .message(outcome.message()).traceId(outcome.traceId())
                    .outputs(outcome.outputs()).errors(outcome.errors()).build();
        } catch (RuntimeException failure) {
            // CATCH P1: retain a failed consumer result while the aggregate replays other consumers.
            log.warn("Workflow publish replay failed for source {}", step.getSourcePid(), failure);
            return ModelPublishReplayResultDTO.builder().step(step).status("FAILED")
                    .automated(true).executed(false).message("Workflow replay failed.")
                    .errors(List.of("WORKFLOW_REPLAY_FAILED")).outputs(Map.of()).build();
        }
    }

    private record Outcome(String status, Boolean automated, Boolean executed, String message,
            String traceId, Boolean matched, Map<String, Object> outputs, List<String> errors) {}
}
