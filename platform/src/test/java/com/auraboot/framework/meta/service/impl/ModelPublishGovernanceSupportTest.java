package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.automation.dto.AutomationLogDTO;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.decision.dto.*;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.service.*;
import com.auraboot.framework.eventpolicy.executor.*;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionResult;
import com.auraboot.framework.eventpolicy.model.*;
import com.auraboot.framework.eventpolicy.service.EventPolicyRuntimeService;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.*;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.*;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.engine.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Characterizes publish risk and downstream replay outcomes independently of persistence. */
class ModelPublishGovernanceSupportTest {
    final MetaModelMapper models = mock(MetaModelMapper.class);
    final MetaFieldMapper fields = mock(MetaFieldMapper.class);
    final MetaModelFieldBindingMapper bindings = mock(MetaModelFieldBindingMapper.class);
    final DecisionImpactService impacts = mock(DecisionImpactService.class);
    final DecisionImpactAckService acknowledgements = mock(DecisionImpactAckService.class);
    final DecisionEvaluationService decisions = mock(DecisionEvaluationService.class);
    final EventPolicyRuntimeService events = mock(EventPolicyRuntimeService.class);
    final AutomationService automations = mock(AutomationService.class);
    final PermissionEvaluator permissions = mock(PermissionEvaluator.class);
    final Model model = new Model();
    final ModelPublishGovernanceSupport support = support(true);

    ModelPublishGovernanceSupport support(boolean available) {
        return new ModelPublishGovernanceSupport(models, fields, bindings, impacts, acknowledgements,
                available ? decisions : null, available ? events : null, available ? automations : null,
                available ? permissions : null, new ModelPublishWorkflowReplaySupport(null, new ObjectMapper()),
                extension -> extension == null || extension.getExtension() == null ? Map.of() : extension.getExtension());
    }

    ModelPublishReplayStepDTO step(String consumer) {
        return ModelPublishReplayStepDTO.builder().consumerType(consumer).sourceCode("model.invoice.read")
                .sourcePid("source-42").fieldRef("invoice.amount").recommendedAction("Review binding")
                .metadata(Map.of("eventType", "updated", "targetType", "MODEL", "targetKey", "invoice",
                        "fieldMasked", true, "roleId", 99L, "modelCode", "invoice")).build();
    }

    MetaModelPublishReplayRequest request(boolean execute) {
        var request = new MetaModelPublishReplayRequest();
        request.setExecuteAutomated(execute);
        request.setCorrelationId("correlation-42");
        request.setSampleContext(Map.of("permission", Map.of("memberId", "42"),
                "record", Map.of("pid", "invoice-42", "id", 17L, "data", Map.of("amount", 12))));
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"DECISION_VERSION", "EVENT_POLICY", "AUTOMATION", "PERMISSION_POLICY"})
    void previewDoesNotExecuteDownstreamConsumers(String consumer) {
        var result = support.replayPublishStep(model, step(consumer), request(false));
        assertThat(result.getStatus()).isEqualTo("READY");
        assertThat(result.getExecuted()).isFalse();
        verifyNoInteractions(decisions, events, automations, permissions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DECISION_VERSION", "EVENT_POLICY", "AUTOMATION", "PERMISSION_POLICY"})
    void absentCapabilityFailsWithoutClaimingExecution(String consumer) {
        var result = support(false).replayPublishStep(model, step(consumer), request(true));
        assertThat(result.getStatus()).endsWith("UNAVAILABLE");
        assertThat(result.getExecuted()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DECISION_VERSION", "EVENT_POLICY", "AUTOMATION", "PERMISSION_POLICY"})
    void automatedReplayRequiresRepresentativeInput(String consumer) {
        var input = request(true);
        input.setSampleContext(Map.of());
        var result = support.replayPublishStep(model, step(consumer), input);
        assertThat(result.getStatus()).isEqualTo("NEEDS_SAMPLE_CONTEXT");
        assertThat(result.getExecuted()).isFalse();
        verifyNoInteractions(decisions, events, automations, permissions);
    }

    @Test
    void decisionReplayPreservesCallerContextTraceOutputsAndErrors() {
        model.setPid("model-42");
        when(decisions.evaluate(any())).thenReturn(DecisionResult.builder("model.invoice.read")
                .traceId("trace-42").matched(true).outputs(Map.of("allowed", true)).build());
        var result = support.replayPublishStep(model, step("DECISION_VERSION"), request(true));
        assertThat(result.getStatus()).isEqualTo("EXECUTED");
        assertThat(result.getTraceId()).isEqualTo("trace-42");
        assertThat(result.getOutputs()).containsEntry("allowed", true);
        var capture = ArgumentCaptor.forClass(DrtEvaluateRequest.class);
        verify(decisions).evaluate(capture.capture());
        assertThat(capture.getValue().getCallerRef()).isEqualTo("model-42");
        assertThat(capture.getValue().getCorrelationId()).isEqualTo("correlation-42");
        assertThat(capture.getValue().getContext()).isEqualTo(request(true).getSampleContext());
        when(decisions.evaluate(any())).thenReturn(DecisionResult.builder("model.invoice.read").errors(List.of("invalid binding")).build());
        assertThat(support.replayPublishStep(model, step("DECISION_VERSION"), request(true)).getErrors())
                .containsExactly("invalid binding");
        when(decisions.evaluate(any())).thenThrow(new IllegalStateException("evaluation unavailable"));
        assertThat(support.replayPublishStep(model, step("DECISION_VERSION"), request(true)).getStatus()).isEqualTo("FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "failed", "error", "cancelled"})
    void automationReplayExposesExecutionLogAndDistinguishesFailure(String status) {
        var log = new AutomationLogDTO();
        log.setPid("log-42"); log.setStatus(status); log.setTriggerRecordPid("invoice-42");
        log.setErrorMessage("success".equals(status) ? null : "action rejected");
        log.setDurationMs(13L); log.setActionResults(List.of());
        when(automations.triggerManually(eq("source-42"), eq("invoice-42"), anyMap())).thenReturn(log);
        var result = support.replayPublishStep(model, step("AUTOMATION"), request(true));
        assertThat(result.getStatus()).isEqualTo("success".equals(status) ? "EXECUTED" : "FAILED");
        assertThat(result.getExecuted()).isEqualTo("success".equals(status));
        assertThat(result.getTraceId()).isEqualTo("log-42");
        assertThat(result.getOutputs()).containsEntry("recordPid", "invoice-42").containsEntry("logStatus", status);
    }

    @Test
    void automationMissingLogAndExceptionsRemainFailed() {
        var result = support.replayPublishStep(model, step("AUTOMATION"), request(true));
        assertThat(result.getErrors()).containsExactly("AUTOMATION_REPLAY_RETURNED_NO_LOG");
        assertThat(result.getExecuted()).isFalse();
        when(automations.triggerManually(anyString(), anyString(), anyMap())).thenThrow(new IllegalArgumentException("bad action"));
        assertThat(support.replayPublishStep(model, step("AUTOMATION"), request(true)).getErrors()).containsExactly("bad action");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void permissionReplayCarriesTheDecisionAndPublicTraceWithoutChangingRecord(boolean granted) {
        var evaluation = new EvaluationStep("RuleCenter", EvaluationVerdict.DENY, "policy evaluated",
                Map.of("ruleCenterFailures", List.of(Map.of("ruleTraceId", "permission-trace-42"))));
        when(permissions.canOperate(eq(42L), eq("invoice"), eq("read"), anyMap()))
                .thenReturn(new PermissionResult(granted, "policy decision", List.of(evaluation)));
        var result = support.replayPublishStep(model, step("PERMISSION_POLICY"), request(true));
        assertThat(result.getStatus()).isEqualTo("EXECUTED");
        assertThat(result.getMatched()).isEqualTo(granted);
        assertThat(result.getTraceId()).isEqualTo("permission-trace-42");
        assertThat(result.getOutputs()).containsEntry("memberId", "42").containsEntry("roleId", "99")
                .containsEntry("fieldMasked", true).containsEntry("affectedFieldRef", "invoice.amount");
        verify(permissions).canOperate(42L, "invoice", "read", Map.of("amount", 12, "pid", "invoice-42", "id", 17L));
    }

    @Test
    void permissionNullResultAndExceptionsDoNotBecomeAllow() {
        assertThat(support.replayPublishStep(model, step("PERMISSION_POLICY"), request(true)).getErrors())
                .containsExactly("PERMISSION_REPLAY_RETURNED_NO_RESULT");
        when(permissions.canOperate(anyLong(), anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("deny failure"));
        assertThat(support.replayPublishStep(model, step("PERMISSION_POLICY"), request(true)).getStatus()).isEqualTo("FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MATCHED", "NOT_MATCHED", "ERROR", "CONFLICT"})
    void eventPolicyReplayCarriesRuntimeAndActionOutcomes(String status) {
        var policy = new EventPolicyResult("policy-42", EventPolicyResult.Status.valueOf(status),
                List.of("rule-42"), List.of(), List.of(), List.of(), "event-correlation", List.of("decision-trace"));
        var action = ActionExecutionResult.success("rule-42", "UPDATE", "key-42", Map.of("updated", 1));
        var execution = new PolicyExecutionResult("policy-42", PolicyExecutionResult.OverallStatus.ALL_SUCCESS, List.of(action));
        when(events.runAndExecute(eq("updated"), eq("MODEL"), eq("invoice"), anyMap()))
                .thenReturn(new EventPolicyExecutionResult(policy, execution));
        var result = support.replayPublishStep(model, step("EVENT_POLICY"), request(true));
        assertThat(result.getStatus()).isEqualTo(Set.of("ERROR", "CONFLICT").contains(status) ? "FAILED" : "EXECUTED");
        assertThat(result.getTraceId()).isEqualTo("decision-trace");
        assertThat(result.getOutputs()).containsEntry("policyCode", "policy-42").containsEntry("actionCount", 1)
                .containsEntry("successfulActionCount", 1L);
        assertThat(result.getOutputs().get("actions")).asList().hasSize(1);
    }

    @Test
    void partialEventExecutionAndMissingMetadataAreNotSuccessfulReplay() {
        var action = new ActionExecutionResult("rule-42", "UPDATE", "key-42", ActionExecutionStatus.FAILED, "write denied");
        var policy = new EventPolicyResult("policy-42", EventPolicyResult.Status.MATCHED, List.of(), List.of(), List.of(), List.of());
        when(events.runAndExecute(anyString(), anyString(), anyString(), anyMap())).thenReturn(new EventPolicyExecutionResult(
                policy, new PolicyExecutionResult("policy-42", PolicyExecutionResult.OverallStatus.PARTIAL_SUCCESS, List.of(action))));
        var result = support.replayPublishStep(model, step("EVENT_POLICY"), request(true));
        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getErrors()).contains("write denied", "EVENT_POLICY_EXECUTION_PARTIAL_SUCCESS");
        var step = step("EVENT_POLICY"); step.setMetadata(Map.of());
        assertThat(support.replayPublishStep(model, step, request(true)).getStatus()).isEqualTo("MANUAL_REQUIRED");
        when(events.runAndExecute(anyString(), anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("runtime offline"));
        assertThat(support.replayPublishStep(model, step("EVENT_POLICY"), request(true)).getErrors()).containsExactly("runtime offline");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DECISION", "EVENT", "AUTOMATION", "BPM", "SLA", "PERMISSION", "NAMED_QUERY", "CUSTOM"})
    void schemaRiskPreservesFieldImpactAndBuildsTheCorrespondingConsumerPlan(String consumer) {
        model.setId(8L); model.setPid("model-42"); model.setCode("invoice"); model.setVersion(3);
        var binding = new ModelFieldBinding(); binding.setFieldId(9L);
        var field = new Field(); field.setId(9L); field.setCode("amount");
        var extension = new ExtensionBean(); extension.setExtension(Map.of("masked", true, "permission", "invoice.read")); field.setExtension(extension);
        when(bindings.findByModelId(8L)).thenReturn(List.of(binding));
        when(fields.findByIds(List.of(9L))).thenReturn(List.of(field));
        var ref = new DecisionImpactRefDTO(); ref.setSourceType(consumer); ref.setSourceCode("consumer-42");
        ref.setSourcePid("source-42"); ref.setMetadata(Map.of("sourceVersion", 2));
        var risk = new DecisionImpactRiskDTO(); risk.setBlocking(true); risk.setSummary("breaking field change");
        var impact = new DecisionFieldImpactDTO(); impact.setRisk(risk); impact.setReferences(List.of(ref));
        when(impacts.getFieldImpact("invoice.amount")).thenReturn(impact);
        var published = new Model(); published.setVersion(2); published.setStatus("published");
        when(models.findAllVersionsByCode("invoice")).thenReturn(List.of(published));
        var preview = DDLPreviewResult.builder().ddlStatements(List.of("ALTER TABLE invoice DROP COLUMN amount")).build();
        var governance = support.buildPublishGovernance(model, preview);
        assertThat(governance.getBlocked()).isTrue();
        assertThat(governance.getRequiresAcknowledgement()).isTrue();
        assertThat(governance.getSchemaChangeKinds()).containsExactly("DROP_COLUMN");
        assertThat(governance.getLatestPublishedVersion()).isEqualTo(2);
        assertThat(governance.getFieldImpacts()).extracting(DecisionFieldImpactDTO::getFieldRef).containsExactly("invoice.amount");
        assertThat(governance.getReplayPlan()).hasSize(1);
        assertThat(governance.getReplayPlan().get(0).getMetadata()).containsEntry("fieldMasked", true)
                .containsEntry("fieldPermissionChange", true).containsEntry("requiresLowPermissionSample", true);
        assertThat(ref.getMetadata()).doesNotContainKey("fieldMasked");
        support.recordModelPublishAcknowledgement(model, governance, "Owner reviewed");
        verify(acknowledgements).recordAcknowledgement(eq("MODEL_PUBLISH"), eq("MODEL"), eq("invoice"),
                eq("model-42"), eq("invoice"), contains("invoice.amount"), same(governance), eq("Owner reviewed"));
        var report = support.replayReport(model, governance, request(false));
        assertThat(report.getTotalCount()).isEqualTo(1);
        assertThat(report.getExecutedCount()).isZero();
    }
}
