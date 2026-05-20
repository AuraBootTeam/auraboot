package com.auraboot.framework.agent.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentErrorFrame")
class AgentErrorFrameTest {

    @Test
    @DisplayName("hashes args and exposes only compact recovery fields")
    void hashesArgsAndExposesCompactRecoveryFields() {
        AgentErrorFrame first = AgentErrorFrame.of(
                AgentErrorFrame.CATEGORY_TOOL,
                "customer_lookup",
                Map.of("customerName", "secret customer", "apiKey", "sk-secret"),
                "IllegalStateException",
                true,
                "Tool execution failed.",
                "Use corrected arguments or summarize the failure to the user.");
        AgentErrorFrame second = AgentErrorFrame.of(
                AgentErrorFrame.CATEGORY_TOOL,
                "customer_lookup",
                Map.of("apiKey", "sk-secret", "customerName", "secret customer"),
                "IllegalStateException",
                true,
                "Tool execution failed.",
                "Use corrected arguments or summarize the failure to the user.");

        assertThat(first.schemaVersion()).isEqualTo("agent-error-frame/v1");
        assertThat(first.category()).isEqualTo(AgentErrorFrame.CATEGORY_TOOL);
        assertThat(first.toolName()).isEqualTo("customer_lookup");
        assertThat(first.argsHash()).hasSize(64).isEqualTo(second.argsHash());
        assertThat(first.retryable()).isTrue();

        Map<String, Object> snapshot = first.toSnapshotMap();
        assertThat(snapshot)
                .containsEntry("category", AgentErrorFrame.CATEGORY_TOOL)
                .containsEntry("toolName", "customer_lookup")
                .containsEntry("errorClass", "IllegalStateException")
                .containsEntry("retryable", true)
                .containsEntry("userSafeMessage", "Tool execution failed.");
        assertThat(String.valueOf(snapshot))
                .doesNotContain("secret customer")
                .doesNotContain("sk-secret");
    }

    @Test
    @DisplayName("supports provider and validation categories")
    void supportsProviderAndValidationCategories() {
        AgentErrorFrame providerFrame = AgentErrorFrame.of(
                AgentErrorFrame.CATEGORY_PROVIDER,
                null,
                Map.of("providerCode", "openai", "apiKey", "sk-secret"),
                "IllegalArgumentException",
                false,
                "LLM provider request failed.",
                "Stop the turn and ask an operator to check provider configuration.");
        AgentErrorFrame validationFrame = AgentErrorFrame.of(
                AgentErrorFrame.CATEGORY_VALIDATION,
                "platform_create_model",
                Map.of("description", "secret model description"),
                "UnknownTool",
                true,
                "The model requested an unavailable tool.",
                "Call one of the available tools from the tool schema.");

        assertThat(providerFrame.toSnapshotMap())
                .containsEntry("category", AgentErrorFrame.CATEGORY_PROVIDER)
                .containsEntry("retryable", false);
        assertThat(validationFrame.toSnapshotMap())
                .containsEntry("category", AgentErrorFrame.CATEGORY_VALIDATION)
                .containsEntry("retryable", true)
                .containsEntry("toolName", "platform_create_model");
        assertThat(String.valueOf(providerFrame.toSnapshotMap())).doesNotContain("sk-secret");
        assertThat(String.valueOf(validationFrame.toSnapshotMap())).doesNotContain("secret model description");
    }
}
