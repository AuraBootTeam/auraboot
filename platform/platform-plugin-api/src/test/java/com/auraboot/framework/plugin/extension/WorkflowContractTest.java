package com.auraboot.framework.plugin.extension;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class WorkflowContractTest {
    @Test
    void eventPreservesMetadataAndSnapshotsPayload() {
        Map<String, Object> payload = new HashMap<>(Map.of("status", "done"));
        Instant before = Instant.now();
        WorkflowEvent event = new WorkflowEvent(7L, "COMPLETED", "BPM", "approval", "instance", "node", payload);
        payload.put("status", "changed");
        assertThat(event.getTenantId()).isEqualTo(7L);
        assertThat(event.getEventType()).isEqualTo("bpm:completed");
        assertThat(event.getWorkflowEventType()).isEqualTo("completed");
        assertThat(event.getSourceType()).isEqualTo("BPM");
        assertThat(event.getProcessKey()).isEqualTo("approval");
        assertThat(event.getInstanceId()).isEqualTo("instance");
        assertThat(event.getNodeId()).isEqualTo("node");
        assertThat(event.getPayload()).containsExactlyEntriesOf(Map.of("status", "done"));
        assertThatThrownBy(() -> event.getPayload().put("status", "bad")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(UUID.fromString(event.getEventId()).toString()).isEqualTo(event.getEventId());
        assertThat(event.getOccurredAt()).isBetween(before, Instant.now());
        assertThat(new WorkflowEvent(7L, "COMPLETED", "BPM", null, null, null, null).getEventId()).isNotEqualTo(event.getEventId());
    }

    @Test
    void missingEventMetadataUsesDocumentedDefaults() {
        WorkflowEvent absent = new WorkflowEvent(null, null, null, null, null, null, null);
        assertThat(absent.getSourceType()).isEqualTo("workflow");
        assertThat(absent.getEventType()).isNull();
        assertThat(absent.getWorkflowEventType()).isNull();
        assertThat(absent.getPayload()).isEmpty();
        assertThat(new WorkflowEvent(1L, "STARTED", null, null, null, null, null).getEventType()).isEqualTo("workflow:started");
    }

    @Test
    void eventTokensDoNotDependOnHostLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            WorkflowEvent event = new WorkflowEvent(1L, "INITIALIZED", "INTEGRATION", null, null, null, null);
            assertThat(event.getEventType()).isEqualTo("integration:initialized");
            assertThat(event.getWorkflowEventType()).isEqualTo("initialized");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void requestAndResultDefensivelyCopyPayloads() {
        Map<String, Object> payload = new HashMap<>(Map.of("action", "approve"));
        var request = new WorkflowCapability.WorkflowRequest(7L, 9L, payload);
        var result = new WorkflowCapability.WorkflowResult(payload);
        payload.clear();
        assertThat(request.tenantId()).isEqualTo(7L);
        assertThat(request.actorUserId()).isEqualTo(9L);
        assertThat(request.payload()).containsEntry("action", "approve");
        assertThat(result.payload()).containsEntry("action", "approve");
        assertThatThrownBy(() -> request.payload().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.payload().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new WorkflowCapability.WorkflowRequest(null, null, null).payload()).isEmpty();
        assertThat(new WorkflowCapability.WorkflowResult(null).payload()).isEmpty();
        assertThat(WorkflowCapability.WorkflowResult.empty().payload()).isEmpty();
    }

    @Test
    void failureRemainsAnExceptionWhilePreservingPartialState() {
        var cause = new IllegalStateException("provider unavailable");
        Map<String, Object> payload = new HashMap<>(Map.of("completed", 2));
        var failure = new WorkflowCapability.WorkflowExecutionException("partial failure", cause, payload);
        payload.clear();
        assertThat(failure).hasMessage("partial failure").hasCause(cause);
        assertThat(failure.payload()).containsEntry("completed", 2);
        assertThatThrownBy(() -> failure.payload().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new WorkflowCapability.WorkflowExecutionException("failure", null, null).payload()).isEmpty();
    }
}
