package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.controller.CoreAgentController;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.spi.AgentExecutionService;
import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CoreAgentProviderTenantScopeTest {
    @AfterEach void clearContext() { MetaContext.clear(); }

    @ParameterizedTest
    @ValueSource(longs = {7L, 11L})
    void discoveryAndStatusUseTheCurrentTenant(long tenant) {
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        AgentProperties properties = mock(AgentProperties.class);
        AgentExecutionService execution = mock(AgentExecutionService.class);
        var provider = LlmProviderFactory.ProviderInfo.builder()
                .providerCode("tenant-service").displayName("Tenant service").configured(true).build();
        when(factory.listConfiguredProviders(tenant)).thenReturn(List.of(provider));
        var controller = new CoreAgentController(properties, factory, execution, null,
                null, null, null, null, null, null);
        MetaContext.setContext(tenant, 9L, "user-9", "Editor");
        assertThat(controller.listConfiguredProviders().getBody()).isEqualTo(List.of(provider));
        assertThat(controller.getStatus().getBody()).containsEntry("configuredProviders", 1);
        verify(factory, times(2)).listConfiguredProviders(tenant);
        verify(factory, never()).listConfiguredProviders(null);
    }
}
