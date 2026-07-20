package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LlmProviderFactory")
class LlmProviderFactoryTest {

    @Test
    @DisplayName("resolveProvider returns effective stub provider when requested provider resolves to stub config")
    void resolveProviderReturnsEffectiveStubProvider() {
        LlmProvider anthropicProvider = mock(LlmProvider.class);
        when(anthropicProvider.getProviderCode()).thenReturn("anthropic");
        LlmProvider stubProvider = mock(LlmProvider.class);
        when(stubProvider.getProviderCode()).thenReturn(StubLlmProvider.PROVIDER_CODE);

        AgentProperties properties = new AgentProperties();
        properties.getAnthropic().setApiKey(StubLlmProvider.STUB_API_KEY_SENTINEL);
        properties.getAnthropic().setBaseUrl("https://api.anthropic.com");
        properties.getAnthropic().setDefaultModel("claude-sonnet-4-6");

        LlmProviderFactory factory = new LlmProviderFactory(
                List.of(anthropicProvider, stubProvider),
                mock(CloudConfigService.class),
                properties,
                new ObjectMapper(),
                mock(com.auraboot.framework.agent.trace.GenAiUsageRecorder.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));

        LlmProviderFactory.ProviderResolution resolution = factory.resolveProvider(7L, "anthropic");

        assertThat(resolution).isNotNull();
        assertThat(resolution.getRequestedProviderCode()).isEqualTo("anthropic");
        assertThat(resolution.getEffectiveProviderCode()).isEqualTo(StubLlmProvider.PROVIDER_CODE);
        assertThat(resolution.getConfig().getProviderCode()).isEqualTo(StubLlmProvider.PROVIDER_CODE);
        assertThat(resolution.getProvider()).isSameAs(stubProvider);
    }

    @Test
    @DisplayName("F2: resolveDefaultProviderCode returns stub code in stub-mode and null when nothing is configured")
    void resolveDefaultProviderCodeContract() {
        LlmProviderFactory factory = new LlmProviderFactory(
                List.of(),
                mock(CloudConfigService.class),
                new AgentProperties(),
                new ObjectMapper(),
                mock(com.auraboot.framework.agent.trace.GenAiUsageRecorder.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));

        // Nothing configured -> null; callers keep their honest fail-fast.
        assertThat(factory.resolveDefaultProviderCode(7L)).isNull();

        // Stub-mode -> the stub provider, so durable runs work on golden stacks
        // exactly like chat turns do.
        org.springframework.test.util.ReflectionTestUtils.setField(factory, "stubMode", true);
        assertThat(factory.resolveDefaultProviderCode(7L)).isEqualTo(StubLlmProvider.PROVIDER_CODE);
    }

    @Test
    @DisplayName("getProvider returns null instead of anthropic fallback when chat-completions adapter is missing")
    void getProviderReturnsNullWhenOpenAiCompatibleAdapterIsMissing() {
        LlmProvider anthropicProvider = mock(LlmProvider.class);
        when(anthropicProvider.getProviderCode()).thenReturn("anthropic");

        LlmProviderFactory factory = new LlmProviderFactory(
                List.of(anthropicProvider),
                mock(CloudConfigService.class),
                new AgentProperties(),
                new ObjectMapper(),
                mock(com.auraboot.framework.agent.trace.GenAiUsageRecorder.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));

        assertThat(factory.getProvider("deepseek")).isNull();
    }

    @Test
    @DisplayName("resolveConfig fails closed when CloudConfig lookup fails instead of using yml fallback")
    void resolveConfigFailsClosedWhenCloudConfigLookupFails() {
        CloudConfigService cloudConfigService = mock(CloudConfigService.class);
        when(cloudConfigService.getEffectiveConfig(7L, "llm", "anthropic"))
                .thenThrow(new IllegalStateException("cloud config db down"));

        AgentProperties properties = new AgentProperties();
        properties.getAnthropic().setApiKey(StubLlmProvider.STUB_API_KEY_SENTINEL);
        properties.getAnthropic().setBaseUrl("https://api.anthropic.com");
        properties.getAnthropic().setDefaultModel("claude-sonnet-4-6");

        LlmProviderFactory factory = new LlmProviderFactory(
                List.of(),
                cloudConfigService,
                properties,
                new ObjectMapper(),
                mock(com.auraboot.framework.agent.trace.GenAiUsageRecorder.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));

        assertThatThrownBy(() -> factory.resolveConfig(7L, "anthropic"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CloudConfig lookup failed for LLM/anthropic")
                .hasRootCauseMessage("cloud config db down");
    }

    @Test
    @DisplayName("resolveConfig fails closed when provider auto-discovery fails instead of using yml fallback")
    void resolveConfigFailsClosedWhenProviderAutoDiscoveryFails() {
        CloudConfigService cloudConfigService = mock(CloudConfigService.class);
        when(cloudConfigService.getEnabledProviders(7L, "llm"))
                .thenThrow(new IllegalStateException("cloud config db down"));

        AgentProperties properties = new AgentProperties();
        properties.getAnthropic().setApiKey(StubLlmProvider.STUB_API_KEY_SENTINEL);
        properties.getAnthropic().setBaseUrl("https://api.anthropic.com");
        properties.getAnthropic().setDefaultModel("claude-sonnet-4-6");

        LlmProviderFactory factory = new LlmProviderFactory(
                List.of(),
                cloudConfigService,
                properties,
                new ObjectMapper(),
                mock(com.auraboot.framework.agent.trace.GenAiUsageRecorder.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));

        assertThatThrownBy(() -> factory.resolveConfig(7L, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CloudConfig provider auto-discovery failed for LLM")
                .hasRootCauseMessage("cloud config db down");
    }

    @Test
    @DisplayName("resolveProvider falls back to a configured provider when the requested one has no usable config (CAP-04)")
    void resolveProviderFallsBackWhenRequestedUnconfigured() {
        LlmProvider anthropicProvider = mock(LlmProvider.class);
        when(anthropicProvider.getProviderCode()).thenReturn("anthropic");

        AgentProperties properties = new AgentProperties();
        properties.getAnthropic().setApiKey("sk-real-anthropic");   // anthropic configured via yml
        properties.getAnthropic().setBaseUrl("https://api.anthropic.com");
        properties.getAnthropic().setDefaultModel("claude-sonnet-4-6");

        LlmProviderFactory factory = new LlmProviderFactory(
                List.of(anthropicProvider),
                mock(CloudConfigService.class),   // no CloudConfig → deepseek unresolvable
                properties,
                new ObjectMapper(),
                mock(com.auraboot.framework.agent.trace.GenAiUsageRecorder.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));
        ReflectionTestUtils.setField(factory, "providerFallbackRaw", "anthropic");

        LlmProviderFactory.ProviderResolution resolution = factory.resolveProvider(7L, "deepseek");

        assertThat(resolution).isNotNull();
        assertThat(resolution.getRequestedProviderCode()).isEqualTo("deepseek");   // original preserved
        assertThat(resolution.getEffectiveProviderCode()).isEqualTo("anthropic");  // fell back
        // getProvider wraps the raw provider in the UsageRecording decorator; assert identity via code.
        assertThat(resolution.getProvider()).isNotNull();
        assertThat(resolution.getProvider().getProviderCode()).isEqualTo("anthropic");
    }

    @Test
    @DisplayName("resolveProvider returns null (no fallback) when the chain is empty and the provider is unconfigured (CAP-04)")
    void resolveProviderNoFallbackWhenChainEmpty() {
        LlmProviderFactory factory = new LlmProviderFactory(
                List.of(mock(LlmProvider.class)),
                mock(CloudConfigService.class),
                new AgentProperties(),
                new ObjectMapper(),
                mock(com.auraboot.framework.agent.trace.GenAiUsageRecorder.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));
        // providerFallbackRaw defaults to null → empty chain → behaviour unchanged
        assertThat(factory.resolveProvider(7L, "deepseek")).isNull();
    }

    @Test
    @DisplayName("resolveByTier routes a configured tier to the mapped provider + model (CAP-04 routing)")
    void resolveByTierRoutesConfiguredTier() {
        LlmProvider anthropicProvider = mock(LlmProvider.class);
        when(anthropicProvider.getProviderCode()).thenReturn("anthropic");

        AgentProperties properties = anthropicYmlProps("claude-sonnet-4-6");            // provider default model
        properties.getLlm().getModelRouting().put("smart", "anthropic:claude-opus-4-1"); // routed model differs

        LlmProviderFactory factory = factory(properties, anthropicProvider);

        LlmProviderFactory.ProviderResolution resolution = factory.resolveByTier(7L, "smart");

        assertThat(resolution).isNotNull();
        assertThat(resolution.getEffectiveProviderCode()).isEqualTo("anthropic");
        // The routed model is stamped onto the config, not the provider's yml default.
        assertThat(resolution.getConfig().getDefaultModel()).isEqualTo("claude-opus-4-1");
        assertThat(resolution.getProvider().getProviderCode()).isEqualTo("anthropic");
    }

    @Test
    @DisplayName("resolveByTier falls back to the default provider for an unmapped tier (CAP-04 routing)")
    void resolveByTierUnmappedTierFallsBackToDefault() {
        LlmProvider anthropicProvider = mock(LlmProvider.class);
        when(anthropicProvider.getProviderCode()).thenReturn("anthropic");

        AgentProperties properties = anthropicYmlProps("claude-sonnet-4-6");
        properties.getLlm().getModelRouting().put("smart", "anthropic:claude-opus-4-1"); // only "smart" mapped

        LlmProviderFactory factory = factory(properties, anthropicProvider);

        // "cheap" is not in the routing map → default provider, provider's own default model (not routed).
        LlmProviderFactory.ProviderResolution resolution = factory.resolveByTier(7L, "cheap");

        assertThat(resolution).isNotNull();
        assertThat(resolution.getEffectiveProviderCode()).isEqualTo("anthropic");
        assertThat(resolution.getConfig().getDefaultModel()).isEqualTo("claude-sonnet-4-6"); // default, not routed
    }

    @Test
    @DisplayName("resolveByTier with an empty routing map is a no-op → default provider (CAP-04 routing)")
    void resolveByTierEmptyMapIsNoOp() {
        LlmProvider anthropicProvider = mock(LlmProvider.class);
        when(anthropicProvider.getProviderCode()).thenReturn("anthropic");

        AgentProperties properties = anthropicYmlProps("claude-sonnet-4-6");   // model-routing left empty (default)

        LlmProviderFactory factory = factory(properties, anthropicProvider);

        // A tier name that would be routed if configured still resolves to the default provider.
        LlmProviderFactory.ProviderResolution resolution = factory.resolveByTier(7L, "smart");

        assertThat(resolution).isNotNull();
        assertThat(resolution.getEffectiveProviderCode()).isEqualTo("anthropic");
        assertThat(resolution.getConfig().getDefaultModel()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    @DisplayName("resolveByTier inherits the availability-fallback chain and keeps the fallback provider's model (CAP-04 routing)")
    void resolveByTierInheritsAvailabilityFallback() {
        LlmProvider anthropicProvider = mock(LlmProvider.class);
        when(anthropicProvider.getProviderCode()).thenReturn("anthropic");

        AgentProperties properties = anthropicYmlProps("claude-sonnet-4-6");            // only anthropic configured
        properties.getLlm().getModelRouting().put("cheap", "deepseek:deepseek-chat");  // deepseek unconfigured

        LlmProviderFactory factory = factory(properties, anthropicProvider);
        ReflectionTestUtils.setField(factory, "providerFallbackRaw", "anthropic");     // availability fallback chain

        LlmProviderFactory.ProviderResolution resolution = factory.resolveByTier(7L, "cheap");

        assertThat(resolution).isNotNull();
        assertThat(resolution.getRequestedProviderCode()).isEqualTo("deepseek");    // routed provider preserved
        assertThat(resolution.getEffectiveProviderCode()).isEqualTo("anthropic");   // fell back via the shared chain
        // The routed model (deepseek-chat) is NOT forced onto the anthropic fallback provider.
        assertThat(resolution.getConfig().getDefaultModel()).isEqualTo("claude-sonnet-4-6");
    }

    // ---- helpers (mirror the existing construction pattern) ----

    private static AgentProperties anthropicYmlProps(String defaultModel) {
        AgentProperties properties = new AgentProperties();
        properties.getAnthropic().setApiKey("sk-real-anthropic");
        properties.getAnthropic().setBaseUrl("https://api.anthropic.com");
        properties.getAnthropic().setDefaultModel(defaultModel);
        return properties;
    }

    private static LlmProviderFactory factory(AgentProperties properties, LlmProvider... providers) {
        return new LlmProviderFactory(
                List.of(providers),
                mock(CloudConfigService.class),
                properties,
                new ObjectMapper(),
                mock(com.auraboot.framework.agent.trace.GenAiUsageRecorder.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));
    }
}
