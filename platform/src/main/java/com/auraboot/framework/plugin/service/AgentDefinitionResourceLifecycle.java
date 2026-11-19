package com.auraboot.framework.plugin.service;

import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.entity.PluginResource;

/** Optional agent resource provider; lifecycle calls participate in the caller's transaction. */
public interface AgentDefinitionResourceLifecycle {
    boolean exists(Long tenantId, String agentCode);
    PluginResource importAgentDefinition(AgentDefinitionDTO dto, String pluginPid, String importId,
                                        Long tenantId, ImportRequest.ConflictStrategy conflictStrategy);
    /** Withdraw the current owned import image, refusing changed content or protection state. */
    void rollback(PluginResource resource);
    /** Restore a versioned pre-import image and tracking; this is not an arbitrary undelete API. */
    void restore(PluginResource resource);
}
