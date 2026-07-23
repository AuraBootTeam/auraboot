package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.dto.ChatRequest;
import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.aurabot.service.AuraBotChatService;
import com.auraboot.framework.agent.runtime.PendingContinuationService;
import com.auraboot.framework.agent.runtime.PendingToolStore;
import com.auraboot.framework.agent.runtime.TurnExecutionPlanner;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The built-in assistant honours its own configured model.
 *
 * <p>A named agent picks its provider from its {@code model} column; the aurabot path did
 * not, and fell to "the first enabled provider with a key" whenever a request arrived
 * without an explicit model or provider — so an admin who set the tenant assistant to qwen
 * still got whichever vendor happened to be first, and the column looked inert. This is the
 * fallback that makes the column mean what it says.
 *
 * <p>The two edges are the point. An explicit per-request choice must always win (otherwise
 * a caller who asked for one vendor silently gets another), and an unset column must change
 * nothing (otherwise every existing tenant's default shifts under them). The fill only
 * happens in the one gap between those: no request choice, and a column that was set on
 * purpose.
 */
@DisplayName("The assistant falls back to its configured model, and only there")
class AssistantModelFallbackTest {

    private final DynamicDataMapper mapper = mock(DynamicDataMapper.class);
    private ConversationTurnServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConversationTurnServiceImpl(
                mock(AuraBotChatService.class),
                mock(PendingContinuationService.class),
                mock(TurnExecutionPlanner.class),
                mock(TurnSideEffects.class),
                mock(PendingToolStore.class),
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "dynamicDataMapper", mapper);
        ReflectionTestUtils.setField(service, "agentChatPort", mock(AgentChatPort.class));
    }

    /** Stub the aurabot row's model column: null → no row, "" → row with null model. */
    private void configuredModel(String model) {
        if (model == null) {
            when(mapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of());
        } else {
            Map<String, Object> row = new HashMap<>();
            row.put("model", model.isEmpty() ? null : model);
            when(mapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of(row));
        }
    }

    private ChatRequest apply(ChatRequest request) {
        ReflectionTestUtils.invokeMethod(service, "applyConfiguredAssistantModel", 7L, request);
        return request;
    }

    private static String modelOf(ChatRequest r) {
        return r.getOptions() == null ? null : r.getOptions().getModel();
    }

    @Test
    @DisplayName("no request choice + a configured model -> the configured model is used")
    void fillsFromConfiguredModel() {
        configuredModel("qwen-plus");
        ChatRequest request = new ChatRequest(); // no options at all

        assertThat(modelOf(apply(request)))
                .as("a request that named neither model nor provider should adopt the assistant's own model")
                .isEqualTo("qwen-plus");
    }

    @Test
    @DisplayName("an explicit request model is never overridden")
    void explicitModelWins() {
        // The direction that matters most: a caller who asked for deepseek must get
        // deepseek, whatever the tenant default is.
        configuredModel("qwen-plus");
        ChatRequest request = new ChatRequest();
        ChatRequest.ChatOptions options = new ChatRequest.ChatOptions();
        options.setModel("deepseek-chat");
        request.setOptions(options);

        assertThat(modelOf(apply(request))).isEqualTo("deepseek-chat");
    }

    @Test
    @DisplayName("an explicit request provider blocks the fallback (the caller already chose a vendor)")
    void explicitProviderBlocksFallback() {
        configuredModel("qwen-plus");
        ChatRequest request = new ChatRequest();
        ChatRequest.ChatOptions options = new ChatRequest.ChatOptions();
        options.setProvider("anthropic");
        request.setOptions(options);

        assertThat(modelOf(apply(request)))
                .as("a request that chose a provider must not have a model quietly grafted on from another vendor")
                .isNull();
    }

    @Test
    @DisplayName("an unset model column changes nothing — existing tenants keep today's default")
    void nullColumnIsNoOp() {
        configuredModel(""); // row exists, model column is null
        ChatRequest request = new ChatRequest();

        assertThat(modelOf(apply(request)))
                .as("a tenant that never configured an assistant model must keep the default-provider behaviour")
                .isNull();
    }

    @Test
    @DisplayName("no aurabot row at all is a no-op, not an error")
    void missingRowIsNoOp() {
        configuredModel(null); // no row
        ChatRequest request = new ChatRequest();

        assertThat(modelOf(apply(request))).isNull();
    }
}
