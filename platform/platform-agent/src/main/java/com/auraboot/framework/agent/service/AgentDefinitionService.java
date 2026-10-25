package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Service layer for AgentDefinition lookups.
 * Wraps mapper access so controllers do not directly depend on the mapper layer.
 */
@Service
@RequiredArgsConstructor
public class AgentDefinitionService {

    private final AgentDefinitionMapper agentDefinitionMapper;

    /**
     * Find an agent definition by its PID.
     *
     * @param pid the public identifier
     * @return the agent definition, or null if not found
     */
    public AgentDefinition findByPid(String pid) {
        return agentDefinitionMapper.findByPid(pid);
    }
}
