package com.auraboot.framework.agent.identity;

public class AgentNotResolvedException extends RuntimeException {

    private final long tenantId;
    private final String agentCode;

    public AgentNotResolvedException(long tenantId, String agentCode) {
        super("No active ab_agent_definition row for tenantId=" + tenantId + ", agentCode='" + agentCode + "'");
        this.tenantId = tenantId;
        this.agentCode = agentCode;
    }

    public long getTenantId() {
        return tenantId;
    }

    public String getAgentCode() {
        return agentCode;
    }
}
