package com.auraboot.framework.agent.runtime.context;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationContextWindowManagerTest {

    private final ConversationContextWindowManager manager =
            new ConversationContextWindowManager();

    @Test
    void compactsOldTextWithVersionedProvenanceAndPreservesRecentMessages() {
        List<LlmChatRequest.Message> source = textTape(18, 2_200);
        List<LlmChatRequest.Message> recent =
                List.copyOf(source.subList(source.size() - 4, source.size()));

        ConversationContextWindowManager.CompactionResult result =
                manager.compact(source, 6_000);

        assertThat(result.compacted()).isTrue();
        assertThat(result.summaryVersion())
                .isEqualTo("conversation-summary/v1");
        assertThat(result.sourceHash()).hasSize(64);
        assertThat(result.messages().get(0).getContent().toString())
                .contains("source-count=\"")
                .contains("source-hash=\"" + result.sourceHash() + "\"")
                .contains("recent-wins;structured-protocol-preserved");
        assertThat(result.messages())
                .endsWith(recent.toArray(LlmChatRequest.Message[]::new));
        assertThat(result.outputChars()).isLessThanOrEqualTo(result.charBudget());
    }

    @Test
    void redactsCredentialValuesFromPersistableSummary() {
        List<LlmChatRequest.Message> source = textTape(16, 2_000);
        source.get(0).setContent(
                "api_key=super-secret-live-value " + "x".repeat(2_000));

        ConversationContextWindowManager.CompactionResult result =
                manager.compact(source, 6_000);

        String summary = String.valueOf(
                result.messages().get(0).getContent());
        assertThat(summary)
                .contains("api_key=[REDACTED]")
                .doesNotContain("super-secret-live-value");
    }

    @Test
    void failsPredictablyInsteadOfBreakingStructuredToolProtocol() {
        List<LlmChatRequest.Message> source = new ArrayList<>();
        source.add(LlmChatRequest.Message.builder()
                .role("assistant")
                .content(List.of(LlmChatRequest.ContentBlock.builder()
                        .type("tool_use")
                        .id("TOOL_1")
                        .name("lookup")
                        .input(java.util.Map.of("query", "x".repeat(30_000)))
                        .build()))
                .build());
        source.addAll(textTape(4, 2_000));

        assertThatThrownBy(() -> manager.compact(source, 6_000))
                .isInstanceOf(
                        ConversationContextWindowManager
                                .ContextWindowExceededException.class)
                .hasMessageContaining(
                        "CONTEXT_WINDOW_STRUCTURED_TAPE_EXCEEDED");
    }

    @Test
    void leavesSmallTapeUnchanged() {
        List<LlmChatRequest.Message> source = textTape(4, 20);

        ConversationContextWindowManager.CompactionResult result =
                manager.compact(source, 6_000);

        assertThat(result.compacted()).isFalse();
        assertThat(result.messages()).containsExactlyElementsOf(source);
    }

    private List<LlmChatRequest.Message> textTape(
            int count,
            int contentChars) {
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            messages.add(LlmChatRequest.Message.text(
                    index % 2 == 0 ? "user" : "assistant",
                    "message-" + index + " " + "x".repeat(contentChars)));
        }
        return messages;
    }
}
