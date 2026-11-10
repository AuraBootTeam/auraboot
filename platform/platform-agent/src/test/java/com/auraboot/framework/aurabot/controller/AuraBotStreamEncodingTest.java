package com.auraboot.framework.aurabot.controller;

import com.auraboot.framework.aurabot.service.AuraBotChatService;
import com.auraboot.framework.aurabot.service.ChatToolResolver;
import com.auraboot.framework.conversation.ConversationTurnService;
import com.auraboot.framework.conversation.ResponseSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Actual MVC/SSE serialization with a deterministic turn, not a live-model qualification. */
class AuraBotStreamEncodingTest {
    @org.junit.jupiter.api.BeforeEach void identity() {
        com.auraboot.framework.application.tenant.MetaContext.setContext(1L, 2L, "test-user", "Test user");
    }
    @org.junit.jupiter.api.AfterEach void clearIdentity() {
        com.auraboot.framework.application.tenant.MetaContext.clear();
    }
    @ParameterizedTest
    @ValueSource(strings = {"/chat/stream", "/execute"})
    void transportDeclaresAndPreservesUtf8AcrossInitialAndResumedTurns(String endpoint) throws Exception {
        var turns = mock(ConversationTurnService.class);
        var text = "交付材料核对：无需重试 🧭";
        org.mockito.stubbing.Answer<Object> emit = invocation -> {
            ResponseSink sink = invocation.getArgument(invocation.getArguments().length - 1);
            sink.onTextChunk(text); sink.onDone(text, "trace"); return null;
        };
        doAnswer(emit).when(turns).runTurn(any(), any());
        doAnswer(emit).when(turns).resumeTurn(any(), any(), any());
        var controller = new AuraBotController(mock(AuraBotChatService.class), mock(ChatToolResolver.class),
                turns, new ObjectMapper(), Runnable::run, mock(Tracer.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var result = mvc.perform(post("/api/ai/aurabot" + endpoint)
                .contentType("application/json").content("{\"message\":\"read\",\"pendingTurnId\":\"pending\",\"confirmed\":false}")).andReturn();
        if (result.getRequest().isAsyncStarted()) result = mvc.perform(asyncDispatch(result)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        assertThat(result.getResponse().getContentAsString()).contains(text);
        assertThat(result.getResponse().getContentAsByteArray()).containsSubsequence(text.getBytes(StandardCharsets.UTF_8));
    }
}
