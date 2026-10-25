package com.auraboot.framework.agentchat.employee;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for managing AI employees — agents that appear as virtual team members.
 */
@Slf4j
@Service
public class AgentEmployeeService {

    private final AgentDefinitionMapper agentDefinitionMapper;

    public AgentEmployeeService(AgentDefinitionMapper agentDefinitionMapper) {
        this.agentDefinitionMapper = agentDefinitionMapper;
    }

    /**
     * List all active AI employees for the given tenant.
     */
    public List<AgentDefinition> listEmployees(Long tenantId) {
        return agentDefinitionMapper.findEmployees(tenantId);
    }
}
