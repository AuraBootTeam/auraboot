package com.auraboot.framework.agent.runtime.policy;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Resolves stored agent definition rows into runtime profile policy.
 */
public interface AgentProfileResolver {

    AgentProfile resolve(ObjectMapper objectMapper, Map<String, Object> agentDefinition);
}
