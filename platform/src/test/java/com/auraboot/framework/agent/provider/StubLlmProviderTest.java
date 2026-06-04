package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StubLlmProvider")
class StubLlmProviderTest {

    private final StubLlmProvider provider = new StubLlmProvider();

    @Test
    @DisplayName("scripted tool_use marker produces one deterministic tool call")
    void scriptedToolUseMarkerProducesDeterministicToolCall() {
        String message = "Create model\n" + StubLlmProvider.TOOL_USE_MARKER + " "
                + "{\"id\":\"toolu-skill\",\"name\":\"aurabot:model:create\","
                + "\"input\":{\"code\":\"crm_customer\"}}";

        LlmChatResponse response = provider.chat(LlmChatRequest.builder()
                .messages(List.of(LlmChatRequest.Message.text("user", message)))
                .build(), StubLlmProvider.STUB_API_KEY_SENTINEL, "stub://local");

        assertThat(response.getStopReason()).isEqualTo("tool_use");
        assertThat(response.getContent()).hasSize(1);
        LlmChatResponse.ContentBlock block = response.getContent().get(0);
        assertThat(block.getType()).isEqualTo("tool_use");
        assertThat(block.getId()).isEqualTo("toolu-skill");
        assertThat(block.getName()).isEqualTo("aurabot:model:create");
        assertThat(block.getInput()).isEqualTo(Map.of("code", "crm_customer"));
    }

    @Test
    @DisplayName("scripted marker is not repeated after tool_result and final text carries result digest")
    void scriptedToolUseMarkerIsIgnoredAfterToolResult() {
        String message = StubLlmProvider.TOOL_USE_MARKER + " "
                + "{\"id\":\"toolu-skill\",\"name\":\"aurabot:model:create\","
                + "\"input\":{\"code\":\"crm_customer\"}}";

        LlmChatResponse response = provider.chat(LlmChatRequest.builder()
                .messages(List.of(
                        LlmChatRequest.Message.text("user", message),
                        LlmChatRequest.Message.builder()
                                .role("user")
                                .content(List.of(LlmChatRequest.ContentBlock.builder()
                                        .type("tool_result")
                                        .toolUseId("toolu-skill")
                                        .result(Map.of("success", true))
                                        .build()))
                                .build()))
                .build(), StubLlmProvider.STUB_API_KEY_SENTINEL, "stub://local");

        assertThat(response.getStopReason()).isEqualTo("end_turn");
        assertThat(response.getContent().get(0).getText())
                .contains("[stub response]")
                .contains("\"success\":true");
    }

    @Test
    @DisplayName("tool_result JSON string is surfaced in deterministic final text")
    void toolResultJsonStringIsSurfacedInFinalText() {
        String supplierResult = """
                {"success":true,"records":[{"supplier_name":"Shenzhen Precision Components","supplier_id":"SUP-1"}]}
                """;

        LlmChatResponse response = provider.chat(LlmChatRequest.builder()
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user")
                        .content(List.of(LlmChatRequest.ContentBlock.builder()
                                .type("tool_result")
                                .toolUseId("toolu-query")
                                .result(supplierResult)
                                .build()))
                        .build()))
                .build(), StubLlmProvider.STUB_API_KEY_SENTINEL, "stub://local");

        assertThat(response.getStopReason()).isEqualTo("end_turn");
        assertThat(response.getContent().get(0).getText())
                .contains("[stub response]")
                .contains("Shenzhen Precision Components")
                .contains("SUP-1");
    }

    @Test
    @DisplayName("new user marker after an older tool_result produces the next deterministic tool call")
    void scriptedToolUseMarkerAfterOlderToolResultProducesNextCall() {
        String first = StubLlmProvider.TOOL_USE_MARKER + " "
                + "{\"id\":\"toolu-query\",\"name\":\"nq_customer_stats\","
                + "\"input\":{\"tenantId\":\"1\"}}";
        String second = StubLlmProvider.TOOL_USE_MARKER + " "
                + "{\"id\":\"toolu-create\",\"name\":\"cmd_create_report\","
                + "\"input\":{\"title\":\"Customer stats\"}}";

        LlmChatResponse response = provider.chat(LlmChatRequest.builder()
                .messages(List.of(
                        LlmChatRequest.Message.text("user", first),
                        LlmChatRequest.Message.builder()
                                .role("user")
                                .content(List.of(LlmChatRequest.ContentBlock.builder()
                                        .type("tool_result")
                                        .toolUseId("toolu-query")
                                        .result(Map.of("success", true))
                                        .build()))
                                .build(),
                        LlmChatRequest.Message.text("user", second)))
                .build(), StubLlmProvider.STUB_API_KEY_SENTINEL, "stub://local");

        assertThat(response.getStopReason()).isEqualTo("tool_use");
        assertThat(response.getContent().get(0).getName()).isEqualTo("cmd_create_report");
        assertThat(response.getContent().get(0).getInput()).isEqualTo(Map.of("title", "Customer stats"));
    }

    // --- E2E observability reply delay (DELAY_MARKER) ---
    // The group-agent named-agent turn runs the synchronous tool loop
    // (ChatTurnRuntime.runToolLoop -> callProvider -> chat()), so the instant
    // stub reply collapses the ai_turn_started -> ai_turn_completed window to a
    // few milliseconds and the iOS/Android "typing bubble" flashes too fast to
    // observe. A bounded delay inside chat() keeps the turn observably in-flight.

    @Test
    @DisplayName("no delay marker -> resolveStubDelayMs returns 0")
    void noDelayMarkerMeansZeroDelay() {
        assertThat(provider.resolveStubDelayMs(userReq("Hello, please reply."))).isZero();
    }

    @Test
    @DisplayName("bare delay marker -> default delay")
    void bareDelayMarkerUsesDefault() {
        assertThat(provider.resolveStubDelayMs(userReq("trigger " + StubLlmProvider.DELAY_MARKER)))
                .isEqualTo(StubLlmProvider.DEFAULT_STUB_DELAY_MS);
    }

    @Test
    @DisplayName("delay marker with =millis -> parsed value")
    void delayMarkerWithMillisIsParsed() {
        assertThat(provider.resolveStubDelayMs(
                userReq("trigger " + StubLlmProvider.DELAY_MARKER + "=350 tail")))
                .isEqualTo(350L);
    }

    @Test
    @DisplayName("delay marker above the cap is clamped to MAX_STUB_DELAY_MS")
    void delayMarkerIsClampedToMax() {
        assertThat(provider.resolveStubDelayMs(
                userReq("x " + StubLlmProvider.DELAY_MARKER + "=99999")))
                .isEqualTo(StubLlmProvider.MAX_STUB_DELAY_MS);
    }

    @Test
    @DisplayName("delay marker is read from the latest user message only")
    void delayMarkerReadFromLatestUserMessage() {
        LlmChatRequest request = LlmChatRequest.builder()
                .messages(List.of(
                        LlmChatRequest.Message.text("user", "older message"),
                        LlmChatRequest.Message.text("assistant", "[stub response]"),
                        LlmChatRequest.Message.text("user", "newest " + StubLlmProvider.DELAY_MARKER + "=200")))
                .build();
        assertThat(provider.resolveStubDelayMs(request)).isEqualTo(200L);
    }

    @Test
    @DisplayName("chat() blocks for the requested delay before returning the stub response")
    void chatBlocksForRequestedDelay() {
        long start = System.nanoTime();
        LlmChatResponse response = provider.chat(
                userReq("D1 trigger " + StubLlmProvider.DELAY_MARKER + "=250"),
                StubLlmProvider.STUB_API_KEY_SENTINEL, "stub://local");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(230L);
        assertThat(response.getStopReason()).isEqualTo("end_turn");
        assertThat(response.getContent().get(0).getText()).contains("[stub response]");
    }

    @Test
    @DisplayName("chat() without a delay marker returns promptly")
    void chatWithoutDelayReturnsPromptly() {
        long start = System.nanoTime();
        provider.chat(userReq("plain reply"),
                StubLlmProvider.STUB_API_KEY_SENTINEL, "stub://local");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(150L);
    }

    private static LlmChatRequest userReq(String userText) {
        return LlmChatRequest.builder()
                .messages(List.of(LlmChatRequest.Message.text("user", userText)))
                .build();
    }
}
