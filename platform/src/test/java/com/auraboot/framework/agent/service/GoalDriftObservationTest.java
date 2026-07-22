package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A run that was asked a question and then starts changing things.
 *
 * <p>Nothing could notice this before, because the intent frame is recomputed
 * every turn — by the time a later step ran there was nothing left to compare it
 * against, so "is this still the job we were asked to do" was not a question the
 * system could ask.
 *
 * <p>Recorded rather than refused, on purpose. A real run legitimately touches
 * several objects, and blocking a step on a signal nobody has measured would
 * break working behaviour to prevent a hypothesis. Measuring first is what makes
 * it possible to find out whether refusing would ever be right.
 */
@DisplayName("A write under a read-only opening is noticed")
class GoalDriftObservationTest {

    private final AgentObservationService observations = mock(AgentObservationService.class);
    private final ToolLoopService service = new ToolLoopService(
            mock(ActionRecorder.class), mock(AgentApprovalGateService.class),
            mock(ToolAclChecker.class), mock(com.auraboot.framework.agent.trace.AiTraceService.class),
            mock(com.auraboot.framework.meta.mapper.DynamicDataMapper.class),
            mock(com.auraboot.framework.meta.service.CommandExecutor.class),
            mock(com.auraboot.framework.meta.service.NamedQueryService.class),
            new com.fasterxml.jackson.databind.ObjectMapper(),
            mock(com.auraboot.framework.agent.provider.ToolProviderRegistry.class),
            mock(ResultContractEmitter.class),
            mock(com.auraboot.framework.agent.authorization.RuntimeAuthorizationService.class));

    private AgentToolDefinition tool(String kind) {
        return AgentToolDefinition.builder()
                .name("cmd:crm:" + kind + "_account").toolType("dsl_command").operationKind(kind).build();
    }

    private void notifyDrift(String openingIntent, AgentToolDefinition toolDef) {
        ReflectionTestUtils.setField(service, "agentObservationService", observations);
        if (openingIntent != null) {
            StepContext.setOpeningIntent(openingIntent);
        }
        ReflectionTestUtils.invokeMethod(service, "noteGoalDrift",
                7L, "run-1", "agent-a", toolDef, toolDef == null ? null : toolDef.getName());
    }

    @AfterEach
    void clearContext() {
        StepContext.clearOpeningIntent();
    }

    @ParameterizedTest(name = "opened as {0}, then writes -> recorded")
    @ValueSource(strings = {"query", "analyze", "summarize", "compare", "explain", "report"})
    void writeUnderReadOnlyOpeningIsRecorded(String opening) {
        notifyDrift(opening, tool("delete"));

        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(observations).publish(anyLong(), anyString(), anyString(), any(), anyString(),
                detail.capture());
        assertThat(detail.getValue())
                .as("the record must say what the run was opened to do, or it cannot be judged later")
                .containsEntry("openingIntent", opening)
                .containsEntry("operationKind", "delete");
    }

    @ParameterizedTest(name = "opened as {0} -> a write is the job, not a drift")
    @CsvSource({"create,create", "update,update", "delete,delete", "transition,transition"})
    void writeUnderAWriteOpeningIsNotDrift(String opening, String kind) {
        // The control that keeps this from becoming noise. A run asked to create
        // something and then creating something is the run working.
        notifyDrift(opening, tool(kind));
        verify(observations, never()).publish(anyLong(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("a read under a read-only opening is not drift")
    void readUnderReadOnlyOpeningIsNotDrift() {
        notifyDrift("query", AgentToolDefinition.builder()
                .name("list:crm_account").toolType("dsl_query").operationKind("query").build());
        verify(observations, never()).publish(anyLong(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("with no opening intent recorded, nothing is claimed either way")
    void noOpeningIntentMeansNoClaim() {
        // Silence here is honest: without a starting point there is no drift to
        // measure, and inventing one would put a made-up alert into the same
        // stream the quality judge reads.
        notifyDrift(null, tool("delete"));
        verify(observations, never()).publish(anyLong(), anyString(), anyString(), any(), anyString(), any());
    }
}
