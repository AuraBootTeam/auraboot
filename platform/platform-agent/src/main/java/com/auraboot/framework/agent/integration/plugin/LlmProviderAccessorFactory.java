package com.auraboot.framework.agent.integration.plugin;

import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.plugin.extension.AiProviderAccessor;
import com.auraboot.framework.plugin.extension.AiProviderAccessorFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Keeps model-provider construction outside the command pipeline. */
@Component
@RequiredArgsConstructor
public class LlmProviderAccessorFactory implements AiProviderAccessorFactory {
    private final LlmProviderFactory providerFactory;
    private final ObjectMapper objectMapper;

    @Override
    public AiProviderAccessor create(Long tenantId) {
        return new LlmProviderAccessorImpl(providerFactory, objectMapper, tenantId);
    }
}
