package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
                new ObjectMapper());

        LlmProviderFactory.ProviderResolution resolution = factory.resolveProvider(7L, "anthropic");

        assertThat(resolution).isNotNull();
        assertThat(resolution.getRequestedProviderCode()).isEqualTo("anthropic");
        assertThat(resolution.getEffectiveProviderCode()).isEqualTo(StubLlmProvider.PROVIDER_CODE);
        assertThat(resolution.getConfig().getProviderCode()).isEqualTo(StubLlmProvider.PROVIDER_CODE);
        assertThat(resolution.getProvider()).isSameAs(stubProvider);
    }
}
