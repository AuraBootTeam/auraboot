package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.LlmChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LlmResponseGuard")
class LlmResponseGuardTest {

    @Test
    @DisplayName("requireContent rejects null responses with operation context")
    void requireContent_nullResponse_throwsOperationSpecificFailure() {
        assertThatThrownBy(() -> LlmResponseGuard.requireContent(null, "ACP plan step"))
                .isInstanceOf(LlmResponseGuard.EmptyLlmResponseException.class)
                .hasMessage("Empty response from LLM during ACP plan step");
    }

    @Test
    @DisplayName("requireContent rejects empty content blocks")
    void requireContent_emptyContent_throwsOperationSpecificFailure() {
        LlmChatResponse response = LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of())
                .build();

        assertThatThrownBy(() -> LlmResponseGuard.requireContent(response, "ACP replan"))
                .isInstanceOf(LlmResponseGuard.EmptyLlmResponseException.class)
                .hasMessage("Empty response from LLM during ACP replan");
    }

    @Test
    @DisplayName("requireContent returns valid responses unchanged")
    void requireContent_validResponse_returnsSameInstance() {
        LlmChatResponse response = LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text")
                        .text("ok")
                        .build()))
                .build();

        assertThat(LlmResponseGuard.requireContent(response, "ACP agent loop"))
                .isSameAs(response);
    }
}
