package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.webhook.service.WebhookDispatchResult;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link WebhookActionHandler} — fans out to WebhookDispatcher with the right event
 * type / body / tenant. Real-stack delivery is @Async + needs a webhook subscription, so the async
 * delivery-log IT is a documented follow-on (gap tracker); this verifies the dispatch contract.
 */
class WebhookActionHandlerTest {

    private final WebhookDispatcher dispatcher = mock(WebhookDispatcher.class);
    private final WebhookActionHandler handler = new WebhookActionHandler(dispatcher);

    @AfterEach
    void clear() {
        MetaContext.clear();
    }

    private ResolvedActionPlan plan(Map<String, Object> payload) {
        return new ResolvedActionPlan("R-1", "WEBHOOK", "https://x", 10, payload, "idem-1");
    }

    @Test
    void dispatchesEventWithBodyAndTenant() {
        MetaContext.setCurrentTenantId(42L);
        when(dispatcher.dispatchTracked(eq("complaint.escalated"), org.mockito.ArgumentMatchers.anyMap(), eq(42L)))
                .thenReturn(new WebhookDispatchResult(List.of()));
        handler.execute(plan(Map.of("eventType", "complaint.escalated", "caseId", "CMP-1")),
                DecisionContext.of(Map.of()));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).dispatchTracked(eq("complaint.escalated"), body.capture(), eq(42L));
        assertThat(body.getValue()).containsEntry("caseId", "CMP-1").doesNotContainKey("eventType");
    }

    @Test
    void returnsStructuredWebhookDispatchAcceptedResult() throws Exception {
        MetaContext.setCurrentTenantId(42L);
        when(dispatcher.dispatchTracked(eq("complaint.escalated"), org.mockito.ArgumentMatchers.anyMap(), eq(42L)))
                .thenReturn(new WebhookDispatchResult(List.of()));

        Map<String, Object> result = handler.executeWithResult(
                plan(Map.of("eventType", "complaint.escalated", "caseId", "CMP-1")),
                DecisionContext.of(Map.of()));

        assertThat(result)
                .containsEntry("eventType", "complaint.escalated")
                .containsEntry("tenantId", 42L)
                .containsEntry("dispatchAccepted", true);
        assertThat(result.get("payloadKeys")).asList().containsExactly("caseId");
    }

    @Test
    void injectsDeliveryEventIdForWebhookDeliveryTrace() {
        MetaContext.setCurrentTenantId(42L);
        when(dispatcher.dispatchTracked(eq("complaint.escalated"), org.mockito.ArgumentMatchers.anyMap(), eq(42L)))
                .thenReturn(new WebhookDispatchResult(List.of()));

        Map<String, Object> result = handler.executeWithResult(
                plan(Map.of("eventType", "complaint.escalated", "caseId", "CMP-1")),
                DecisionContext.of(Map.of()));

        assertThat(result.get("deliveryEventId")).isInstanceOf(String.class);
        assertThat((String) result.get("deliveryEventId")).isNotBlank();
        assertThat(result)
                .containsEntry("deliveryTraceStatus", "pending_async_delivery");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).dispatchTracked(eq("complaint.escalated"), body.capture(), eq(42L));
        assertThat(body.getValue())
                .containsEntry("_eventId", result.get("deliveryEventId"))
                .doesNotContainKey("eventType");
    }

    @Test
    void rendersPayloadTemplatesBeforeDispatchAndResultEvidence() {
        MetaContext.setCurrentTenantId(42L);
        when(dispatcher.dispatchTracked(eq("sla.timeout"), org.mockito.ArgumentMatchers.anyMap(), eq(42L)))
                .thenReturn(new WebhookDispatchResult(List.of()));

        Map<String, Object> result = handler.executeWithResult(
                plan(Map.of(
                        "eventType", "sla.timeout",
                        "_eventId", "${sla.recordPid}:timeout:WEBHOOK:event",
                        "recordPid", "${record.recordPid}",
                        "slaRecordPid", "${sla.recordPid}")),
                DecisionContext.builder()
                        .put(Scope.RECORD, Map.of("recordPid", "leave-1"))
                        .put(Scope.SLA, Map.of("recordPid", "sla-1"))
                        .build());

        assertThat(result)
                .containsEntry("deliveryEventId", "sla-1:timeout:WEBHOOK:event")
                .containsEntry("recordPid", "leave-1")
                .containsEntry("slaRecordPid", "sla-1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).dispatchTracked(eq("sla.timeout"), body.capture(), eq(42L));
        assertThat(body.getValue())
                .containsEntry("_eventId", "sla-1:timeout:WEBHOOK:event")
                .containsEntry("recordPid", "leave-1")
                .containsEntry("slaRecordPid", "sla-1")
                .doesNotContainKey("eventType");
    }

    @Test
    void returnsTrackedDeliveryLogPidsWhenDispatcherProvidesReceipts() {
        MetaContext.setCurrentTenantId(42L);
        when(dispatcher.dispatchTracked(eq("complaint.escalated"), org.mockito.ArgumentMatchers.anyMap(), eq(42L)))
                .thenReturn(new WebhookDispatchResult(List.of(
                        new WebhookDispatchResult.Receipt(
                                "sub-1",
                                "delivery-log-1",
                                "policy-webhook-evt-1",
                                "failed",
                                false,
                                "blocked"
                        )
                )));

        Map<String, Object> result = handler.executeWithResult(
                plan(Map.of(
                        "eventType", "complaint.escalated",
                        "_eventId", "policy-webhook-evt-1",
                        "caseId", "CMP-1")),
                DecisionContext.of(Map.of()));

        assertThat(result)
                .containsEntry("deliveryEventId", "policy-webhook-evt-1")
                .containsEntry("deliveryTraceStatus", "tracked_delivery_logs");
        assertThat(result.get("deliveryLogPids")).asList().containsExactly("delivery-log-1");
        assertThat(result.get("deliveryReceipts")).asList().hasSize(1);
    }

    @Test
    void wrapsDispatcherFailureWithStructuredWebhookResultPayload() {
        MetaContext.setCurrentTenantId(42L);
        when(dispatcher.dispatchTracked(eq("complaint.escalated"), org.mockito.ArgumentMatchers.anyMap(), eq(42L)))
                .thenThrow(new IllegalStateException("dispatcher down"));

        assertThatThrownBy(() -> handler.executeWithResult(
                plan(Map.of(
                        "eventType", "complaint.escalated",
                        "_eventId", "policy-webhook-evt-1",
                        "recordPid", "CMP-1",
                        "caseId", "CASE-1")),
                DecisionContext.of(Map.of())))
                .isInstanceOf(ActionExecutionException.class)
                .hasMessage("WEBHOOK dispatch failed: dispatcher down")
                .satisfies(error -> {
                    Map<String, Object> payload = ((ActionExecutionException) error).resultPayload();
                    assertThat(payload)
                            .containsEntry("eventType", "complaint.escalated")
                            .containsEntry("tenantId", 42L)
                            .containsEntry("dispatchAccepted", false)
                            .containsEntry("deliveryEventId", "policy-webhook-evt-1")
                            .containsEntry("deliveryTraceStatus", "dispatch_failed")
                            .containsEntry("failureReason", "webhook_dispatch_failed")
                            .containsEntry("errorMessage", "dispatcher down")
                            .containsEntry("recordPid", "CMP-1");
                    assertThat(payload.get("payloadKeys")).asList()
                            .containsExactlyInAnyOrder("recordPid", "caseId");
                });
    }

    @Test
    void preservesCallerSuppliedDeliveryEventId() {
        MetaContext.setCurrentTenantId(42L);
        when(dispatcher.dispatchTracked(eq("complaint.escalated"), org.mockito.ArgumentMatchers.anyMap(), eq(42L)))
                .thenReturn(new WebhookDispatchResult(List.of()));

        Map<String, Object> result = handler.executeWithResult(
                plan(Map.of(
                        "eventType", "complaint.escalated",
                        "_eventId", "policy-webhook-evt-1",
                        "caseId", "CMP-1")),
                DecisionContext.of(Map.of()));

        assertThat(result).containsEntry("deliveryEventId", "policy-webhook-evt-1");
    }

    @Test
    void rejectsOverlongCallerSuppliedDeliveryEventIdBeforeDispatch() {
        MetaContext.setCurrentTenantId(42L);
        String overlongEventId = "x".repeat(65);

        assertThatThrownBy(() -> handler.executeWithResult(
                plan(Map.of(
                        "eventType", "complaint.escalated",
                        "_eventId", overlongEventId,
                        "caseId", "CMP-1")),
                DecisionContext.of(Map.of())))
                .isInstanceOf(ActionExecutionException.class)
                .hasMessage("WEBHOOK payload._eventId must be 64 characters or fewer (current: 65)")
                .satisfies(error -> {
                    Map<String, Object> payload = ((ActionExecutionException) error).resultPayload();
                    assertThat(payload)
                            .containsEntry("eventType", "complaint.escalated")
                            .containsEntry("deliveryEventId", overlongEventId)
                            .containsEntry("deliveryTraceStatus", "validation_failed")
                            .containsEntry("validationError", "payload._eventId exceeds max length")
                            .containsEntry("field", "payload._eventId")
                            .containsEntry("actualLength", 65)
                            .containsEntry("maxLength", 64);
                });
        verifyNoInteractions(dispatcher);
    }

    @Test
    void throwsWhenEventTypeMissing() {
        MetaContext.setCurrentTenantId(42L);
        assertThatThrownBy(() -> handler.execute(plan(Map.of("caseId", "CMP-1")), DecisionContext.of(Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void throwsWhenNoTenantContext() {
        assertThatThrownBy(() -> handler.execute(plan(Map.of("eventType", "x")), DecisionContext.of(Map.of())))
                .isInstanceOf(IllegalStateException.class);
    }
}
