package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.controller.CoreAgentController;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import com.auraboot.framework.application.tenant.MetaContext;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillToolCatalogScopeTest {
    @AfterEach
    void clearContext() { MetaContext.clear(); }
    @Test
    void forwardsOptionalModelScopeThroughTheExistingRegistry() {
        ToolProviderRegistry registry = mock(ToolProviderRegistry.class);
        when(registry.discoverAll(any())).thenReturn(List.of());
        when(registry.getProviderCodes()).thenReturn(List.of());
        CoreAgentController controller = new CoreAgentController(null, null, null, null,
                null, null, null, null, null, registry);

        MetaContext.setContext(7L, 9L, "user-9", "Editor");
        controller.listToolRegistry("tpm_customer");

        ArgumentCaptor<ToolDiscoveryContext> context = ArgumentCaptor.forClass(ToolDiscoveryContext.class);
        verify(registry).discoverAll(context.capture());
        assertThat(context.getValue().getTenantId()).isEqualTo(7L);
        assertThat(context.getValue().getModelHint()).isEqualTo("tpm_customer");
    }
}
