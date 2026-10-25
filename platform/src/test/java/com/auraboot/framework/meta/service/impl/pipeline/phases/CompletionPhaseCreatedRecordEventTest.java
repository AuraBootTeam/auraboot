package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.module.meta.event.DomainEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompletionPhaseCreatedRecordEventTest {
    @Test
    void declaredCustomCreatePublishesServerReturnedRecordIdentity() {
        publish(customCreate(), null, Map.of("recordPid", "client-record"),
                Map.of(), Map.of("recordPid", "created-record"), "created-record");
    }

    @Test
    void standardCreatePublishesFieldMapRecordIdentity() {
        publish(Map.of("type", "create"), null, Map.of(),
                Map.of("recordPid", "mapped-record"), Map.of(), "mapped-record");
    }

    @Test
    void explicitTargetCannotBeOverriddenByHandlerResult() {
        publish(customCreate(), "owned-record", Map.of("recordPid", "client-record"),
                Map.of("recordPid", "mapped-record"), Map.of("recordPid", "other-record"), "owned-record");
    }

    @Test
    void readResultIsNotTreatedAsCreatedRecord() {
        publish(Map.of("type", "custom", "handlerParams", Map.of("targetRecordAction", "read")),
                null, Map.of(), Map.of(), Map.of("recordPid", "related-record"), null);
    }

    @Test
    void creationCannotTakeRecordIdentityFromClientPayload() {
        publish(customCreate(), null, Map.of("recordPid", "client-record"), Map.of(), Map.of(), null);
    }

    @Test
    void blankOrNonStringResultsCannotBecomeRecordIdentity() {
        publish(customCreate(), null, Map.of(), Map.of(), Map.of("recordPid", "  "), null);
        publish(customCreate(), null, Map.of(), Map.of(), Map.of("recordPid", 123L), null);
    }

    private Map<String, Object> customCreate() {
        return Map.of("type", "custom", "handlerParams", Map.of("targetRecordAction", "create"));
    }

    private void publish(Map<String, Object> config, String target, Map<String, Object> payload,
                         Map<String, Object> mapped, Map<String, Object> handled, String expectedRecord) {
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        CompletionPhase phase = new CompletionPhase(null, null, publisher, null, null, null, null, null);
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordId(target);
        CommandDefinition command = new CommandDefinition();
        command.setCode("fixture:create");
        command.setModelCode("fixture");
        CommandPipelineContext context = CommandPipelineContext.builder()
                .request(request).command(command).tenantId(99L).userId(11L)
                .execConfig(config).payload(payload).fieldMapResults(mapped).handlerResults(handled).build();
        ReflectionTestUtils.invokeMethod(phase, "publishDomainEvent", context);
        verify(publisher).publishCommandCompleted(eq("fixture:create"), eq((String) config.get("type")),
                eq(99L), eq(expectedRecord), eq("fixture"), same(payload), eq(11L), nullable(String.class), anyMap());
    }
}
