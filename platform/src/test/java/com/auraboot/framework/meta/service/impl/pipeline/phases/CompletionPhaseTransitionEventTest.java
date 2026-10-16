package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.module.meta.event.DomainEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompletionPhaseTransitionEventTest {
    @Test void configuredTransitionRetainsPublicOperationAndPublishesAuthoritativeState() {
        Map<String, Object> metadata = publish(Map.of("type", "state_transition",
                "stateField", "order_status", "toState", "done"));
        assertThat(metadata).containsEntry("beforeSnapshot", Map.of("order_status", "in_review"));
        assertThat(metadata).containsEntry("commandStateTransition",
                Map.of("stateField", "order_status", "toState", "done"));
    }

    @Test void ordinaryOrIncompleteExecutionCannotPublishTransitionMetadata() {
        assertThat(publish(Map.of("type", "update", "stateField", "order_status", "toState", "done")))
                .doesNotContainKey("commandStateTransition");
        assertThat(publish(Map.of("type", "state_transition", "stateField", "order_status")))
                .doesNotContainKey("commandStateTransition");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> publish(Map<String, Object> config) {
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        CompletionPhase phase = new CompletionPhase(null, null, publisher, null, null, null, null, null);
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("UPDATE");
        request.setTargetRecordId("owned-record");
        CommandDefinition command = new CommandDefinition();
        command.setCode("orders:complete");
        command.setModelCode("orders");
        Map<String, Object> payload = Map.of("status", "client-value");
        CommandPipelineContext context = CommandPipelineContext.builder()
                .request(request).command(command).tenantId(99L).userId(11L)
                .execConfig(config).payload(payload).beforeSnapshot(Map.of("order_status", "in_review")).build();
        ReflectionTestUtils.invokeMethod(phase, "publishDomainEvent", context);
        ArgumentCaptor<Map> metadata = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishCommandCompleted(eq("orders:complete"), eq("UPDATE"), eq(99L),
                eq("owned-record"), eq("orders"), same(payload), eq(11L), isNull(), metadata.capture());
        return metadata.getValue();
    }
}
