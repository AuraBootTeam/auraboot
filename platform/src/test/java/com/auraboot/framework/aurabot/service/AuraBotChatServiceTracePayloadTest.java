package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuraBotChatServiceTracePayloadTest {

    @Test
    @DisplayName("prompt span output keeps full prompt text alongside char count")
    void buildPromptSpanOutput_keepsFullPromptText() {
        String prompt = "system prompt body";

        Map<String, Object> payload = AuraBotChatService.buildPromptSpanOutput(prompt);

        assertThat(payload).containsEntry("system_prompt", prompt);
        assertThat(payload).containsEntry("char_count", prompt.length());
    }

    @Test
    @DisplayName("generation span input keeps model, system prompt, messages and tools")
    void buildGenerationSpanInput_keepsRequestDetails() {
        LlmChatRequest request = LlmChatRequest.builder()
                .model("gpt-test")
                .systemPrompt("system prompt")
                .messages(List.of(
                        LlmChatRequest.Message.builder()
                                .role("user")
                                .content("hello")
                                .build()))
                .tools(List.of(
                        LlmChatRequest.Tool.builder()
                                .name("lookup_account")
                                .description("Lookup account")
                                .inputSchema(Map.of("type", "object"))
                                .build()))
                .maxTokens(2048)
                .build();

        Map<String, Object> payload = AuraBotChatService.buildGenerationSpanInput(request);

        assertThat(payload).containsEntry("model", "gpt-test");
        assertThat(payload).containsEntry("system_prompt", "system prompt");
        assertThat(payload).containsEntry("max_tokens", 2048);
        assertThat((List<?>) payload.get("messages")).hasSize(1);
        assertThat((List<?>) payload.get("tools")).hasSize(1);
    }

    @Test
    @DisplayName("resolve tools span keeps inspectable request and selected tools")
    void buildResolveToolsPayloads_keepUsefulDetails() {
        Map<String, Object> input = AuraBotChatService.buildResolveToolsSpanInput(
                "find accounts", "crm_account", "rec_001");
        Map<String, Object> output = AuraBotChatService.buildResolveToolsSpanOutput(List.of(
                LlmChatRequest.Tool.builder()
                        .name("lookup_account")
                        .description("Lookup account")
                        .build()));

        assertThat(input).containsEntry("message", "find accounts");
        assertThat(input).containsEntry("model_code", "crm_account");
        assertThat(output).containsEntry("tool_count", 1);
        assertThat((List<?>) output.get("tools")).hasSize(1);
    }

    @Test
    @DisplayName("generation span output keeps full response content and token metadata")
    void buildGenerationSpanOutput_keepsResponseDetails() {
        LlmChatResponse response = LlmChatResponse.builder()
                .stopReason("tool_use")
                .inputTokens(321)
                .outputTokens(654)
                .content(List.of(
                        LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("thinking")
                                .build(),
                        LlmChatResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("tool_1")
                                .name("lookup_account")
                                .input(Map.of("accountId", "A-001"))
                                .build()))
                .build();

        Map<String, Object> payload = AuraBotChatService.buildGenerationSpanOutput(response);

        assertThat(payload).containsEntry("stop_reason", "tool_use");
        assertThat(payload).containsEntry("input_tokens", 321);
        assertThat(payload).containsEntry("output_tokens", 654);
        assertThat((List<?>) payload.get("content")).hasSize(2);
    }

    @Test
    @DisplayName("tool execution allowlist rejects names that were not exposed to the LLM")
    void isToolOffered_rejectsUnavailableToolName() {
        List<LlmChatRequest.Tool> tools = List.of(
                LlmChatRequest.Tool.builder().name("nq_crm_lead_pipeline_stats").build(),
                LlmChatRequest.Tool.builder().name("platform_fill_form").build());

        assertThat(AuraBotChatService.isToolOffered(tools, "nq_crm_lead_pipeline_stats")).isTrue();
        assertThat(AuraBotChatService.isToolOffered(tools, "platform_execute_sql")).isFalse();
        assertThat(AuraBotChatService.isToolOffered(tools, null)).isFalse();
    }
}
