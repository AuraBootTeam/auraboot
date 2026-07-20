package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LlmMessageTapeSupport")
class LlmMessageTapeSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("F5: unexecuted sibling tool calls get truthful placeholder results")
    void completeDanglingToolResults_answersUnexecutedSiblings() {
        // Exactly the tape shape DeepSeek rejects: one assistant message requesting
        // TWO tools, only one answered (the confirmed one), the sibling dangling.
        // Provider verbatim: "An assistant message with 'tool_calls' must be
        // followed by tool messages responding to each 'tool_call_id'."
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        messages.add(LlmChatRequest.Message.text("user", "list and delete"));
        messages.add(LlmMessageTapeSupport.buildAssistantMessage(List.of(
                LlmChatResponse.ContentBlock.builder()
                        .type("tool_use").id("tool-list").name("list:crm_lead").input(Map.of()).build(),
                LlmChatResponse.ContentBlock.builder()
                        .type("tool_use").id("tool-delete").name("cmd:crm:delete_lead").input(Map.of()).build())));
        messages.add(LlmMessageTapeSupport.buildToolResultMessage(List.of(
                LlmMessageTapeSupport.buildToolResultBlock(objectMapper, "tool-delete",
                        Map.of("success", true)))));

        int synthesized = LlmMessageTapeSupport.completeDanglingToolResults(objectMapper, messages);

        assertThat(synthesized).isEqualTo(1);
        // Every requested id is now answered — the invariant the provider enforces.
        assertThat(collectAnsweredIds(messages)).contains("tool-list", "tool-delete");
        // And the placeholder is honest about not having run.
        assertThat(messages.get(messages.size() - 1).getContent().toString())
                .contains("not executed");
    }

    @Test
    @DisplayName("F5: a tape whose tool calls are all answered is left untouched")
    void completeDanglingToolResults_noopWhenComplete() {
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        messages.add(LlmMessageTapeSupport.buildAssistantMessage(List.of(
                LlmChatResponse.ContentBlock.builder()
                        .type("tool_use").id("tool-only").name("list:crm_lead").input(Map.of()).build())));
        messages.add(LlmMessageTapeSupport.buildToolResultMessage(List.of(
                LlmMessageTapeSupport.buildToolResultBlock(objectMapper, "tool-only",
                        Map.of("success", true)))));
        int before = messages.size();

        assertThat(LlmMessageTapeSupport.completeDanglingToolResults(objectMapper, messages)).isZero();
        assertThat(messages).hasSize(before);
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> collectAnsweredIds(List<LlmChatRequest.Message> messages) {
        java.util.List<String> ids = new ArrayList<>();
        for (LlmChatRequest.Message m : messages) {
            if (!(m.getContent() instanceof List<?> list)) {
                continue;
            }
            for (Object o : list) {
                if (o instanceof LlmChatRequest.ContentBlock b && "tool_result".equals(b.getType())) {
                    ids.add(b.getToolUseId());
                }
            }
        }
        return ids;
    }

    @Test
    @DisplayName("assistant message preserves text and tool_use response blocks")
    void assistantMessagePreservesTextAndToolUseBlocks() {
        LlmChatRequest.Message message = LlmMessageTapeSupport.buildAssistantMessage(List.of(
                LlmChatResponse.ContentBlock.builder()
                        .type("text")
                        .text("I will check that.")
                        .build(),
                LlmChatResponse.ContentBlock.builder()
                        .type("tool_use")
                        .id("toolu_1")
                        .name("lookup_customer")
                        .input(Map.of("customerId", "C-001"))
                        .build()));

        assertThat(message.getRole()).isEqualTo("assistant");
        assertThat(message.getContent()).asList().hasSize(2);

        LlmChatRequest.ContentBlock textBlock = (LlmChatRequest.ContentBlock) ((List<?>) message.getContent()).get(0);
        assertThat(textBlock.getType()).isEqualTo("text");
        assertThat(textBlock.getText()).isEqualTo("I will check that.");

        LlmChatRequest.ContentBlock toolUseBlock = (LlmChatRequest.ContentBlock) ((List<?>) message.getContent()).get(1);
        assertThat(toolUseBlock.getType()).isEqualTo("tool_use");
        assertThat(toolUseBlock.getId()).isEqualTo("toolu_1");
        assertThat(toolUseBlock.getName()).isEqualTo("lookup_customer");
        assertThat(toolUseBlock.getInput()).isEqualTo(Map.of("customerId", "C-001"));
    }

    @Test
    @DisplayName("tool result block serializes structured result as JSON")
    void toolResultBlockSerializesStructuredResultAsJson() throws Exception {
        LlmChatRequest.ContentBlock block = LlmMessageTapeSupport.buildToolResultBlock(
                objectMapper,
                "toolu_2",
                Map.of("success", true, "rows", List.of(Map.of("name", "Alice"))));

        assertThat(block.getType()).isEqualTo("tool_result");
        assertThat(block.getToolUseId()).isEqualTo("toolu_2");
        assertThat(objectMapper.readValue((String) block.getResult(), Map.class))
                .containsEntry("success", true)
                .containsKey("rows");
    }

    @Test
    @DisplayName("message tape serializes and deserializes null safely")
    void messageTapeSerializesAndDeserializesNullSafely() {
        LlmChatRequest.Message userMessage = LlmChatRequest.Message.builder()
                .role("user")
                .content("hello")
                .build();

        List<Map<String, Object>> serialized = LlmMessageTapeSupport.serializeMessages(List.of(userMessage));
        List<LlmChatRequest.Message> restored = LlmMessageTapeSupport.deserializeMessages(serialized);

        assertThat(serialized).containsExactly(Map.of("role", "user", "content", "hello"));
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).getRole()).isEqualTo("user");
        assertThat(restored.get(0).getContent()).isEqualTo("hello");
        assertThat(LlmMessageTapeSupport.serializeMessages(null)).isEmpty();
        assertThat(LlmMessageTapeSupport.deserializeMessages(null)).isEmpty();
    }

    @Test
    @DisplayName("text history builder filters system messages and appends current user message")
    void textHistoryBuilderFiltersSystemMessagesAndAppendsCurrentUserMessage() {
        List<HistoryItem> history = List.of(
                new HistoryItem("system", "hidden prompt"),
                new HistoryItem("user", "previous question"),
                new HistoryItem("assistant", "previous answer"));

        List<LlmChatRequest.Message> messages = LlmMessageTapeSupport.buildTextMessages(
                history,
                HistoryItem::role,
                HistoryItem::content,
                "current question");

        assertThat(messages).extracting(LlmChatRequest.Message::getRole)
                .containsExactly("user", "assistant", "user");
        assertThat(messages).extracting(LlmChatRequest.Message::getContent)
                .containsExactly("previous question", "previous answer", "current question");
    }

    @Test
    @DisplayName("stored tape wins over frontend history before appending current user message")
    void storedTapeWinsOverFrontendHistoryBeforeAppendingCurrentUserMessage() {
        List<Map<String, Object>> stored = List.of(
                Map.of("role", "assistant", "content", "stored answer"));
        List<HistoryItem> frontendHistory = List.of(
                new HistoryItem("user", "frontend question"));

        List<LlmChatRequest.Message> messages = LlmMessageTapeSupport.restoreOrBuildTextMessages(
                stored,
                frontendHistory,
                HistoryItem::role,
                HistoryItem::content,
                "current question");

        assertThat(messages).extracting(LlmChatRequest.Message::getRole)
                .containsExactly("assistant", "user");
        assertThat(messages).extracting(LlmChatRequest.Message::getContent)
                .containsExactly("stored answer", "current question");
    }

    @Test
    @DisplayName("response text extraction removes reasoning tags")
    void responseTextExtractionRemovesReasoningTags() {
        LlmChatResponse response = LlmChatResponse.builder()
                .content(List.of(
                        LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("Visible ")
                                .build(),
                        LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("<think>hidden reasoning</think>answer")
                                .build(),
                        LlmChatResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("toolu_3")
                                .name("lookup_customer")
                                .input(Map.of())
                                .build()))
                .build();

        assertThat(LlmMessageTapeSupport.extractTextFromResponse(response))
                .isEqualTo("Visible answer");
        assertThat(LlmMessageTapeSupport.extractTextFromResponse(null)).isNull();
    }

    private record HistoryItem(String role, String content) {
    }
}
