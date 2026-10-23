package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.service.AgentDefinitionResourceLifecycle;
import org.springframework.lang.Nullable;

import java.util.Collection;

/** Checks a complete resource batch before its first mutation or AI table access. */
final class PluginAgentResourceGuard {
    private PluginAgentResourceGuard() {}

    static void requireManifest(PluginManifestExtended manifest, @Nullable AgentDefinitionResourceLifecycle adapter) {
        if (manifest.getApplicationModules() != null) {
            for (String module : manifest.getApplicationModules()) {
                if (!"agent".equals(module)) throw new PluginException("Unknown required application module: " + module);
                requireInstalled(adapter);
            }
        }
        if (manifest.getAgentDefinitions() != null && !manifest.getAgentDefinitions().isEmpty()) {
            requireInstalled(adapter);
        }
    }

    static void requireResources(Collection<PluginResource> resources,
                                 @Nullable AgentDefinitionResourceLifecycle adapter) {
        if (resources.stream().anyMatch(resource -> resource.getResourceTypeEnum() == ResourceType.AGENT_DEFINITION)) {
            requireInstalled(adapter);
        }
    }

    static void requireType(ResourceType type, @Nullable AgentDefinitionResourceLifecycle adapter) {
        if (type == ResourceType.AGENT_DEFINITION) requireInstalled(adapter);
    }

    static void requireInstalled(@Nullable AgentDefinitionResourceLifecycle adapter) {
        if (adapter == null) throw new PluginException("Agent resource capability is not installed");
    }
}
