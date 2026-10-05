package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalEventAutomationConsumerTest {
    private final AutomationTriggerService trigger = mock(AutomationTriggerService.class);
    private final ExternalEventAutomationConsumer consumer = new ExternalEventAutomationConsumer(trigger);

    @AfterEach
    void clearTrace() { MDC.remove("requestId"); }

    @Test
    void restoresDurableTraceForConsumerAndPreservesWorkerContext() {
        MDC.put("requestId", "worker-context");
        doAnswer(call -> {
            assertThat(MDC.get("requestId")).isEqualTo("original-request");
            assertThat(call.getArgument(4, Map.class)).containsEntry("amount", 10);
            return null;
        }).when(trigger).onExternalEvent("erp", "external.erp.order.created.v1", "event-1", "order/one", Map.of("amount", 10));
        consumer.consume(envelope(true));
        verify(trigger).onExternalEvent("erp", "external.erp.order.created.v1", "event-1", "order/one", Map.of("amount", 10));
        assertThat(MDC.get("requestId")).isEqualTo("worker-context");
    }

    @Test
    void missingTraceCannotBorrowAnotherWorkersRequestId() {
        MDC.put("requestId", "unrelated-worker");
        doAnswer(call -> { assertThat(MDC.get("requestId")).isNull(); return null; })
            .when(trigger).onExternalEvent(any(), any(), any(), any(), any());
        consumer.consume(envelope(false));
        assertThat(MDC.get("requestId")).isEqualTo("unrelated-worker");
    }

    @Test
    void failingConsumerDoesNotLeakRequestContext() {
        doThrow(new IllegalStateException("consumer failed")).when(trigger)
            .onExternalEvent(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> consumer.consume(envelope(true))).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("requestId")).isNull();
    }

    private IntegrationEventEnvelope envelope(boolean withTrace) {
        Map<String, String> headers = new java.util.LinkedHashMap<>(Map.of("sourceCode", "erp", "externalEventId", "event-1"));
        if (withTrace) headers.put("requestId", "original-request");
        return new IntegrationEventEnvelope("1.0", "ext:inst:event-1", "external.erp.order.created.v1", "open-platform/inst/erp",
            "order/one", Instant.now(), 42L, "original-request", null, "order/one", 1, Map.of("amount", 10), headers);
    }
}
