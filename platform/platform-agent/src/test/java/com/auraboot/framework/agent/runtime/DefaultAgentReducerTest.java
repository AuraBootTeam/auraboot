package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.conversation.TurnContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultAgentReducer")
class DefaultAgentReducerTest {

    private final AgentRuntimeStateFactory stateFactory = new AgentRuntimeStateFactory();
    private final DefaultAgentReducer reducer = new DefaultAgentReducer();

    @Test
    @DisplayName("replays model/tool/result events into deterministic state and effects")
    void replayProducesDeterministicStateAndEffects() {
        AgentExecutionState initial = baseState();

        AgentReducer.Result firstResult = replay(initial);
        AgentReducer.Result secondResult = replay(initial);

        AgentExecutionState finalState = firstResult.state();
        assertThat(finalState.stateHash()).hasSize(64).isEqualTo(secondResult.state().stateHash());
        assertThat(finalState.pending())
                .containsEntry("eventCount", 3)
                .containsEntry("lastEventType", AgentRuntimeEvent.TOOL_RESULT_RECORDED)
                .containsEntry("lastToolName", "customer_lookup");
        assertThat(finalState.pending()).containsKey("lastPayloadHash");
        assertThat(String.valueOf(finalState.pending()))
                .doesNotContain("secret customer name")
                .doesNotContain("raw provider text");

        assertThat(firstResult.effects())
                .extracting(AgentRuntimeEffect::type)
                .containsExactly(AgentRuntimeEffect.CONTINUE_MODEL_CALL);
    }

    @Test
    @DisplayName("confirmation-required event emits suspend effect")
    void confirmationRequiredEmitsSuspendEffect() {
        AgentExecutionState initial = baseState();

        AgentReducer.Result result = reducer.reduce(initial, AgentRuntimeEvent.confirmationRequired(
                0,
                "toolu-write",
                "customer_create",
                Map.of("customerName", "secret customer name")));

        assertThat(result.state().pending())
                .containsEntry("eventCount", 1)
                .containsEntry("lastEventType", AgentRuntimeEvent.CONFIRMATION_REQUIRED)
                .containsEntry("lastToolId", "toolu-write")
                .containsEntry("lastToolName", "customer_create");
        assertThat(result.effects())
                .extracting(AgentRuntimeEffect::type)
                .containsExactly(AgentRuntimeEffect.SUSPEND_FOR_CONFIRMATION);
        assertThat(String.valueOf(result.state().pending())).doesNotContain("secret customer name");
    }

    private AgentReducer.Result replay(AgentExecutionState initial) {
        AgentReducer.Result result = reducer.reduce(initial,
                AgentRuntimeEvent.modelResponse(0, "tool_use", Map.of("rawText", "raw provider text")));
        result = reducer.reduce(result.state(), AgentRuntimeEvent.toolUseRequested(
                0,
                "toolu-read",
                "customer_lookup",
                Map.of("keyword", "secret customer name"),
                false));
        return reducer.reduce(result.state(), AgentRuntimeEvent.toolResultRecorded(
                0,
                "toolu-read",
                "customer_lookup",
                Map.of("rows", List.of(Map.of("name", "secret customer name")))));
    }

    private AgentExecutionState baseState() {
        ToolDefinition tool = ToolDefinition.builder()
                .toolCode("customer_lookup")
                .description("Lookup customer records")
                .toolType("dsl_query")
                .sourceCode("crm.customer.lookup")
                .riskLevel("L0")
                .confirmationPolicy("none")
                .parameterSchema(Map.of("type", "object"))
                .build();
        return stateFactory.chatTurnState(
                TurnContext.legacyDefault(7L, 100L, 100L),
                "customer_agent",
                "session-1",
                "openai",
                "gpt-test",
                0,
                null,
                "system prompt",
                4096,
                List.of(LlmChatRequest.Message.text("user", "hello")),
                List.of(LlmChatRequest.Tool.builder()
                        .name("customer_lookup")
                        .description("Lookup customer records")
                        .inputSchema(Map.of("type", "object"))
                        .build()),
                List.of(tool),
                Map.of());
    }
}
