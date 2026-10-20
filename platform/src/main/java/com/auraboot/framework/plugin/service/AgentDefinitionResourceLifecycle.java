package com.auraboot.framework.plugin.service;

import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.entity.PluginResource;

/** Optional agent resource provider; lifecycle calls participate in the caller's transaction. */
public interface AgentDefinitionResourceLifecycle {
    boolean exists(Long tenantId, String agentCode);
    PluginResource importAgentDefinition(AgentDefinitionDTO dto, String pluginPid, String importId,
                                        Long tenantId, ImportRequest.ConflictStrategy conflictStrategy);
    void rollback(PluginResource resource);
    void restore(PluginResource resource);
}
