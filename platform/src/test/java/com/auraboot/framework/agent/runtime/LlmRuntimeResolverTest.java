package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LlmRuntimeResolver")
class LlmRuntimeResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);

    @Test
    @DisplayName("agent guardrails provider wins over model inference")
    void agentGuardrailsProviderWinsOverModelInference() {
        Map<String, Object> agentDef = Map.of(
                "guardrails", "{\"provider\":\"openai\"}",
                "model", "claude-sonnet-4-6");
        when(providerFactory.resolveProviderByModel("claude-sonnet-4-6")).thenReturn("anthropic");

        String providerCode = LlmRuntimeResolver.resolveAgentProviderCode(
                objectMapper, providerFactory, agentDef);

        assertThat(providerCode).isEqualTo("openai");
    }

    @Test
    @DisplayName("agent guardrails preferredProvider wins over model inference")
    void agentGuardrailsPreferredProviderWinsOverModelInference() {
        Map<String, Object> agentDef = Map.of(
                "guardrails", "{\"preferredProvider\":\"ollama\"}",
                "model", "qwen3:8b");
        when(providerFactory.resolveProviderByModel("qwen3:8b")).thenReturn("qianwen");

        assertThat(LlmRuntimeResolver.resolveAgentProviderCode(
                objectMapper, providerFactory, agentDef))
                .isEqualTo("ollama");
    }

    @Test
    @DisplayName("agent model can infer provider and unresolved provider returns null")
    void agentModelCanInferProviderAndUnresolvedProviderReturnsNull() {
        when(providerFactory.resolveProviderByModel("gpt-5.1")).thenReturn("openai");

        assertThat(LlmRuntimeResolver.resolveAgentProviderCode(
                objectMapper, providerFactory, Map.of("model", "gpt-5.1")))
                .isEqualTo("openai");
        assertThat(LlmRuntimeResolver.resolveAgentProviderCode(
                objectMapper, providerFactory, Map.of("model", "unknown-model")))
                .isNull();
        assertThat(LlmRuntimeResolver.resolveAgentProviderCode(
                objectMapper, providerFactory, null))
                .isNull();
    }

    @Test
    @DisplayName("chat provider override wins and model inference falls back to auto discovery")
    void chatProviderOverrideWinsAndModelInferenceFallsBackToAutoDiscovery() {
        when(providerFactory.resolveProviderByModel("claude-sonnet-4-6")).thenReturn("anthropic");
        when(providerFactory.resolveProviderByModel("unknown-model")).thenReturn(null);

        assertThat(LlmRuntimeResolver.resolveChatProviderCode(
                providerFactory, "openai", "claude-sonnet-4-6"))
                .isEqualTo("openai");
        assertThat(LlmRuntimeResolver.resolveChatProviderCode(
                providerFactory, null, "claude-sonnet-4-6"))
                .isEqualTo("anthropic");
        assertThat(LlmRuntimeResolver.resolveChatProviderCode(
                providerFactory, "", "unknown-model"))
                .isNull();
        assertThat(LlmRuntimeResolver.resolveChatProviderCode(
                providerFactory, null, null))
                .isNull();
    }

    @Test
    @DisplayName("agent model uses configured model unless forced to fallback provider default")
    void agentModelUsesConfiguredModelUnlessForcedToFallbackProviderDefault() {
        Map<String, Object> agentDef = Map.of("model", "claude-sonnet-4-6");
        when(providerFactory.getDefaultModel("openai")).thenReturn("gpt-5.1");

        assertThat(LlmRuntimeResolver.resolveAgentModel(
                providerFactory, agentDef, "openai"))
                .isEqualTo("claude-sonnet-4-6");
        assertThat(LlmRuntimeResolver.resolveAgentModel(
                providerFactory, agentDef, "openai", true))
                .isEqualTo("gpt-5.1");
    }

    @Test
    @DisplayName("malformed guardrails do not prevent model based provider inference")
    void malformedGuardrailsDoNotPreventModelBasedProviderInference() {
        Map<String, Object> agentDef = Map.of(
                "guardrails", "{not-json",
                "model", "gpt-5.1");
        when(providerFactory.resolveProviderByModel("gpt-5.1")).thenReturn("openai");

        assertThat(LlmRuntimeResolver.resolveAgentProviderCode(
                objectMapper, providerFactory, agentDef))
                .isEqualTo("openai");
    }

    @Test
    @DisplayName("agent provider chain keeps preferred first and deduplicates fallbackProviders")
    void agentProviderChainKeepsPreferredFirstAndDedupesFallbacks() {
        Map<String, Object> agentDef = Map.of(
                "guardrails", "{\"fallbackProviders\":[\"openai\",\"anthropic\",\"openai\",\" \",null]}");

        assertThat(LlmRuntimeResolver.resolveAgentProviderChain(objectMapper, agentDef, "anthropic"))
                .containsExactly("anthropic", "openai");
    }

    @Test
    @DisplayName("agent provider chain ignores malformed guardrails and blank preferred provider")
    void agentProviderChainIgnoresMalformedGuardrailsAndBlankPreferredProvider() {
        assertThat(LlmRuntimeResolver.resolveAgentProviderChain(
                objectMapper, Map.of("guardrails", "{not-json"), "anthropic"))
                .containsExactly("anthropic");
        assertThat(LlmRuntimeResolver.resolveAgentProviderChain(
                objectMapper, Map.of("guardrails", "{\"fallbackProviders\":[\"openai\"]}"), " "))
                .containsExactly("openai");
        assertThat(LlmRuntimeResolver.resolveAgentProviderChain(
                objectMapper, null, null))
                .isEmpty();
    }

    @Test
    @DisplayName("agent step model uses agent model only when it belongs to the resolved provider")
    void agentStepModelUsesAgentModelOnlyWhenItBelongsToResolvedProvider() {
        Map<String, Object> agentDef = Map.of("model", "claude-sonnet-4-6");
        when(providerFactory.resolveProviderByModel("claude-sonnet-4-6")).thenReturn("anthropic");
        when(providerFactory.getDefaultModel("openai")).thenReturn("gpt-5.1");

        assertThat(LlmRuntimeResolver.resolveAgentModelForProvider(
                providerFactory, agentDef, "anthropic"))
                .isEqualTo("claude-sonnet-4-6");
        assertThat(LlmRuntimeResolver.resolveAgentModelForProvider(
                providerFactory, agentDef, "openai"))
                .isEqualTo("gpt-5.1");
    }
}
