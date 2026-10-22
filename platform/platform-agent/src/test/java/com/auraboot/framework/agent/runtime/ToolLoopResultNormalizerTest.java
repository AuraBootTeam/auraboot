package com.auraboot.framework.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolLoopResultNormalizer")
class ToolLoopResultNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("compacts raw tool error strings into safe error frames")
    void compactsRawToolErrorStringsIntoSafeErrorFrames() {
        Map<String, Object> result = ToolLoopResultNormalizer.normalize(
                objectMapper,
                "Error: database password=secret-db exploded apiKey=sk-secret",
                "customer_stats",
                Map.of("customerId", "c-1", "token", "tok-secret"));

        assertThat(result)
                .containsEntry("success", false)
                .containsEntry("error", "Tool execution failed.")
                .containsEntry("retryable", true)
                .containsEntry("durationMs", 0L);
        assertThat((Map<String, Object>) result.get("errorFrame"))
                .containsEntry("category", AgentErrorFrame.CATEGORY_TOOL)
                .containsEntry("toolName", "customer_stats")
                .containsEntry("errorClass", "ToolReturnedError")
                .containsEntry("userSafeMessage", "Tool execution failed.");
        assertThat(String.valueOf(result))
                .doesNotContain("secret-db")
                .doesNotContain("sk-secret")
                .doesNotContain("tok-secret");
    }

    @Test
    @DisplayName("compacts JSON failure results unless they are pending control payloads")
    void compactsJsonFailureResultsUnlessTheyArePendingControlPayloads() {
        Map<String, Object> failure = ToolLoopResultNormalizer.normalize(
                objectMapper,
                """
                {"success":false,"error":"raw stack apiKey=sk-secret","durationMs":42}
                """,
                "customer_stats",
                Map.of("customerId", "c-1"));

        assertThat(failure)
                .containsEntry("success", false)
                .containsEntry("error", "Tool execution failed.")
                .containsEntry("durationMs", 42);
        assertThat((Map<String, Object>) failure.get("errorFrame"))
                .containsEntry("errorClass", "ToolReturnedError");
        assertThat(String.valueOf(failure)).doesNotContain("sk-secret");

        Map<String, Object> approval = ToolLoopResultNormalizer.normalize(
                objectMapper,
                """
                {"success":false,"error":"approval required","approvalRequired":true,"approvalPid":"ap-1"}
                """,
                "create_task",
                Map.of());

        assertThat(approval)
                .containsEntry("success", false)
                .containsEntry("approvalRequired", true)
                .containsEntry("approvalPid", "ap-1")
                .doesNotContainKey("errorFrame");
    }

    @Test
    @DisplayName("normalizes successful plain text and JSON payloads")
    void normalizesSuccessfulPlainTextAndJsonPayloads() {
        Map<String, Object> plain = ToolLoopResultNormalizer.normalize(
                objectMapper,
                "customer count: 3",
                "customer_stats",
                Map.of());

        assertThat(plain)
                .containsEntry("success", true)
                .containsEntry("data", "customer count: 3")
                .containsEntry("durationMs", 0L);

        Map<String, Object> json = ToolLoopResultNormalizer.normalize(
                objectMapper,
                """
                {"data":{"count":3},"durationMs":7}
                """,
                "customer_stats",
                Map.of());

        assertThat(json)
                .containsEntry("success", true)
                .containsEntry("durationMs", 7);
        assertThat((Map<String, Object>) json.get("data")).containsEntry("count", 3);
    }
}
