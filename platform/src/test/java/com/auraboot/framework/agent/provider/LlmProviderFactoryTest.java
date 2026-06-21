package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
