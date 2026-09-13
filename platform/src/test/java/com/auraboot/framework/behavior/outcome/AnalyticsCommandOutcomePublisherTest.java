package com.auraboot.framework.behavior.outcome;

import com.auraboot.framework.agent.service.StepContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalyticsCommandOutcomePublisherTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BehaviorOutcomePublisher outbox = mock(BehaviorOutcomePublisher.class);
    private final AnalyticsCommandOutcomePublisher publisher =
            new AnalyticsCommandOutcomePublisher(jdbc, new ObjectMapper(), outbox);

    @AfterEach void clearRun() { StepContext.clearRunPid(); }

    private CommandPipelineContext context() {
        var request = new CommandExecuteRequest();
        var command = new CommandDefinition();
        command.setModelCode("orders");
        return CommandPipelineContext.builder().tenantId(7L).userId(8L).request(request)
                .commandCode("orders:create").command(command).execConfig(Map.of("type", "create"))
                .handlerResults(Map.of("record", Map.of("pid", "ORDER_1", "secret", "not-for-events")))
                .build();
    }

    private void admitted(String status, long actor, String startedBinding) {
        StepContext.setRunPid("RUN_1");
        when(jdbc.queryForList(anyString(), eq(7L), eq("RUN_1"))).thenReturn(List.of(Map.of(
                "actor_user_id", actor, "run_status", status, "principal_type", "HUMAN_DELEGATED",
                "binding", "{\"analysisId\":\"ANALYSIS_1\"}", "started_event_id", "START_1",
                "started_payload", "{\"analyticsExecution\":" + startedBinding + "}")));
    }

    @Test void emitsOnlyTargetAndTrustedOrigin() {
        admitted("running", 8L, "{\"analysisId\":\"ANALYSIS_1\"}");
        publisher.record(context());
        var event = ArgumentCaptor.forClass(BehaviorOutcomeEvent.class);
        verify(outbox).publish(event.capture());
        assertThat(event.getValue().getTargetKey()).isEqualTo("ORDER_1");
        assertThat(event.getValue().getCausedByEventId()).isEqualTo("START_1");
        assertThat(event.getValue().getInteractionId()).isEqualTo("ANALYSIS_1");
        assertThat(event.getValue().getProps()).containsOnlyKeys(
                "commandCode", "modelCode", "recordPid", "operation", "analyticsExecution", "principalType");
        assertThat(event.getValue().getProps().toString()).doesNotContain("not-for-events");
    }

    @Test void noRunAndDryRunDoNotPublish() {
        var ctx = context();
        publisher.record(ctx);
        StepContext.setRunPid("RUN_1");
        ctx.getRequest().setDryRun(true);
        publisher.record(ctx);
        verifyNoInteractions(jdbc, outbox);
    }

    @Test void mismatchedActorCannotAttributeBusinessResult() {
        admitted("running", 99L, "{\"analysisId\":\"ANALYSIS_1\"}");
        assertThatThrownBy(() -> publisher.record(context())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(outbox);
    }

    @Test void cancelledRunCannotCommitCommandOutcome() {
        admitted("cancelled", 8L, "{\"analysisId\":\"ANALYSIS_1\"}");
        assertThatThrownBy(() -> publisher.record(context())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(outbox);
    }

    @Test void mutatedBindingCannotAttributeBusinessResult() {
        admitted("running", 8L, "{\"analysisId\":\"OTHER\"}");
        assertThatThrownBy(() -> publisher.record(context())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(outbox);
    }

    @Test void outboxFailureEscapesToCommandTransaction() {
        admitted("running", 8L, "{\"analysisId\":\"ANALYSIS_1\"}");
        doThrow(new IllegalStateException("outbox unavailable")).when(outbox).publish(any());
        assertThatThrownBy(() -> publisher.record(context()))
                .isInstanceOf(IllegalStateException.class).hasMessage("outbox unavailable");
    }
}
